(ns zrepl.rpc-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as jsonista]
   [zrepl.rpc :as sut])
  (:import
   (java.io
    ByteArrayInputStream
    ByteArrayOutputStream)
   (java.util.concurrent
    CountDownLatch
    TimeUnit)))

(set! *warn-on-reflection* true)

(def ^:private json-mapper jsonista/keyword-keys-object-mapper)

(defn- bytes->framed-stream
  [^bytes bs]
  (ByteArrayInputStream. bs))

(defn- encode
  "Frames a raw JSON string (lets us write deliberately malformed bodies)."
  ^bytes [^String json]
  (let [body (.getBytes json "UTF-8")
        header (.getBytes (format "Content-Length: %d\r\n\r\n" (alength body)) "UTF-8")
        out (ByteArrayOutputStream.)]
    (.write out header)
    (.write out body)
    (.toByteArray out)))

(defn- drain-out
  [^ByteArrayOutputStream out]
  (.flush out)
  (let [raw (.toString out "UTF-8")]
    (mapv #(jsonista/read-value % json-mapper)
          (filter seq (str/split raw #"Content-Length: \d+\r\n\r\n")))))

(defn- sync-executor
  "ExecutorService that runs tasks inline on the calling thread — keeps
   dispatch tests synchronous (and satisfies the `.submit` on-close path)."
  []
  (proxy [java.util.concurrent.AbstractExecutorService] []
    (execute [^java.lang.Runnable runnable] (.run runnable))
    (shutdown [])
    (shutdownNow [])
    (isShutdown [] false)
    (isTerminated [] false)
    (awaitTermination [_ _ _] true)))

(defn- test-ctx
  "A minimal context: in-memory out, synchronous executor, recording handlers."
  [{:keys [in]}]
  (let [out (ByteArrayOutputStream.)]
    {:in in
     :out out
     :lock (Object.)
     :next-id (atom 0)
     :pending-responses (atom {})
     :executor (sync-executor)
     :request-handlers {}
     :notification-handlers {}
     :after-response {}
     :state (atom {})
     :out-buffer out}))

(defn- read-one-response
  [ctx]
  (first (drain-out (:out-buffer ctx))))

;;;; Framing

(deftest frame-bytes-test
  (let [framed (sut/frame-bytes {:jsonrpc "2.0" :id 1 :result {}})
        s (String. ^bytes framed "UTF-8")]
    (is (str/starts-with? s "Content-Length: "))
    (is (str/includes? s "\r\n\r\n"))
    (let [[_ header body] (re-matches #"Content-Length: (\d+)\r\n\r\n(.*)" s)]
      (is (= (count body) (parse-long header)))
      (is (= {:jsonrpc "2.0" :id 1 :result {}}
             (jsonista/read-value body json-mapper))))))

(deftest write-message-test
  (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})]
    (sut/write-message! ctx {:jsonrpc "2.0" :id 7 :method "ping"})
    (is (= [{:jsonrpc "2.0" :id 7 :method "ping"}] (drain-out (:out-buffer ctx))))))

(deftest send-notification!-test
  (testing "with params"
    (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})]
      (sut/send-notification! ctx "textDocument/publishDiagnostics" {:uri "file:///t.clj" :diagnostics []})
      (is (= [{:jsonrpc "2.0" :method "textDocument/publishDiagnostics"
               :params {:uri "file:///t.clj" :diagnostics []}}]
             (drain-out (:out-buffer ctx))))))
  (testing "nil params are omitted"
    (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})]
      (sut/send-notification! ctx "workspace/inlayHint/refresh" nil)
      (is (= [{:jsonrpc "2.0" :method "workspace/inlayHint/refresh"}]
             (drain-out (:out-buffer ctx)))))))

(deftest read-message-test
  (testing "single message"
    (let [in (bytes->framed-stream (encode "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}"))]
      (is (= {:jsonrpc "2.0" :id 1 :method "x"} (sut/read-message! in)))))

  (testing "two messages back to back"
    (let [in (bytes->framed-stream
              (byte-array (into (vec (encode "{\"id\":1,\"method\":\"a\"}"))
                                (vec (encode "{\"id\":2,\"method\":\"b\"}")))))]
      (is (= {:id 1 :method "a"} (sut/read-message! in)))
      (is (= {:id 2 :method "b"} (sut/read-message! in)))))

  (testing "extra headers (Content-Type with charset) are tolerated"
    (let [body "{\"id\":1}"
          raw (str "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n"
                   (format "Content-Length: %d\r\n\r\n%s" (count body) body))
          in (bytes->framed-stream (.getBytes raw "UTF-8"))]
      (is (= {:id 1} (sut/read-message! in)))))

  (testing "clean EOF returns nil"
    (is (nil? (sut/read-message! (bytes->framed-stream (byte-array 0)))))))

(deftest malformed-frames-test
  (testing "garbage header line throws ::malformed-header"
    (let [in (bytes->framed-stream (.getBytes "Not-A-Header\r\n\r\n{}" "UTF-8"))]
      (is (thrown-with-msg? Exception #"Malformed header"
                            (sut/read-message! in)))))

  (testing "missing Content-Length throws"
    (let [in (bytes->framed-stream (.getBytes "Content-Type: text/plain\r\n\r\n{}" "UTF-8"))]
      (is (thrown-with-msg? Exception #"Content-Length"
                            (sut/read-message! in)))))

  (testing "truncated body throws"
    (let [raw (str "Content-Length: 100\r\n\r\n" "{\"id\":1}")
          in (bytes->framed-stream (.getBytes raw "UTF-8"))]
      (is (thrown-with-msg? Exception #"Truncated body"
                            (sut/read-message! in))))))

(deftest fragmented-stream-test
  (testing "message split into tiny chunks across many reads"
    (let [bs (encode "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"slow\"}")
          in (new java.io.SequenceInputStream
                  ^java.util.Enumeration
                  (java.util.Collections/enumeration
                   (map (fn [b] (ByteArrayInputStream. (byte-array [b]))) bs)))]
      (is (= {:jsonrpc "2.0" :id 42 :method "slow"} (sut/read-message! in))))))

;;;; Classification

(deftest classify-test
  (testing "request: method + id"
    (is (= ::sut/request (sut/classify {:jsonrpc "2.0" :id 1 :method "m" :params {}}))))
  (testing "notification: method, no id"
    (is (= ::sut/notification (sut/classify {:jsonrpc "2.0" :method "m"}))))
  (testing "response: id, no method"
    (is (= ::sut/response (sut/classify {:jsonrpc "2.0" :id 5 :result 1})))
    (is (= ::sut/response (sut/classify {:jsonrpc "2.0" :id 5 :error {:code 1 :message "m"}}))))
  (testing "invalid"
    (is (= ::sut/invalid (sut/classify {:jsonrpc "2.0"})))
    (is (= ::sut/invalid (sut/classify "not a map")))
    (is (= ::sut/invalid (sut/classify nil)))))

;;;; Dispatch

(deftest dispatch-request-test
  (testing "handler result is written as a response"
    (let [ctx (assoc (test-ctx {:in (bytes->framed-stream (byte-array 0))})
                     :request-handlers {"add" (fn [_ {:keys [a b]}] (+ a b))})]
      (sut/handle-message! ctx {:jsonrpc "2.0" :id 1 :method "add" :params {:a 1 :b 2}})
      (is (= {:jsonrpc "2.0" :id 1 :result 3} (read-one-response ctx)))))

  (testing "unknown method -> -32601"
    (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})]
      (sut/handle-message! ctx {:jsonrpc "2.0" :id 2 :method "nope"})
      (is (= -32601 (-> (read-one-response ctx) :error :code)))))

  (testing "handler exception -> -32603 with custom code support"
    (let [ctx (assoc (test-ctx {:in (bytes->framed-stream (byte-array 0))})
                     :request-handlers {"boom" (fn [_ _] (throw (ex-info "kaboom" {:rpc/code -32000})))})]
      (sut/handle-message! ctx {:jsonrpc "2.0" :id 3 :method "boom"})
      (let [resp (read-one-response ctx)]
        (is (= -32000 (-> resp :error :code)))
        (is (str/includes? (-> resp :error :message) "kaboom")))))

  (testing "invalid params -> -32602"
    (let [ctx (assoc (test-ctx {:in (bytes->framed-stream (byte-array 0))})
                     :request-handlers {"initialize" (fn [_ _] {})})]
      (sut/handle-message! ctx {:jsonrpc "2.0" :id 4 :method "initialize" :params "garbage"})
      (is (= -32602 (-> (read-one-response ctx) :error :code))))))

(deftest dispatch-notification-test
  (testing "handler receives params"
    (let [received (atom nil)
          ctx (assoc (test-ctx {:in (bytes->framed-stream (byte-array 0))})
                     :notification-handlers {"note" (fn [_ params] (reset! received params))})]
      (sut/handle-message! ctx {:jsonrpc "2.0" :method "note" :params {:x 1}})
      (is (= {:x 1} @received))
      ;; no output produced for notifications
      (is (empty? (drain-out (:out-buffer ctx))))))

  (testing "handler exception is swallowed"
    (let [ctx (assoc (test-ctx {:in (bytes->framed-stream (byte-array 0))})
                     :notification-handlers {"boom" (fn [_ _] (throw (RuntimeException. "x")))})]
      (is (nil? (sut/handle-message! ctx {:jsonrpc "2.0" :method "boom"})))))

  (testing "unknown notification is ignored"
    (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})]
      (is (nil? (sut/handle-message! ctx {:jsonrpc "2.0" :method "who/ami"})))))

  (testing "unknown $/ notification is ignored too (LSP: must be ignored)"
    (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})]
      (is (nil? (sut/handle-message! ctx {:jsonrpc "2.0" :method "$/setTrace" :params {:value "off"}}))))))

(deftest dispatch-response-test
  (testing "client response is routed to the pending promise"
    (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})
          promise (sut/send-request! ctx "workspace/inlayHint/refresh" nil)]
      (sut/handle-message! ctx {:jsonrpc "2.0" :id 1 :result nil})
      (is (= {:jsonrpc "2.0" :id 1 :result nil} (deref promise 1000 ::timeout)))))

  (testing "response with unknown id is dropped, no throw"
    (let [ctx (test-ctx {:in (bytes->framed-stream (byte-array 0))})]
      (is (nil? (sut/handle-message! ctx {:jsonrpc "2.0" :id 99 :result nil}))))))

;;;; start! end-to-end over streams

(deftest start!-test
  (testing "requests dispatched; loop ends at EOF; on-close runs once"
    (let [closed (CountDownLatch. 1)
          out (ByteArrayOutputStream.)
          in (bytes->framed-stream
              (byte-array (into (vec (encode "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":{\"v\":9}}"))
                                (vec (byte-array 0)))))
          ctx (sut/start! in out
                          {:request-handlers {"echo" (fn [_ params] params)}
                           :on-close (fn [_] (.countDown closed))})]
      (is (.await closed 5 TimeUnit/SECONDS) "on-close should fire at EOF")
      (is (not (.isAlive ^Thread (:reader-thread ctx))))
      (is (= {:jsonrpc "2.0" :id 1 :result {:v 9}} (first (drain-out out))))))

  (testing "malformed frames terminate the loop and fire on-close"
    (let [closed (CountDownLatch. 1)
          in (bytes->framed-stream (.getBytes "total garbage" "UTF-8"))]
      (sut/start! in (ByteArrayOutputStream.)
                  {:on-close (fn [_] (.countDown closed))})
      (is (.await closed 5 TimeUnit/SECONDS) "on-close should fire after malformed input"))))

;;;; Stdout guard

(defn- root-var-value
  "Reads a var's root binding from a fresh thread (futures convey bindings,
   raw threads do not)."
  [v]
  (let [p (promise)]
    (.start (Thread. ^Runnable (fn [] (deliver p @v))))
    (deref p 5000 nil)))

(deftest discard-stdout!-test
  (let [original-out System/out
        original-err System/err
        original-root-out (root-var-value #'*out*)
        original-root-err (root-var-value #'*err*)
        err (java.io.ByteArrayOutputStream.)]
    (try
      (System/setErr (java.io.PrintStream. err true "UTF-8"))
      ;; route the root *err* writer to the replaced System/err, so the
      ;; test can observe what the guard forwards
      (alter-var-root #'*err* (constantly (java.io.PrintWriter. System/err true)))
      (sut/discard-stdout!)
      (is (not= original-out System/out) "System/out replaced by the guard")
      (is (not= original-root-out (root-var-value #'*out*))
          "root *out* routed through the guard (println protection)")
      ;; a print from an unbound thread goes through root *out* -> guard -> stderr
      (let [printed (promise)]
        (.start (Thread. ^Runnable (fn [] (println "leaked print") (deliver printed true))))
        (is (deref printed 5000 false) "print thread finished")
        (is (str/includes? (.toString err "UTF-8") "leaked print"))
        (is (str/includes? (.toString err "UTF-8") "discarded stdout")))
      (finally
        (System/setOut original-out)
        (System/setErr original-err)
        (alter-var-root #'*out* (constantly original-root-out))
        (alter-var-root #'*err* (constantly original-root-err))))))
