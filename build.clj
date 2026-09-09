(ns build
  (:require
   [clojure.tools.build.api :as b]
   [deps-deploy.deps-deploy :as dd]))

(defn build-opts
  [opts]
  (let [lib 'io.github.just-sultanov/zrepl
        patch (or (b/git-count-revs nil) 0)
        version (format "0.1.%s" patch)]
    (assoc opts
           :basis (b/create-basis {:project "deps.edn"})
           :class-dir "target/classes"
           :jar-file (format "target/%s-%s.jar" (name lib) version)
           :lib lib
           :src-dirs ["src/main/clojure" "src/main/resources"]
           :target "target"
           :version version
           :pom-data [[:description "ZREPL"]
                      [:url "https://github.com/just-sultanov/zrepl"]
                      [:licenses
                       [:license
                        [:name "Eclipse Public License"]
                        [:url "http://www.eclipse.org/legal/epl-v10.html"]]]
                      [:developers
                       [:developer
                        [:name "Ilshat Sultanov"]
                        [:email "ilshat@sultanov.team"]]]
                      [:scm
                       [:url "https://github.com/just-sultanov/zrepl"]
                       [:connection "scm:git:https://github.com/just-sultanov/zrepl.git"]
                       [:developerConnection "scm:git:ssh:git@github.com:just-sultanov/zrepl.git"]
                       [:tag (str "v" version)]]])))

(defn clean
  [opts]
  (let [{:keys [target] :as opts} (build-opts opts)]
    (println "Cleaning...")
    (b/delete {:path target})
    opts))

(defn build-meta
  [{:keys [lib version class-dir] :as opts}]
  (let [now (java.util.Date.)
        path (format "%s/%s/meta.edn" class-dir (name lib))
        commit (b/git-process {:git-args "rev-parse --short HEAD"})
        branch (b/git-process {:git-args "branch --show-current"})
        metadata {:lib (str lib)
                  :version version
                  :commit (or commit "N/A")
                  :branch (or branch "N/A")
                  :timestamp now}]
    (spit path (pr-str metadata))
    opts))

(defn build
  [opts]
  (let [{:keys [jar-file src-dirs class-dir] :as opts} (build-opts opts)]
    (println "Writing pom...")
    (b/write-pom opts)
    (println "Copying sources...")
    (b/copy-dir {:src-dirs src-dirs, :target-dir class-dir})
    (println "Generating build metadata...")
    (build-meta opts)
    (println "Building" jar-file "...")
    (b/jar opts)
    opts))

(defn install
  [opts]
  (let [opts (build-opts opts)]
    (println "Installing to `~/.m2`...")
    (b/install opts)
    opts))

(defn publish
  [opts]
  (let [{:keys [jar-file] :as opts} (build-opts opts)]
    (dd/deploy
     {:installer :remote,
      :artifact (b/resolve-path jar-file)
      :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))})
    opts))

