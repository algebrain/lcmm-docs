#!/usr/bin/env bb
(ns test
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def green "\u001b[1;32m")
(def cyan "\u001b[1;36m")
(def reset "\u001b[0m")

(defn- started-at []
  (let [t (java.time.LocalTime/now)
        s (.format t (java.time.format.DateTimeFormatter/ofPattern "HH:mm"))]
    (println (str green "STARTED AT " s reset))))

(defn- beep! []
  (try
    (when (.startsWith (System/getProperty "os.name" "") "Windows")
      @(p/process ["powershell"
                   "-Command"
                   "[console]::Beep(800,200); [console]::Beep(1000,200); [console]::Beep(1200,200)"]
                  {:inherit true}))
    (catch Throwable _
      nil)))

(defn- script-dir []
  (.getParentFile (io/file *file*)))

(defn- normalize-path [^java.io.File file]
  (-> (.toPath (script-dir))
      (.relativize (.toPath file))
      str
      (str/replace "\\" "/")))

(defn- child-test-files []
  (let [root-file (.getCanonicalFile (io/file *file*))]
    (->> (file-seq (script-dir))
         (filter #(.isFile ^java.io.File %))
         (filter #(= "test.bb" (.getName ^java.io.File %)))
         (remove #(= root-file (.getCanonicalFile ^java.io.File %)))
         (sort-by (fn [file]
                    [(if (str/starts-with? (normalize-path file) "modules/") 0 1)
                     (normalize-path file)])))))

(defn- run-test! [^java.io.File file args]
  (let [dir (.getParentFile file)
        rel-path (normalize-path file)
        command (vec (concat ["bb" "test.bb"] args))]
    (println (str cyan "RUN " rel-path reset))
    @(p/check
      (p/process command
                 {:dir dir
                  :inherit true}))))

(defn -main [& args]
  (let [tests (child-test-files)]
    (beep!)
    (started-at)
    (doseq [file tests]
      (run-test! file args))))

(apply -main *command-line-args*)
