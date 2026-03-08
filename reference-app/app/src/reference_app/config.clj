(ns reference-app.config
  (:require [clojure.string :as str]
            [configure.core :as cfg]))

(def allowed-keys
  #{"app.name"
    "http.port"
    "http.expose_correlation_headers"
    "guard.mode"
    "guard.rate_limit"
    "guard.rate_window_sec"
    "guard.login.window_sec"
    "guard.login.max_failures"
    "guard.ban_ttl_sec"
    "accounts.db_path"
    "catalog.db_path"
    "booking.db_path"
    "notify.db_path"
    "audit.db_path"})

(def required-keys
  #{"http.port"
    "accounts.db_path"
    "catalog.db_path"
    "booking.db_path"
    "notify.db_path"
    "audit.db_path"})

(def types
  {"http.port" :int
   "http.expose_correlation_headers" :bool
   "guard.rate_limit" :int
   "guard.rate_window_sec" :int
   "guard.login.window_sec" :int
   "guard.login.max_failures" :int
   "guard.ban_ttl_sec" :int})

(defn load-app-config [logger]
  (cfg/load-config
   {:module-name "reference-app"
    :config "./resources/reference_app.toml"
    :allow-relative? true
    :env-only? false
    :allowed-keys allowed-keys
    :required required-keys
    :types types
    :logger logger}))

(defn- nested-value [m parts]
  (reduce (fn [acc part]
            (when (map? acc)
              (or (get acc part)
                  (get acc (keyword part)))))
          m
          parts))

(defn config-value [config dot-key default]
  (let [parts (str/split dot-key #"\.")]
    (or (get config dot-key)
        (get config (keyword dot-key))
        (nested-value config parts)
        default)))

(defn module-storage-config [config module-id]
  {"storage.mode" "external-managed"
   "storage.backend" "sqlite"
   "storage.allow-self-managed" false
   "storage.sqlite.path" (str (config-value config (str (name module-id) ".db_path") ""))})

(defn module-db-paths [config]
  {:accounts (config-value config "accounts.db_path" "./data/accounts.db")
   :catalog (config-value config "catalog.db_path" "./data/catalog.db")
   :booking (config-value config "booking.db_path" "./data/booking.db")
   :notify (config-value config "notify.db_path" "./data/notify.db")
   :audit (config-value config "audit.db_path" "./data/audit.db")})
