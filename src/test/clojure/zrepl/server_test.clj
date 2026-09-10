(ns zrepl.server-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as jsonista]
   [zrepl.lsp.schemas :as schemas]
   [zrepl.rpc :as rpc]
   [zrepl.server :as sut])
  (:import
   (java.io
    ByteArrayInputStream
    ByteArrayOutputStream)))

(set! *warn-on-reflection* true)

(def ^:private json-mapper jsonista/keyword-keys-object-mapper)

(defn- fresh-ctx
  "Context wired to the real server handlers, with a synchronous executor
   and an in-memory out stream for capturing responses."
  []
  (let [out (ByteArrayOutputStream.)]
    {:in (ByteArrayInputStream. (byte-array 0))
     :out out
     :lock (Object.)
     :next-id (atom 0)
     :pending-responses (atom {})
     :executor (proxy [java.util.concurrent.AbstractExecutorService] []
                 (execute [^java.lang.Runnable runnable] (.run runnable))
                 (shutdown [])
                 (shutdownNow [])
                 (isShutdown [] false)
                 (isTerminated [] false)
                 (awaitTermination [_ _ _] true))
     :request-handlers @#'sut/request-handlers
     :notification-handlers @#'sut/notification-handlers
     :after-response @#'sut/after-response
     :state (atom {:shutdown-received? false :buffers {}})}))

(defn- reset-state!
  "Handlers run against the private server state atom, not ctx :state —
   reset it for test isolation."
  []
  (reset! @#'sut/state {:shutdown-received? false :buffers {}}))

(defn- drain-responses
  [ctx]
  (let [raw (.toString ^ByteArrayOutputStream (:out ctx) "UTF-8")]
    (mapv #(jsonista/read-value % json-mapper)
          (filter seq (str/split raw #"Content-Length: \d+\r\n\r\n")))))

(defn- handle
  "Dispatches `msg` and returns all messages written to the out stream."
  [ctx msg]
  (rpc/handle-message! ctx msg)
  (drain-responses ctx))

(deftest initialize-test
  (let [ctx (fresh-ctx)
        [resp refresh :as msgs] (handle ctx {:jsonrpc "2.0" :id 1 :method "initialize"
                                             :params {:capabilities {}}})]
    (testing "response carries capabilities + serverInfo and validates"
      (is (= 1 (:id resp)))
      (is (schemas/valid? "initialize" :result (:result resp)))
      (is (= ["zrepl/eval"] (-> resp :result :capabilities :executeCommandProvider :commands)))
      (is (true? (-> resp :result :capabilities :inlayHintProvider)))
      (is (= 1 (-> resp :result :capabilities :textDocumentSync)))
      (is (= "zrepl" (-> resp :result :serverInfo :name))))
    (testing "refresh request follows the response (bidirectional channel)"
      (is (= 2 (count msgs)))
      (is (= "workspace/inlayHint/refresh" (:method refresh)))
      (is (= 1 (:id refresh)))
      (is (not (contains? refresh :params))))))

(deftest execute-command-test
  (testing "zrepl/eval stub"
    (let [ctx (fresh-ctx)
          [resp] (handle ctx {:jsonrpc "2.0" :id 2 :method "workspace/executeCommand"
                              :params {:command "zrepl/eval" :arguments ["(+ 2 2)"]}})]
      (is (= {:value "stub"} (:result resp)))))
  (testing "unknown command -> -32601"
    (let [ctx (fresh-ctx)
          [resp] (handle ctx {:jsonrpc "2.0" :id 3 :method "workspace/executeCommand"
                              :params {:command "zrepl/nope"}})]
      (is (= -32601 (-> resp :error :code))))))

(deftest inlay-hint-test
  (let [ctx (fresh-ctx)
        [resp] (handle ctx {:jsonrpc "2.0" :id 4 :method "textDocument/inlayHint"
                            :params {:textDocument {:uri "file:///t.clj"}
                                     :range {:start {:line 0 :character 0}
                                             :end {:line 0 :character 1}}}})]
    (is (= [] (:result resp)))
    (is (schemas/valid? "textDocument/inlayHint" :result (:result resp)))))

(deftest shutdown-test
  (testing "shutdown responds null and sets the flag"
    (let [ctx (fresh-ctx)
          [resp] (handle ctx {:jsonrpc "2.0" :id 5 :method "shutdown"})]
      (is (nil? (:result resp)))
      (is (true? (sut/shutdown-received?))))))

(deftest notifications-noop-test
  (doseq [method ["initialized"
                  "workspace/didChangeConfiguration"
                  "textDocument/didSave"]]
    (testing (str method " is a no-op and produces no output")
      (let [ctx (fresh-ctx)]
        (is (empty? (handle ctx {:jsonrpc "2.0" :method method :params {}})))))))

(deftest buffer-tracking-test
  (let [did-open-msg (fn [uri text version]
                       {:jsonrpc "2.0" :method "textDocument/didOpen"
                        :params {:textDocument {:uri uri :languageId "clojure"
                                                :version version :text text}}})
        did-change-msg (fn [uri version text]
                         {:jsonrpc "2.0" :method "textDocument/didChange"
                          :params {:textDocument {:uri uri :version version}
                                   :contentChanges [{:text text}]}})
        did-close-msg (fn [uri]
                        {:jsonrpc "2.0" :method "textDocument/didClose"
                         :params {:textDocument {:uri uri}}})
        uri "file:///w/core.clj"]
    (testing "didOpen stores text/version/languageId and validates"
      (reset-state!)
      (let [ctx (fresh-ctx)
            params (get-in (did-open-msg uri "(+ 1 2)" 0) [:params])]
        (is (schemas/valid? "textDocument/didOpen" :params params))
        (handle ctx (did-open-msg uri "(+ 1 2)" 0))
        (is (= {:text "(+ 1 2)" :version 0 :language-id "clojure"}
               (sut/buffer uri)))))
    (testing "didChange applies the last full-text event and bumps version"
      (reset-state!)
      (let [ctx (fresh-ctx)]
        (handle ctx (did-open-msg uri "(+ 1 2)" 0))
        (is (empty? (handle ctx (did-change-msg uri 1 "(+ 1 2) "))))
        (is (= {:text "(+ 1 2) " :version 1 :language-id "clojure"}
               (sut/buffer uri)))))
    (testing "didChange keeps only the last event of a multi-event batch"
      (reset-state!)
      (let [ctx (fresh-ctx)]
        (handle ctx (did-open-msg uri "(+ 1 2)" 0))
        (handle ctx (update-in (did-change-msg uri 2 "(second)")
                               [:params :contentChanges] into
                               [{:text "(first)"} {:text "(last)"}]))
        (is (= "(last)" (:text (sut/buffer uri))))))
    (testing "didChange with empty contentChanges leaves the entry unchanged"
      (reset-state!)
      (let [ctx (fresh-ctx)]
        (handle ctx (did-open-msg uri "(+ 1 2)" 0))
        (handle ctx (assoc-in (did-change-msg uri 3 "never-applied")
                              [:params :contentChanges] []))
        (is (= {:text "(+ 1 2)" :version 0 :language-id "clojure"}
               (sut/buffer uri)))))
    (testing "didChange for an unknown uri creates no buffer"
      (reset-state!)
      (let [ctx (fresh-ctx)]
        (is (empty? (handle ctx (did-change-msg "file:///ghost.clj" 1 "x"))))
        (is (nil? (sut/buffer "file:///ghost.clj")))
        (is (= {} (sut/buffers)))))
    (testing "re-didOpen resets the entry wholesale"
      (reset-state!)
      (let [ctx (fresh-ctx)]
        (handle ctx (did-open-msg uri "(+ 1 2)" 0))
        (handle ctx (did-change-msg uri 1 "(+ 1 2) "))
        (handle ctx (did-open-msg uri "(- 4 5)" 0))
        (is (= {:text "(- 4 5)" :version 0 :language-id "clojure"}
               (sut/buffer uri)))))
    (testing "didClose removes the buffer and validates"
      (reset-state!)
      (let [ctx (fresh-ctx)]
        (handle ctx (did-open-msg uri "(+ 1 2)" 0))
        (is (schemas/valid? "textDocument/didClose" :params
                            (get-in (did-close-msg uri) [:params])))
        (handle ctx (did-close-msg uri))
        (is (nil? (sut/buffer uri)))
        (is (= {} (sut/buffers)))))))

(deftest did-save-noop-test
  (testing "didSave produces no output and does not disturb tracked buffers"
    (reset-state!)
    (let [ctx (fresh-ctx)
          uri "file:///s.clj"]
      (handle ctx {:jsonrpc "2.0" :method "textDocument/didOpen"
                   :params {:textDocument {:uri uri :languageId "clojure"
                                           :version 0 :text "(a)"}}})
      (is (empty? (handle ctx {:jsonrpc "2.0" :method "textDocument/didSave"
                               :params {:textDocument {:uri uri}}})))
      (is (= {:text "(a)" :version 0 :language-id "clojure"}
             (sut/buffer uri))))))

(deftest invalid-params-test
  (testing "initialize with garbage params -> -32602"
    (let [ctx (fresh-ctx)
          [resp] (handle ctx {:jsonrpc "2.0" :id 6 :method "initialize" :params "garbage"})]
      (is (= -32602 (-> resp :error :code))))))
