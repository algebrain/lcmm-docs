(ns reference-app.notify.core-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [event-bus :as bus]
            [lcmm.http.core :as http]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.accounts.core :as accounts]
            [reference-app.booking.core :as booking]
            [reference-app.catalog.core :as catalog]
            [reference-app.notify.core :as sut]))

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

(deftest notify-reacts-to-booking-created-and-publishes-derived-event-test
  (testing "notify stores notification and exposes it through HTTP after booking flow"
    (let [accounts-tmp (temp-db-path "notify-accounts-" "accounts.db")
          catalog-tmp (temp-db-path "notify-catalog-" "catalog.db")
          booking-tmp (temp-db-path "notify-booking-" "booking.db")
          notify-tmp (temp-db-path "notify-notify-" "notify.db")
          derived-events (atom [])
          {:keys [bus router registry logger]} (base-system)]
      (try
        (bus/subscribe bus
                       :notify/booking-created
                       (fn [_ envelope]
                         (swap! derived-events conj envelope))
                       {:meta ::derived-events-probe})
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
        (sut/init! {:bus bus
                    :router router
                    :logger logger
                    :config {"storage.mode" "external-managed"
                             "storage.backend" "sqlite"
                             "storage.allow-self-managed" false}
                    :db (sqlite-resource (:path notify-tmp))})
        (let [handler (-> (router/as-ring-handler router)
                          (http/wrap-correlation-context {:expose-headers? true}))
              create-response (handler (request "/bookings/actions/create" {"slot-id" "slot-09-00" "user-id" "u-alice"}))]
          (Thread/sleep 250)
          (let [list-response (handler (request "/notifications" {}))
                notifications (edn/read-string (:body list-response))
                derived-event (first @derived-events)]
            (is (= 200 (:status create-response)))
            (is (= 200 (:status list-response)))
            (is (= 1 (count notifications)))
            (is (= 1 (count @derived-events)))
            (is (= :notify/booking-created (:event-type derived-event)))
            (is (= (-> derived-event :payload :booking-id)
                   (:booking_id (first notifications))))))        
        (finally
          (cleanup-temp! accounts-tmp)
          (cleanup-temp! catalog-tmp)
          (cleanup-temp! booking-tmp)
          (cleanup-temp! notify-tmp))))))
