(ns zrepl.main-integration-test
  "Acceptance gate for S0.2: run `clojure -M -m zrepl.main` as a subprocess
   (via babashka.process), drive the full LSP cycle over stdio
   (initialize -> initialized -> zrepl/eval -> inlayHint -> didOpen ->
   shutdown -> exit) and assert responses plus the exit code."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [jsonista.core :as jsonista])
  (:import
   (java.io
    BufferedReader
    BufferedWriter
    InputStreamReader
    OutputStreamWriter)))

(set! *warn-on-reflection* true)

(def ^:private json-mapper jsonista/keyword-keys-object-mapper)

(def ^:private watchdog-ms 120000)
(def ^:private exit-wait-ms 30000)

(defn- frame
  ^String [msg]
  (let [body (jsonista/write-value-as-string msg)]
    ;; bodies here are ASCII, so char count == byte count is safe;
    ;; still, count the actual UTF-8 bytes
    (str "Content-Length: " (count (.getBytes body "UTF-8")) "\r\n\r\n" body)))

(defn- read-message
  "Reads one LSP message from the server's stdout. Returns the parsed map
   or nil at EOF."
  [^BufferedReader rdr]
  (loop []
    (if-let [first-line (.readLine rdr)]
      (if (str/blank? first-line)
        (recur)
        ;; collect the rest of the headers up to the blank separator
        (let [headers (loop [lines [first-line]]
                        (let [line (.readLine rdr)]
                          (cond
                            (or (nil? line) (str/blank? line)) lines
                            :else (recur (conj lines line)))))
              content-length (some->> headers
                                      (keep #(re-matches #"(?i)Content-Length:\s*(\d+)" %))
                                      first
                                      second
                                      parse-long)]
          (when-not content-length
            (throw (ex-info (str "No Content-Length in headers: " (pr-str headers)) {})))
            ;; bodies are ASCII; read exactly content-length chars
          (let [chars (char-array content-length)
                read (loop [off 0]
                       (let [r (.read rdr chars off (- content-length off))]
                         (cond
                           (neg? r) (throw (ex-info "EOF while reading message body" {}))
                           (= content-length (+ off r)) (+ off r)
                           :else (recur (+ off r)))))]
            (jsonista/read-value (String. chars 0 (int read)) json-mapper))))
      nil)))

(defn- next-response-for
  "Reads messages until the response with `id` arrives; every other message
   (e.g. server->client requests) is parked in `inbox`. Returns ::eof at EOF."
  [rdr inbox id]
  (loop []
    (if-let [msg (read-message rdr)]
      (if (and (contains? msg :id) (not (contains? msg :method)) (= id (:id msg)))
        msg
        (do (vswap! inbox conj msg)
            (recur)))
      ::eof)))

(defn- start-server
  []
  (p/process {:cmd ["clojure" "-M" "-m" "zrepl.main"]
              :err :string}))

(defn- stdin-writer
  [proc]
  (let [writer (BufferedWriter. (OutputStreamWriter. ^java.io.OutputStream (:in proc) "UTF-8"))]
    (fn send! [^String s]
      (.write writer s)
      (.flush writer))))

(defn- stdout-reader
  [proc]
  (BufferedReader. (InputStreamReader. ^java.io.InputStream (:out proc) "UTF-8")))

(deftest ^:integration full-lsp-cycle-test
  (testing "scripted client completes initialize -> initialized -> eval -> shutdown -> exit"
    (let [proc (start-server)
          send! (stdin-writer proc)
          rdr (stdout-reader proc)
          inbox (volatile! [])
          watchdog (future
                     (Thread/sleep (long watchdog-ms))
                     (when (p/alive? proc)
                       (p/destroy proc)))]
      (try
        ;; 1. initialize; server -> client refresh request follows the response
        (send! (frame {:jsonrpc "2.0" :id 1 :method "initialize" :params {:capabilities {}}}))
        (let [resp (read-message rdr)]
          (is (= 1 (:id resp)) (str "got: " (pr-str resp)))
          (is (= ["zrepl/eval"] (-> resp :result :capabilities :executeCommandProvider :commands)))
          (is (true? (-> resp :result :capabilities :inlayHintProvider))))
        (let [refresh (read-message rdr)]
          (is (= "workspace/inlayHint/refresh" (:method refresh)) (str "got: " (pr-str refresh)))
          (is (not (contains? refresh :params))))

        ;; 2. initialized (notification)
        (send! (frame {:jsonrpc "2.0" :method "initialized" :params {}}))

        ;; 3. zrepl/eval stub
        (send! (frame {:jsonrpc "2.0" :id 2 :method "workspace/executeCommand"
                       :params {:command "zrepl/eval" :arguments ["(+ 2 2)"]}}))
        (let [resp (next-response-for rdr inbox 2)]
          (is (= {:value "stub"} (:result resp)) (str "got: " (pr-str resp))))

        ;; 4. didOpen no-op, then inlayHint -> []
        (send! (frame {:jsonrpc "2.0" :method "textDocument/didOpen"
                       :params {:textDocument {:uri "file:///t.clj" :languageId "clojure"
                                               :version 1 :text "(+ 2 2)"}}}))
        (send! (frame {:jsonrpc "2.0" :id 3 :method "textDocument/inlayHint"
                       :params {:textDocument {:uri "file:///t.clj"}
                                :range {:start {:line 0 :character 0}
                                        :end {:line 0 :character 7}}}}))
        (let [resp (next-response-for rdr inbox 3)]
          (is (= [] (:result resp)) (str "got: " (pr-str resp))))

        ;; 5. shutdown -> nil; exit -> code 0
        (send! (frame {:jsonrpc "2.0" :id 4 :method "shutdown"}))
        (let [resp (next-response-for rdr inbox 4)]
          (is (nil? (:result resp)) (str "got: " (pr-str resp))))
        (send! (frame {:jsonrpc "2.0" :method "exit"}))

        (let [result (deref proc exit-wait-ms ::timeout)]
          (if (= ::timeout result)
            (do (p/destroy proc)
                (is false "server did not exit after `exit` notification"))
            (do (is (zero? (:exit result))
                    (str "expected exit code 0, got " (:exit result)
                         ";\nstderr:\n" (:err result)))
                (is (not (str/includes? (str (:err result)) "Exception"))
                    (str "server stderr contains an exception:\n" (:err result))))))

        (finally
          (future-cancel watchdog)
          (when (p/alive? proc)
            (p/destroy proc)))))))
