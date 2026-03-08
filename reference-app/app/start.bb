#!/usr/bin/env bb
(ns start
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.java.io :as io]))

(def reset-color "\u001b[0m")
(def title-color "\u001b[1;36m")
(def info-color "\u001b[1;32m")
(def warn-color "\u001b[1;33m")

(defn- now-hh-mm []
  (.format (java.time.LocalTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "HH:mm")))

(defn- usage []
  (str/join
   \newline
   ["Usage: bb start.bb [--reset|--continue] [--port=<N>] [--help]"
    ""
    "Modes:"
    "  --reset      Start from a clean demo state (default)"
    "  --continue   Keep existing local SQLite data"
    ""
    "Options:"
    "  --port=<N>   Override HTTP port"
    "  --help       Show this help"]))

(defn- parse-port [value]
  (try
    (Long/parseLong value)
    (catch Throwable _
      nil)))

(defn- parse-args [args]
  (reduce (fn [{:keys [mode port] :as acc} arg]
            (cond
              (= arg "--reset")
              (assoc acc :mode :reset)

              (= arg "--continue")
              (assoc acc :mode :continue)

              (str/starts-with? arg "--port=")
              (if-let [parsed (parse-port (subs arg (count "--port=")))]
                (assoc acc :port parsed)
                (throw (ex-info "Invalid port value"
                                {:reason :invalid-port
                                 :arg arg})))

              (= arg "--help")
              (assoc acc :help? true)

              :else
              (throw (ex-info "Unknown argument"
                              {:reason :unknown-arg
                               :arg arg}))))
          {:mode :reset
           :port nil
           :help? false}
          args))

(defn- script-dir []
  (.getParentFile (io/file *file*)))

(defn- db-paths []
  ["./data/accounts.db"
   "./data/catalog.db"
   "./data/booking.db"
   "./data/notify.db"
   "./data/audit.db"])

(defn- banner [mode port]
  (println (str title-color "REFERENCE-APP START" reset-color))
  (println (str info-color "STARTED AT " (now-hh-mm) reset-color))
  (println (str "Mode: " (name mode)))
  (println (str "Port: " (or port 3006)))
  (println "SQLite files:")
  (doseq [path (db-paths)]
    (println (str "  - " path)))
  (println (str "State: "
                (if (= mode :reset)
                  "local demo state will be reset before startup"
                  "existing local demo state will be reused"))))

(defn- destroy-tree! [^Process process]
  (when process
    (let [handle (.toHandle process)
          consumer (reify java.util.function.Consumer
                     (accept [_ ^java.lang.ProcessHandle h]
                       (.destroy h)))]
      (.forEach (.descendants handle) consumer)
      (.destroy process))))

(defn -main [& args]
  (let [{:keys [mode port help?]} (parse-args args)]
    (if help?
      (println (usage))
      (let [dir (.getAbsolutePath (script-dir))
            command (vec (concat ["clj" "-M:run-main" "--" (str "--" (name mode))]
                                 (when port [(str "--port=" port)])))
            child (atom nil)]
        (banner mode port)
        (println (str warn-color "Press Ctrl+C to stop the server cleanly." reset-color))
        (.addShutdownHook
         (Runtime/getRuntime)
         (Thread.
          (fn []
            (when-let [proc @child]
              (destroy-tree! proc)))))
        (let [proc (p/process command {:dir dir
                                       :inherit true})]
          (reset! child (:proc proc))
          @(p/check proc))))))

(apply -main *command-line-args*)
