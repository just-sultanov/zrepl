(ns zrepl.lsp.schemas
  "Malli schemas for the slice of the LSP protocol zrepl implements.
   Maps are open on purpose: we validate what we consume, extra client
   keys are ignored. No `:fn` schemas — keeps the surface GraalVM-friendly."
  (:require
   [malli.core :as m]))

(set! *warn-on-reflection* true)

(def Position
  [:map
   [:line :int]
   [:character :int]])

(def Range
  [:map
   [:start Position]
   [:end Position]])

(def TextDocumentIdentifier
  [:map
   [:uri :string]])

(def VersionedTextDocumentIdentifier
  [:map
   [:uri :string]
   [:version :int]])

(def TextDocumentItem
  [:map
   [:uri :string]
   [:languageId :string]
   [:version :int]
   [:text :string]])

(def InitializeParams
  [:map
   [:capabilities :map]])

(def InitializeResult
  [:map
   [:capabilities
    [:map
     [:textDocumentSync :int]
     [:executeCommandProvider [:map [:commands [:sequential :string]]]]
     [:inlayHintProvider :boolean]]]
   [:serverInfo
    [:map
     [:name :string]
     [:version {:optional true} :string]]]])

(def InitializedParams
  [:map])

(def DidOpenTextDocumentParams
  [:map
   [:textDocument TextDocumentItem]])

;; full-text sync: change events carry the whole document text, no range
(def TextDocumentChangeEvent
  [:map
   [:text :string]])

(def DidChangeTextDocumentParams
  [:map
   [:textDocument VersionedTextDocumentIdentifier]
   [:contentChanges [:sequential TextDocumentChangeEvent]]])

(def DidCloseTextDocumentParams
  [:map
   [:textDocument TextDocumentIdentifier]])

(def ExecuteCommandParams
  [:map
   [:command :string]
   [:arguments {:optional true} :any]])

(def InlayHint
  [:map
   [:position Position]
   [:label [:or :string [:sequential :map]]]])

(def InlayHintParams
  [:map
   [:textDocument TextDocumentIdentifier]
   [:range Range]])

(def PublishDiagnosticsParams
  [:map
   [:uri :string]
   [:diagnostics [:sequential :any]]
   [:version {:optional true} :int]])

(def ShowMessageParams
  [:map
   [:type :int]
   [:message :string]])

(def registry
  "Method name (JSON-RPC string) -> schema map with optional :params/:result."
  {"initialize"                      {:params InitializeParams
                                      :result InitializeResult}
   "initialized"                     {:params InitializedParams}
   "textDocument/didOpen"            {:params DidOpenTextDocumentParams}
   "textDocument/didChange"          {:params DidChangeTextDocumentParams}
   "textDocument/didClose"           {:params DidCloseTextDocumentParams}
   "textDocument/inlayHint"          {:params InlayHintParams
                                      :result [:sequential InlayHint]}
   "workspace/executeCommand"        {:params ExecuteCommandParams}
   "workspace/inlayHint/refresh"     {}
   "textDocument/publishDiagnostics" {:params PublishDiagnosticsParams}
   "window/showMessage"              {:params ShowMessageParams}})

(defn valid?
  "True when `value` validates against the schema registered under
   [method kind]. Unknown methods (or missing schemas) are always valid."
  [method kind value]
  (if-let [schema (get-in registry [method kind])]
    (m/validate schema value)
    true))
