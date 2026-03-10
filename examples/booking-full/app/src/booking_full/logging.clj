(ns booking-full.logging
  (:require [clojure.string :as str])
  (:import [java.io Writer]
           [java.time Instant]))

(def ^:private default-redacted-value "***")

(def ^:private sensitive-keys
  #{"password" "token" "secret" "api-key" "authorization" "cookie"})

(defn- sensitive-key? [k]
  (contains? sensitive-keys
             (-> k name str/lower-case)))

(declare redact-value)

(defn- normalize-exception [^Throwable ex]
  {:class (.getName (.getClass ex))
   :message (.getMessage ex)})

(defn redact-value [value]
  (cond
    (instance? Throwable value)
    (normalize-exception value)

    (map? value)
    (into (empty value)
          (map (fn [[k v]]
                 [k (if (sensitive-key? k)
                      default-redacted-value
                      (redact-value v))]))
          value)

    (vector? value)
    (mapv redact-value value)

    (list? value)
    (apply list (map redact-value value))

    (set? value)
    (set (map redact-value value))

    (sequential? value)
    (doall (map redact-value value))

    :else
    value))

(defn normalize-entry
  ([level data]
   (normalize-entry level data {:now-fn #(Instant/now)}))
  ([level data {:keys [now-fn]
                :or {now-fn #(Instant/now)}}]
   (let [payload (if (map? data)
                   data
                   {:message (str data)})]
     (-> payload
         redact-value
         (assoc :level level
                :timestamp (str (now-fn)))))))

(defn- emit-to-writer! [^Writer writer entry]
  (when writer
    (locking writer
      (.write writer (pr-str entry))
      (.write writer "\n")
      (.flush writer))))

(defn make-app-logger
  ([] (make-app-logger {}))
  ([{:keys [sink writer now-fn]
     :or {writer *out*
          now-fn #(Instant/now)}}]
   (fn [level data]
     (let [entry (normalize-entry level data {:now-fn now-fn})]
       (when sink
         (swap! sink conj entry))
       (emit-to-writer! writer entry)
       nil))))

(defn wrap-request-logging [handler logger]
  (fn [request]
    (let [base-log {:component ::http
                    :method (some-> (:request-method request) name)
                    :path (:uri request)
                    :remote-addr (:remote-addr request)
                    :correlation-id (:lcmm/correlation-id request)
                    :request-id (:lcmm/request-id request)}]
      (logger :info (assoc base-log :event :http/request-started))
      (try
        (let [response (handler request)]
          (logger :info (assoc base-log
                               :event :http/request-finished
                               :status (:status response)))
          response)
        (catch Throwable ex
          (logger :error (assoc base-log
                                :event :http/request-failed
                                :exception ex))
          (throw ex))))))
