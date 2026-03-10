#!/usr/bin/env bb
(ns start
  (:require [babashka.process :as p]
            [clojure.java.io :as io]))

(def reset-color "\u001b[0m")
(def title-color "\u001b[1;36m")
(def info-color "\u001b[1;32m")
(def warn-color "\u001b[1;33m")

(defn- now-hh-mm []
  (.format (java.time.LocalTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "HH:mm")))

(defn- script-dir []
  (.getParentFile (io/file *file*)))

(defn- usage []
  (str "Usage: bb start.bb [--help]"))

(defn- banner []
  (println (str title-color "BOOKING-SIMPLE START" reset-color))
  (println (str info-color "STARTED AT " (now-hh-mm) reset-color))
  (println "App: booking-simple")
  (println "Open in browser: http://localhost:3006")
  (println "Config: ./resources/booking_config.toml"))

(defn- destroy-tree! [^Process process]
  (when process
    (let [handle (.toHandle process)
          consumer (reify java.util.function.Consumer
                     (accept [_ ^java.lang.ProcessHandle h]
                       (.destroy h)))]
      (.forEach (.descendants handle) consumer)
      (.destroy process))))

(defn -main [& args]
  (if (= ["--help"] (vec args))
    (println (usage))
    (let [dir (.getAbsolutePath (script-dir))
          child (atom nil)]
      (banner)
      (println (str warn-color "Press Ctrl+C to stop the server cleanly." reset-color))
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread.
        (fn []
          (when-let [proc @child]
            (destroy-tree! proc)))))
      (let [proc (p/process ["clj" "-M:run-main"] {:dir dir
                                                   :inherit true})]
        (reset! child (:proc proc))
        @(p/check proc)))))

(apply -main *command-line-args*)
