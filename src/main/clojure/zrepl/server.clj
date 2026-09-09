(ns zrepl.server
  "LSP server state and handlers for the S0.2 spike."
  (:require
   [clojure.tools.logging :as log]
   [zrepl.rpc :as rpc]))

(set! *warn-on-reflection* true)

(def ^:private server-version "0.1.0-SNAPSHOT")

(def ^:private state
  (atom {:shutdown-received? false}))

(defn shutdown-received?
  "True when the LSP `shutdown` request has been served. `exit` (and stdin
   EOF) exit with 0 in that case, 1 otherwise — per LSP."
  []
  (get @state :shutdown-received?))

;;;; Request handlers: (fn [ctx params] result)

(defn- initialize
  [_ctx _params]
  {:capabilities {:textDocumentSync 1
                  :executeCommandProvider {:commands ["zrepl/eval"]}
                  :inlayHintProvider true}
   :serverInfo {:name "zrepl" :version server-version}})

(defn- execute-command
  [_ctx {:keys [command arguments]}]
  (if-let [command-handler (get {"zrepl/eval" (constantly {:value "stub"})} command)]
    (command-handler arguments)
    (throw (ex-info (str "Command not found: " command) {:rpc/code -32601}))))

(defn- inlay-hint
  [_ctx _params]
  [])

(defn- shutdown
  [_ctx _params]
  (swap! state assoc :shutdown-received? true)
  nil)

(def ^:private request-handlers
  {"initialize"               initialize
   "workspace/executeCommand" execute-command
   "textDocument/inlayHint"   inlay-hint
   "shutdown"                 shutdown})

;;;; Server -> client follow-ups: (fn [ctx] _), run after the response is written

(def ^:private after-response
  {"initialize" (fn [ctx]
                  ;; proves the bidirectional channel: we never deref this
                  (rpc/send-request! ctx "workspace/inlayHint/refresh" nil))})

;;;; Notification handlers: (fn [ctx params] _)

(defn- exit
  [_ctx _params]
  (log/info "exit notification; shutdown-received?" (shutdown-received?))
  (System/exit (if (shutdown-received?) 0 1)))

(def ^:private notification-handlers
  {"initialized"           (constantly nil)
   "textDocument/didOpen"  (constantly nil)
   "textDocument/didChange" (constantly nil)
   "exit"                  exit})

;;;; Entry

(defn serve!
  "Runs the LSP loop on `in`/`out` and blocks until the stream closes. The
   JVM is exited with 0 after `shutdown`, 1 otherwise (also on stdin EOF)."
  [^java.io.InputStream in ^java.io.OutputStream out]
  (let [ctx (rpc/start! in out
                        {:request-handlers request-handlers
                         :notification-handlers notification-handlers
                         :after-response after-response
                         :state state
                         :on-close (fn [_ctx]
                                     (let [code (if (shutdown-received?) 0 1)]
                                       (log/info "zrepl exiting with code" code)
                                       (System/exit code)))})]
    (.join ^Thread (:reader-thread ctx))))
