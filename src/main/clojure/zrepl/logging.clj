(ns zrepl.logging
  "Programmatic logback configuration. Log output goes to stderr only —
   stdout is the JSON-RPC protocol stream and must never be written to."
  (:require
   [clojure.string :as str])
  (:import
   (ch.qos.logback.classic
    Level
    Logger)
   (ch.qos.logback.classic.encoder
    PatternLayoutEncoder)
   (ch.qos.logback.core
    ConsoleAppender)
   (org.slf4j
    LoggerFactory)))

(set! *warn-on-reflection* true)

(def ^:private default-pattern
  "%d{HH:mm:ss.SSS} %-5level %logger{24} — %msg%n")

(defn- stderr-appender
  ^ConsoleAppender [^String pattern]
  (let [context (LoggerFactory/getILoggerFactory)
        encoder (doto (PatternLayoutEncoder.)
                  (.setContext context)
                  (.setPattern pattern)
                  (.start))]
    (doto (ConsoleAppender.)
      (.setContext context)
      (.setName "STDERR")
      (.setTarget "System.err")
      (.setEncoder encoder)
      (.start))))

(defn init!
  "Configure logback programmatically (no Joran/XML): a single
   ConsoleAppender writing to stderr. Replaces whatever appenders the root
   logger had, so repeated calls stay idempotent and always apply `opts`.
   `opts`:
   - `:level`   — root level keyword (:debug, :info, :warn, :error), default :info
   - `:pattern` — layout pattern override"
  ([] (init! nil))
  ([{:keys [level pattern] :or {level :info pattern default-pattern}}]
   (let [pattern (if (string? pattern) pattern (name pattern))
         level-name ^String (str/upper-case (name level))
         context ^ch.qos.logback.classic.LoggerContext (LoggerFactory/getILoggerFactory)
         root (.getLogger context Logger/ROOT_LOGGER_NAME)]
     (when (str/blank? pattern)
       (throw (ex-info "logback pattern is blank" {:pattern pattern})))
     (doto root
       (.detachAndStopAllAppenders)
       (.addAppender (stderr-appender pattern))
       ;; dot-form + hinted binding: cloverage turns slash-interop call heads
       ;; into method values (compiled reflectively regardless of hints) and
       ;; loses tags of computed args — a hinted let binding survives both
       (.setLevel (. Level toLevel level-name Level/INFO))))))
