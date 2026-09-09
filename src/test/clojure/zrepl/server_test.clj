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
     :state (atom {:shutdown-received? false})}))

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
                  "textDocument/didOpen"
                  "textDocument/didChange"
                  "textDocument/didClose"]]
    (testing (str method " is a no-op and produces no output")
      (let [ctx (fresh-ctx)]
        (is (empty? (handle ctx {:jsonrpc "2.0" :method method :params {}})))))))

(deftest invalid-params-test
  (testing "initialize with garbage params -> -32602"
    (let [ctx (fresh-ctx)
          [resp] (handle ctx {:jsonrpc "2.0" :id 6 :method "initialize" :params "garbage"})]
      (is (= -32602 (-> resp :error :code))))))
