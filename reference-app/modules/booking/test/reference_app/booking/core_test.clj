(ns reference-app.booking.core-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [event-bus :as bus]
            [lcmm.http.core :as http]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.accounts.core :as accounts]
            [reference-app.booking.core :as sut]
            [reference-app.catalog.core :as catalog]))

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
   :booking/rejected {"1.0" [:map [:slot-id :string] [:user-id :string] [:reason :string]]}})

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

(deftest booking-create-uses-providers-and-publishes-events-test
  (testing "booking creates records through provider-based sync-read"
    (let [accounts-tmp (temp-db-path "booking-accounts-" "accounts.db")
          catalog-tmp (temp-db-path "booking-catalog-" "catalog.db")
          booking-tmp (temp-db-path "booking-bookings-" "bookings.db")
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
        (sut/init! {:bus bus
                    :router router
                    :logger logger
                    :read-provider-registry registry
                    :config {"storage.mode" "external-managed"
                             "storage.backend" "sqlite"
                             "storage.allow-self-managed" false}
                    :db (sqlite-resource (:path booking-tmp))})
        (let [handler (-> (router/as-ring-handler router)
                          (http/wrap-correlation-context {:expose-headers? true}))
              create-response (handler (request "/bookings/actions/create" {"slot-id" "slot-09-00" "user-id" "u-alice"}))
              booking (edn/read-string (:body create-response))
              get-response (handler {:request-method :get
                                     :uri (str "/bookings/" (:id booking))
                                     :path-params {:booking_id (:id booking)}
                                     :headers {}})
              list-response (handler (request "/bookings" {"user-id" "u-alice"}))]
          (is (= 200 (:status create-response)))
          (is (= "created" (:status booking)))
          (is (= 200 (:status get-response)))
          (is (= 1 (count (edn/read-string (:body list-response)))))
          (is (contains? (:headers create-response) "x-correlation-id")))
        (finally
          (cleanup-temp! accounts-tmp)
          (cleanup-temp! catalog-tmp)
          (cleanup-temp! booking-tmp))))))

(deftest booking-rejects-closed-slot-test
  (testing "booking rejects slots that are not open"
    (let [accounts-tmp (temp-db-path "booking2-accounts-" "accounts.db")
          catalog-tmp (temp-db-path "booking2-catalog-" "catalog.db")
          booking-tmp (temp-db-path "booking2-bookings-" "bookings.db")
          {:keys [bus router registry logger]} (base-system)]
      (try
        (accounts/init! {:bus bus :router router :logger logger :read-provider-registry registry
                         :config {"storage.mode" "external-managed" "storage.backend" "sqlite" "storage.allow-self-managed" false}
                         :db (sqlite-resource (:path accounts-tmp))})
        (catalog/init! {:bus bus :router router :logger logger :read-provider-registry registry
                        :config {"storage.mode" "external-managed" "storage.backend" "sqlite" "storage.allow-self-managed" false}
                        :db (sqlite-resource (:path catalog-tmp))})
        (sut/init! {:bus bus :router router :logger logger :read-provider-registry registry
                    :config {"storage.mode" "external-managed" "storage.backend" "sqlite" "storage.allow-self-managed" false}
                    :db (sqlite-resource (:path booking-tmp))})
        (let [handler (-> (router/as-ring-handler router)
                          (http/wrap-correlation-context {:expose-headers? true}))
              response (handler (request "/bookings/actions/create" {"slot-id" "slot-11-00" "user-id" "u-alice"}))]
          (is (= 409 (:status response)))
          (is (= "slot not open" (:body response))))
        (finally
          (cleanup-temp! accounts-tmp)
          (cleanup-temp! catalog-tmp)
          (cleanup-temp! booking-tmp))))))

(deftest booking-init-fails-fast-without-required-providers-test
  (testing "booking init! fails when required providers are absent"
    (let [{:keys [bus router registry logger]} (base-system)
          booking-tmp (temp-db-path "booking3-bookings-" "bookings.db")
          ex (try
               (sut/init! {:bus bus
                           :router router
                           :logger logger
                           :read-provider-registry registry
                           :config {"storage.mode" "external-managed" "storage.backend" "sqlite" "storage.allow-self-managed" false}
                           :db (sqlite-resource (:path booking-tmp))})
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (try
        (is (some? ex))
        (is (= :missing-provider (-> ex ex-data :reason)))
        (finally
          (cleanup-temp! booking-tmp))))))
