(ns zrepl.server
  "LSP server state and handlers for the S0.2 spike."
  (:require
   [clojure.tools.logging :as log]
   [zrepl.rpc :as rpc]))

(set! *warn-on-reflection* true)

(def ^:private server-version "0.1.0-SNAPSHOT")

(def ^:private state
  (atom {:shutdown-received? false
         :buffers {}}))

(defn shutdown-received?
  "True when the LSP `shutdown` request has been served. `exit` (and stdin
   EOF) exit with 0 in that case, 1 otherwise — per LSP."
  []
  (get @state :shutdown-received?))

(defn buffers
  "Snapshot of the tracked open buffers:
   {uri {:text :version :language-id}}."
  []
  (get @state :buffers))

(defn buffer
  "Tracked buffer for `uri`: {:text :version :language-id}, or nil."
  [uri]
  (get (buffers) uri))

;;;; Request handlers: (fn [ctx params] result)

(defn- initialize
  [_ctx _params]
  (log/info "initialize: advertising full text sync (1), commands [zrepl/eval], inlay hints")
  {:capabilities {:textDocumentSync 1
                  :executeCommandProvider {:commands ["zrepl/eval"]}
                  :inlayHintProvider true}
   :serverInfo {:name "zrepl" :version server-version}})

(defn- execute-command
  [_ctx {:keys [command arguments]}]
  (log/infof "executeCommand %s (%d args)" command (count arguments))
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

(defn- did-open
  [_ctx {:keys [textDocument]}]
  (let [{:keys [uri text version languageId]} textDocument]
    (swap! state assoc-in [:buffers uri]
           {:text text :version version :language-id languageId})))

(defn- did-change
  [_ctx {:keys [textDocument contentChanges]}]
  (let [uri (get textDocument :uri)
        change (peek (vec contentChanges))]
    (cond
      ;; defensive: full sync carries one event per message, but an empty
      ;; batch means nothing to apply — keep the whole entry as-is
      (nil? change)
      nil

      (contains? (buffers) uri)
      (swap! state update-in [:buffers uri]
             assoc :text (:text change) :version (:version textDocument))

      ;; spec: didChange without didOpen never synthesizes a buffer
      :else
      (log/debug "didChange for untracked uri; ignoring:" uri))))

(defn- did-close
  [_ctx {:keys [textDocument]}]
  (swap! state update :buffers dissoc (get textDocument :uri)))

(def ^:private notification-handlers
  {"initialized"                     (constantly nil)
   "workspace/didChangeConfiguration" (constantly nil)
   "textDocument/didOpen"            did-open
   "textDocument/didChange"          did-change
   "textDocument/didClose"           did-close
   "textDocument/didSave"            (constantly nil)
   "exit"                            exit})

;;;; Entry

(defn serve!
  "Runs the LSP loop on `in`/`out` and blocks until the stream closes. The
   JVM is exited with 0 after `shutdown`, 1 otherwise (also on stdin EOF)."
  [^java.io.InputStream in ^java.io.OutputStream out]
  (log/info "zrepl listening on stdio")
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
