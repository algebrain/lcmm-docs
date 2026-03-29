(ns lcmm-docs.tools.test-runner
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.util.concurrent TimeUnit]))

(def green "\u001b[1;32m")
(def yellow "\u001b[1;33m")
(def reset "\u001b[0m")

(def ^:private min-clojure-version [1 11 0])

(defn started-at []
  (let [t (java.time.LocalTime/now)
        s (.format t (java.time.format.DateTimeFormatter/ofPattern "HH:mm"))]
    (println (str green "STARTED AT " s reset))))

(defn banner [text]
  (println (str green text reset)))

(defn warn [text]
  (println (str yellow text reset)))

(defn- parse-long [s]
  (try
    (Long/parseLong s)
    (catch Throwable _ nil)))

(defn parse-timeout-ms [args]
  (let [timeout-ms-arg (some (fn [arg]
                               (when (str/starts-with? arg "--timeout-ms=")
                                 (subs arg (count "--timeout-ms="))))
                             args)
        timeout-min-arg (some (fn [arg]
                                (when (str/starts-with? arg "--timeout-min=")
                                  (subs arg (count "--timeout-min="))))
                              args)]
    (cond
      timeout-ms-arg (parse-long timeout-ms-arg)
      timeout-min-arg (some-> (parse-long timeout-min-arg) (* 60 1000))
      :else (* 5 60 1000))))

(defn- version-parts [s]
  (->> (re-seq #"\d+" (or s ""))
       (mapv parse-long)))

(defn- version>=? [actual expected]
  (loop [a actual
         e expected]
    (let [av (or (first a) 0)
          ev (or (first e) 0)]
      (cond
        (> av ev) true
        (< av ev) false
        (and (empty? a) (empty? e)) true
        :else (recur (next a) (next e))))))

(defn- destroy-tree! [^Process p]
  (let [^java.lang.ProcessHandle ph (.toHandle p)
        consumer (reify java.util.function.Consumer
                   (accept [_ ^java.lang.ProcessHandle h]
                     (.destroyForcibly h)))]
    (.forEach (.descendants ph) consumer)
    (.destroyForcibly p)))

(defn- run-command! [cmd timeout-ms]
  (let [proc (p/process cmd {:inherit true})
        ^Process process (:proc proc)
        finished? (.waitFor process timeout-ms TimeUnit/MILLISECONDS)]
    (if finished?
      (let [exit (.exitValue process)]
        (when (not= 0 exit)
          (System/exit exit)))
      (do
        (warn (str "TIMEOUT after " timeout-ms " ms: " (str/join " " cmd)))
        (destroy-tree! process)
        (.waitFor process 5000 TimeUnit/MILLISECONDS)
        (when (.isAlive process)
          (warn "Process still alive after forced destroy"))
        (System/exit 1)))))

(defn- deps-edn []
  (-> "deps.edn" io/file slurp edn/read-string))

(defn- has-alias? [alias]
  (contains? (get (deps-edn) :aliases {}) alias))

(defn- clojure-version-output []
  (let [result (p/shell {:out :string
                         :err :string
                         :continue true}
                        "clojure -Sdescribe")]
    (when-not (zero? (:exit result))
      (binding [*out* *err*]
        (print (:err result)))
      (System/exit (:exit result)))
    (:out result)))

(defn- ensure-clojure! []
  (let [output (clojure-version-output)
        version (some->> output
                         (re-find #":version \"([^\"]+)\"")
                         second)
        parts (version-parts version)]
    (when-not (seq parts)
      (warn "Unable to determine Clojure CLI version from clojure -Sdescribe")
      (System/exit 1))
    (when-not (version>=? parts min-clojure-version)
      (warn (str "Clojure CLI " version " is too old. Need "
                 (str/join "." min-clojure-version) " or newer."))
      (System/exit 1))))

(defn- run-step! [label alias extra-args timeout-ms]
  (banner label)
  (when-not (has-alias? alias)
    (warn (str "Missing alias " alias " in deps.edn"))
    (System/exit 1))
  (run-command! (vec (concat ["clojure" "-J--enable-native-access=ALL-UNNAMED"
                              (str "-M" alias)]
                             extra-args))
                timeout-ms))

(defn run! [args]
  (let [timeout-ms (or (parse-timeout-ms args)
                       (* 5 60 1000))]
    (ensure-clojure!)
    (started-at)
    (run-step! "LINT" :lint [] timeout-ms)
    (run-step! "TESTS" :test ["--reporter" "kaocha.report/documentation"] timeout-ms)
    (run-step! "FORMAT" :format [] timeout-ms)))
