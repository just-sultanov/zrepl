(ns zrepl.main
  "Entry point: `clojure -M -m zrepl.main`. Configures logging (stderr),
   guards stdout, runs the LSP loop over stdio."
  (:require
   [zrepl.logging :as logging]
   [zrepl.rpc :as rpc]
   [zrepl.server :as server])
  (:gen-class))

(defn -main
  [& _args]
  (logging/init!)
  (let [stdout System/out]
    (rpc/discard-stdout!)
    (server/serve! System/in stdout))
  (System/exit (if (server/shutdown-received?) 0 1)))
