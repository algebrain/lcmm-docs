(ns reference-app.system
  (:require [event-bus :as bus]
            [lcmm.http.core :as http]
            [lcmm.observe :as obs]
            [lcmm.observe.http :as observe.http]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.accounts.core :as accounts]
            [reference-app.audit.core :as audit]
            [reference-app.booking.core :as booking]
            [reference-app.catalog.core :as catalog]
            [reference-app.config :as config]
            [reference-app.logging :as logging]
            [reference-app.notify.core :as notify]
            [reference-app.schema-registry :as schema-registry]
            [reference-app.security :as security]
            [reference-app.storage :as storage]))

(defn- make-checks [registry]
  [{:name :read-providers
    :critical? true
    :check (fn []
             (try
               (rpr/assert-requirements! registry)
               {:ok? true}
               (catch Exception ex
                 {:ok? false
                  :reason :registry-check-failed
                  :diagnostic {:message (.getMessage ex)}})))}])

(defn make-system
  ([] (make-system nil))
  ([override-config]
   (let [logger (logging/make-app-logger)
         {:keys [config meta]} (config/load-app-config logger)
         app-config (merge config override-config)
         event-bus (bus/make-bus
                     :schema-registry (schema-registry/make-schema-registry)
                     :logger logger
                     :log-payload :none)
         app-router (router/make-router)
         registry (rpr/make-registry)
         guard-instance (security/make-guard app-config)
         observe-registry (obs/make-registry
                            :strict-labels? false
                            :max-series-per-metric 5000
                            :on-series-limit :drop-and-log
                            :on-invalid-number :drop-and-log
                            :render-cache-ttl-ms 1000
                            :storage-mode :single-atom)
         metrics-handler (obs/metrics-handler observe-registry)
         accounts-deps {:bus event-bus
                        :router app-router
                        :logger logger
                        :read-provider-registry registry
                        :config (config/module-storage-config app-config :accounts)
                        :db (storage/accounts-db-resource app-config)}
         catalog-deps {:bus event-bus
                       :router app-router
                       :logger logger
                       :read-provider-registry registry
                       :config (config/module-storage-config app-config :catalog)
                       :db (storage/catalog-db-resource app-config)}
         booking-deps {:bus event-bus
                       :router app-router
                       :logger logger
                       :read-provider-registry registry
                       :config (config/module-storage-config app-config :booking)
                       :db (storage/booking-db-resource app-config)}
         notify-deps {:bus event-bus
                      :router app-router
                      :logger logger
                      :config (config/module-storage-config app-config :notify)
                      :db (storage/notify-db-resource app-config)}
         audit-deps {:bus event-bus
                     :router app-router
                     :logger logger
                     :config (config/module-storage-config app-config :audit)
                     :db (storage/audit-db-resource app-config)}
         _ (accounts/init! accounts-deps)
         _ (catalog/init! catalog-deps)
         _ (booking/init! booking-deps)
         _ (notify/init! notify-deps)
         _ (audit/init! audit-deps)
         _ (rpr/assert-requirements! registry)
         checks (make-checks registry)
         _ (security/install-security-routes! app-router registry guard-instance logger)
         _ (router/add-route! app-router :get "/healthz" (http/health-handler {}) {:name ::healthz})
         _ (router/add-route! app-router :get "/readyz" (http/ready-handler {:checks checks}) {:name ::readyz})
         _ (router/add-route! app-router :get "/metrics" metrics-handler {:name ::metrics})
         raw-handler (router/as-ring-handler app-router)
         observed-handler (observe.http/wrap-observe-http
                           raw-handler
                           {:registry observe-registry
                            :module :reference-app
                            :route-fn (fn [req]
                                        (or (get-in req [:reitit.core/match :template])
                                            "unknown"))})
         app-handler (-> observed-handler
                         (security/wrap-guard guard-instance logger)
                         (http/wrap-correlation-context {:expose-headers? (boolean (config/config-value app-config "http.expose_correlation_headers" true))})
                         (http/wrap-error-contract {}))]
     (logger :info {:component ::system
                    :event :config-loaded
                    :source (:source meta)
                    :file (:file meta)
                    :env-keys (:env-keys meta)})
     {:config app-config
      :logger logger
      :bus event-bus
      :router app-router
      :guard guard-instance
      :read-provider-registry registry
      :observe-registry observe-registry
      :handler app-handler})))
