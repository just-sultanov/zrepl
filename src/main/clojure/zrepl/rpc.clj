(ns zrepl.rpc
  "Own JSON-RPC 2.0 over stdio with LSP framing — no lsp4clj.

   Context map (created by `start!`):
   - `:in` / `:out`            — protocol streams (stdout is sacred)
   - `:lock`                   — monitor for outgoing writes
   - `:next-id`                — atom, ids for server->client requests
   - `:pending-responses`      — atom {id -> promise}, server->client requests
   - `:executor`               — java.util.concurrent.Executor for request handlers
   - `:request-handlers`       — {method (fn [ctx params] result)}
   - `:notification-handlers`  — {method (fn [ctx params] _)}
   - `:after-response`         — {method (fn [ctx] _)}, runs after the response is written
   - `:state`                  — server state atom, passed through to handlers
   - `:on-close`               — (fn [ctx] _), runs once when the reader loop ends"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [jsonista.core :as jsonista]
   [malli.error :as me]
   [malli.core :as m]
   [zrepl.lsp.schemas :as schemas])
  (:import
   (java.io
    BufferedInputStream
    ByteArrayOutputStream
    InputStream
    OutputStream
    PrintStream)
   (java.nio.charset
    StandardCharsets)
   (java.util.concurrent
    ExecutorService
    Executors)))

(set! *warn-on-reflection* true)

;;;; Framing

(defn frame-bytes
  "Returns the LSP-framed bytes (headers + body) for `msg`."
  [msg]
  (let [body ^bytes (jsonista/write-value-as-bytes msg)
        header-str ^String (format "Content-Length: %d\r\n\r\n" (alength body))
        header (.getBytes header-str StandardCharsets/UTF_8)
        out (ByteArrayOutputStream. (+ (alength header) (alength body)))]
    (.write out header)
    (.write out body)
    (.toByteArray out)))

(defn write-message!
  "Serializes `msg` as an LSP frame and writes it to `(:out ctx)` under the
   write lock. Blocks until flushed."
  [{:keys [^Object lock ^OutputStream out]} msg]
  (locking lock
    (.write out ^bytes (frame-bytes msg))
    (.flush out)))

(defn- read-line!
  "Reads one header line terminated by \\n, tolerating \\r\\n. Returns nil at
   EOF. Header lines are ASCII, so byte-to-char casting is safe here."
  [^InputStream in]
  (loop [^StringBuilder sb (StringBuilder.)]
    (let [b (.read in)]
      (cond
        (neg? b)
        (when (pos? (.length sb)) (str sb))

        (= 13 b) ;; \r — consume an optional following \n
        (let [next-b (.read in)]
          (cond
            (= 10 next-b) (str sb)
            (neg? next-b) (str sb)
            :else (recur (doto sb (.append \return) (.append (char next-b))))))

        (= 10 b) (str sb)

        :else (recur (doto sb (.append (char b))))))))

(defn- parse-headers!
  "Reads header lines until an empty line; returns a header map with
   lower-cased names. Throws ::malformed-header on a line without a colon."
  [^InputStream in]
  (loop [headers {}]
    (if-let [line (read-line! in)]
      (if (str/blank? line)
        headers
        (if-let [[_ name value] (re-matches #"([A-Za-z-]+):[ \t]*(.*)" line)]
          (recur (assoc headers (str/lower-case name) (str/trim value)))
          (throw (ex-info (str "Malformed header line: " (pr-str line))
                          {:kind ::malformed-header}))))
      headers)))

(defn read-message!
  "Reads a single LSP-framed JSON-RPC message from `in`. Returns the parsed
   message (keyword keys), or nil on a clean EOF. Throws ex-info with
   `:kind ::malformed-header` on broken framing."
  [^InputStream in]
  (let [headers (parse-headers! in)]
    (if (empty? headers)
      nil
      (let [length (some-> (get headers "content-length") parse-long)]
        (when-not (and length (pos? length))
          (throw (ex-info (str "Missing or invalid Content-Length: " (pr-str headers))
                          {:kind ::malformed-header})))
        (let [body (.readNBytes in (int length))]
          (when-not (= (alength body) (int length))
            (throw (ex-info (format "Truncated body: expected %d bytes, got %d"
                                    length (alength body))
                            {:kind ::malformed-header})))
          (jsonista/read-value body jsonista/keyword-keys-object-mapper))))))

;;;; Classification and dispatch

(defn classify
  "JSON-RPC 2.0 message kind: ::request, ::notification, ::response, ::invalid."
  [msg]
  (cond
    (not (map? msg)) ::invalid
    (:method msg) (if (contains? msg :id) ::request ::notification)
    (contains? msg :id) ::response
    :else ::invalid))

(defn- respond!
  [ctx id result]
  (write-message! ctx {:jsonrpc "2.0" :id id :result result}))

(defn- respond-error!
  [ctx id code message]
  (write-message! ctx {:jsonrpc "2.0" :id id :error {:code code :message message}}))

(defn- explain-error
  [schema value]
  (when-not (m/validate schema value)
    (me/humanize (m/explain schema value))))

(defn- validate-params
  [method params]
  (when-let [schema (get-in schemas/registry [method :params])]
    (explain-error schema (or params {}))))

(defn- validate-result
  [method result]
  (when-let [schema (get-in schemas/registry [method :result])]
    (explain-error schema result)))

(defn- handle-request!
  "Runs on the processing thread. Responds with the handler result, or a
   JSON-RPC error: -32601 unknown method, -32602 invalid params, -32603
   handler failure (honoring :rpc/code on ex-info)."
  [ctx {:keys [id method params]}]
  (if-let [handler (get (:request-handlers ctx) method)]
    (if-let [error (validate-params method params)]
      (respond-error! ctx id -32602 (str "Invalid params: " error))
      (try
        (let [result (handler ctx params)]
          (if-let [error (validate-result method result)]
            (do (log/errorf "handler %s returned an invalid result: %s"
                            method (pr-str error))
                (respond-error! ctx id -32603
                                (str "Internal error: invalid result for " method)))
            (do (respond! ctx id result)
                (when-some [after (get (:after-response ctx) method)]
                  (after ctx)))))
        (catch Exception e
          (let [code (or (:rpc/code (ex-data e)) -32603)]
            (log/error e "request handler failed:" method)
            (try
              (respond-error! ctx id code (or (.getMessage e) "Internal error"))
              (catch Exception e'
                (log/error e' "failed to write error response for" method)))))))
    (respond-error! ctx id -32601 (str "Method not found: " method))))

(defn- handle-notification!
  [ctx {:keys [method params]}]
  (if-let [handler (get (:notification-handlers ctx) method)]
    (try
      (handler ctx params)
      (catch Exception e
        (log/error e "notification handler failed:" method)))
    ;; LSP: methods starting with $/ MUST be ignored if not understood —
    ;; they are protocol-internal ($/setTrace, $/cancelRequest, ...).
    (if (str/starts-with? method "$/")
      (log/debug "ignoring $/ notification:" method)
      (log/warn "ignoring unknown notification:" method))))

(defn- handle-response!
  "Client's response to one of our server->client requests."
  [ctx {:keys [id] :as msg}]
  (if-let [promise (get @(:pending-responses ctx) id)]
    (do (swap! (:pending-responses ctx) dissoc id)
        (deliver promise msg))
    (log/warn "ignoring response for unknown request id:" (pr-str id))))

(defn handle-message!
  "Classifies `msg` and dispatches it. Called on the processing thread —
   message order is preserved (LSP servers process messages in order; a
   slow handler delays subsequent messages, which is correct for `exit`
   semantics)."
  [ctx msg]
  (case (classify msg)
    ::request (handle-request! ctx msg)
    ::notification (handle-notification! ctx msg)
    ::response (handle-response! ctx msg)
    (log/warn "dropping invalid JSON-RPC message:" (pr-str msg))))

;;;; Server -> client

(defn send-notification!
  "Sends a server->client notification. (Part of the protocol surface from
   day one; first used by outbound diagnostics in S0.4.)"
  [ctx method params]
  (write-message! ctx (cond-> {:jsonrpc "2.0" :method method}
                        (some? params) (assoc :params params))))

(defn send-request!
  "Sends a server->client request. Returns a promise delivered with the
   client's raw response message — or never delivered if the client ignores
   it (deref with a timeout)."
  [ctx method params]
  (let [id (swap! (:next-id ctx) inc)
        promise (promise)]
    (swap! (:pending-responses ctx) assoc id promise)
    (write-message! ctx (cond-> {:jsonrpc "2.0" :id id :method method}
                          (some? params) (assoc :params params)))
    promise))

;;;; Loop

(defn- reader-loop!
  "Reads messages from the input stream and processes each one to
   completion (FIFO, single virtual thread) — a notification or request is
   never overtaken by a later message such as `exit`. On EOF or framing
   failure, stops and queues `:on-close` behind everything already read."
  [ctx]
  (try
    (loop []
      (when-let [msg (read-message! (:in ctx))]
        (handle-message! ctx msg)
        (recur)))
    (log/info "input stream closed; reader loop finished")
    (catch Exception e
      (log/error e "reader loop failed"))
    (finally
      (when-some [on-close (:on-close ctx)]
        (try
          (.submit ^ExecutorService (:executor ctx)
                   ^Runnable
                   (fn []
                     (try
                       (on-close ctx)
                       (catch Exception e
                         (log/error e "on-close failed")))))
          (catch Exception e
            (log/error e "failed to queue on-close")))))))

(defn start!
  "Starts the reader loop on a virtual thread. Returns the rpc context with
   the reader thread under `:reader-thread`.
   `:executor` — the FIFO processing executor; must be single-threaded to
   preserve message order. Defaults to a one-thread pool over virtual
   threads. Override with a synchronous executor in tests."
  [^InputStream in ^OutputStream out
   {:keys [request-handlers notification-handlers after-response state
           on-close executor]}]
  (let [ctx {:in (BufferedInputStream. in)
             :out out
             :lock (Object.)
             :next-id (atom 0)
             :pending-responses (atom {})
             :executor (or executor
                           (Executors/newSingleThreadExecutor
                            (.factory ^java.lang.Thread$Builder (Thread/ofVirtual))))
             :request-handlers (or request-handlers {})
             :notification-handlers (or notification-handlers {})
             :after-response (or after-response {})
             :state state
             :on-close on-close}
        builder ^java.lang.Thread$Builder (Thread/ofVirtual)
        reader-thread ^Thread (-> (.name builder "zrepl-rpc-reader")
                                  (.unstarted (fn [] (reader-loop! ctx))))]
    (.start reader-thread)
    (assoc ctx :reader-thread reader-thread)))

;;;; Stdout guard

(defn discard-stdout!
  "Redirects System/out to a PrintStream that forwards everything to stderr,
   and rebinds the root of `*out*` to the same guard (Clojure's `println`
   holds the original stdout in the var's root binding, so a bare setOut is
   not enough). Protects the protocol stream from accidental prints (like
   clojure-lsp's discarding-stdout). Call it once, after capturing the real
   stdout."
  []
  (System/setOut
   (PrintStream.
    (proxy [ByteArrayOutputStream] []
      (write
        ([^bytes b]
         (binding [*out* *err*]
           (println "[zrepl] discarded stdout:" (String. b StandardCharsets/UTF_8))))
        ([^bytes b ^long off ^long len]
         (binding [*out* *err*]
           (println "[zrepl] discarded stdout:" (String. b off len StandardCharsets/UTF_8))))))
    true))
  (alter-var-root #'*out* (constantly (java.io.PrintWriter. System/out true))))
