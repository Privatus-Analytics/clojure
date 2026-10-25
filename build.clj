(ns build
  "Builds the jar and deploys it to Clojars.

    clojure -T:build jar
    clojure -T:build deploy   ; needs CLOJARS_USERNAME and CLOJARS_PASSWORD"
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'com.privatusanalytics/privatus-analytics)
(def version "1.0.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn- pom-data []
  [[:description "Adds the cookieless Privatus Analytics tracking script to Clojure web apps."]
   [:url "https://privatusanalytics.com"]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]
     [:distribution "repo"]]]
   [:developers
    [:developer
     [:name "Two Phase LLC"]
     [:url "https://privatusanalytics.com"]]]])

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :scm {:url "https://github.com/Privatus-Analytics/clojure"
                        :connection "scm:git:https://github.com/Privatus-Analytics/clojure.git"
                        :developerConnection "scm:git:ssh://git@github.com/Privatus-Analytics/clojure.git"
                        :tag (str "v" version)}
                  :pom-data (pom-data)})
    (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
    (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
    (b/jar {:class-dir class-dir :jar-file jar-file})
    (println "Built" jar-file)))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
