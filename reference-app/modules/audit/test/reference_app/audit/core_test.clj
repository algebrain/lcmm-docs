(ns reference-app.audit.core-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [event-bus :as bus]
            [lcmm.http.core :as http]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.accounts.core :as accounts]
            [reference-app.audit.core :as sut]
            [reference-app.booking.core :as booking]
            [reference-app.catalog.core :as catalog]
            [reference-app.notify.core :as notify]))

(defn- temp-db-path [prefix filename]
  (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory prefix (make-array java.nio.file.attribute.FileAttribute 0)))]
    {:dir tmp-dir
     :path (str (.getAbsolutePath tmp-dir) java.io.File/separator filename)}))

(defn- cleanup-temp! [{:keys [dir]}]
  (when (and dir (.exists ^java.io.File dir))
    (doseq [f (reverse (file-seq dir))]
      (.delete ^java.io.File f))))

(defn- sqlite-resource [path]
  {:backend-type "sqlite"
   :db-spec {:classname "org.sqlite.JDBC"
             :subprotocol "sqlite"
             :subname path}})

(defn- make-schema-registry []
  {:booking/create-requested {"1.0" [:map [:slot-id :string] [:user-id :string]]}
   :booking/created {"1.0" [:map [:booking-id :string] [:slot-id :string] [:user-id :string]]}
   :booking/rejected {"1.0" [:map [:slot-id :string] [:user-id :string] [:reason :string]]}
   :notify/booking-created {"1.0" [:map [:booking-id :string] [:message :string]]}})

(defn- base-system []
  {:bus (bus/make-bus :schema-registry (make-schema-registry) :logger (fn [_ _] nil))
   :router (router/make-router)
   :registry (rpr/make-registry)
   :logger (fn [_ _] nil)})

(defn- request [uri query]
  {:request-method :get
   :uri uri
   :query-params query
   :headers {}})

(deftest audit-captures-booking-and-notify-flow-test
  (testing "audit stores key events with correlation-id and causation-path"
    (let [accounts-tmp (temp-db-path "audit-accounts-" "accounts.db")
          catalog-tmp (temp-db-path "audit-catalog-" "catalog.db")
          booking-tmp (temp-db-path "audit-booking-" "booking.db")
          notify-tmp (temp-db-path "audit-notify-" "notify.db")
          audit-tmp (temp-db-path "audit-audit-" "audit.db")
          {:keys [bus router registry logger]} (base-system)]
      (try
        (accounts/init! {:bus bus
                         :router router
                         :logger logger
                         :read-provider-registry registry
                         :config {"storage.mode" "external-managed"
                                  "storage.backend" "sqlite"
                                  "storage.allow-self-managed" false}
                         :db (sqlite-resource (:path accounts-tmp))})
        (catalog/init! {:bus bus
                        :router router
                        :logger logger
                        :read-provider-registry registry
                        :config {"storage.mode" "external-managed"
                                 "storage.backend" "sqlite"
                                 "storage.allow-self-managed" false}
                        :db (sqlite-resource (:path catalog-tmp))})
        (booking/init! {:bus bus
                        :router router
                        :logger logger
                        :read-provider-registry registry
                        :config {"storage.mode" "external-managed"
                                 "storage.backend" "sqlite"
                                 "storage.allow-self-managed" false}
                        :db (sqlite-resource (:path booking-tmp))})
        (notify/init! {:bus bus
                       :router router
                       :logger logger
                       :config {"storage.mode" "external-managed"
                                "storage.backend" "sqlite"
                                "storage.allow-self-managed" false}
                       :db (sqlite-resource (:path notify-tmp))})
        (sut/init! {:bus bus
                    :router router
                    :logger logger
                    :config {"storage.mode" "external-managed"
                             "storage.backend" "sqlite"
                             "storage.allow-self-managed" false}
                    :db (sqlite-resource (:path audit-tmp))})
        (let [handler (-> (router/as-ring-handler router)
                          (http/wrap-correlation-context {:expose-headers? true}))
              create-response (handler (request "/bookings/actions/create" {"slot-id" "slot-09-00" "user-id" "u-alice"}))]
          (Thread/sleep 300)
          (let [audit-response (handler (request "/audit" {}))
                records (edn/read-string (:body audit-response))
                event-types (set (map :event_type records))]
            (is (= 200 (:status create-response)))
            (is (= 200 (:status audit-response)))
            (is (contains? event-types "booking/create-requested"))
            (is (contains? event-types "booking/created"))
            (is (contains? event-types "notify/booking-created"))
            (is (every? some? (map :correlation_id records)))
            (is (every? some? (map :causation_path records)))))
        (finally
          (cleanup-temp! accounts-tmp)
          (cleanup-temp! catalog-tmp)
          (cleanup-temp! booking-tmp)
          (cleanup-temp! notify-tmp)
          (cleanup-temp! audit-tmp))))))
