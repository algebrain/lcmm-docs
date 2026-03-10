(ns app.integration-test
  (:require [app.audit :as audit]
            [app.booking :as booking]
            [app.notify :as notify]
            [app.sqlite :as sqlite]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [event-bus :as bus]
            [lcmm.router :as router]
            [ring.middleware.params :refer [wrap-params]]))

(defn- make-test-system []
  (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory "lcmm-example2-" (make-array java.nio.file.attribute.FileAttribute 0)))
        db-path (str (.getAbsolutePath tmp-dir) java.io.File/separator "test.db")
        logger (fn [_level _data] nil)
        schema-registry {:booking/create-requested {"1.0" [:map [:slot :string] [:name :string]]}
                         :booking/created {"1.0" [:map [:booking-id :string] [:slot :string] [:name :string]]}
                         :booking/rejected {"1.0" [:map [:slot :string] [:name :string] [:reason :string]]}
                         :notify/booking-created {"1.0" [:map [:booking-id :string] [:message :string]]}}
        event-bus (bus/make-bus {:logger logger :schema-registry schema-registry})
        app-router (router/make-router)
        db (sqlite/make-store db-path)
        _ (sqlite/init-schema! db)
        deps {:bus event-bus :router app-router :logger logger :db db}]
    (booking/init! deps)
    (notify/init! deps)
    (audit/init! deps)
    {:handler (wrap-params (router/as-ring-handler app-router))
     :bus event-bus
     :db db
     :tmp-dir tmp-dir}))

(defn- cleanup-system! [{:keys [bus db tmp-dir]}]
  (when (instance? java.io.Closeable bus)
    (.close ^java.io.Closeable bus))
  (sqlite/close-store! db)
  (when (and tmp-dir (.exists ^java.io.File tmp-dir))
    (doseq [f (reverse (file-seq tmp-dir))]
      (.delete ^java.io.File f))))

(defn- request [uri query]
  {:request-method :get
   :uri uri
   :query-params query
   :headers {}
   :path-params {}})

(defn- body->edn [response]
  (edn/read-string (:body response)))

(deftest booking-success-flow-test
  (testing "Создание брони проходит и запускает reactive-модули"
    (let [system (make-test-system)]
      (try
        (let [handler (:handler system)
              create-resp (handler (request "/booking/create" {"slot" "10:00" "name" "Ann"}))
              _ (Thread/sleep 250)
              bookings-resp (handler (request "/booking/list" {}))
              notify-resp (handler (request "/notify/list" {}))
              audit-resp (handler (request "/audit/list" {}))
              bookings (body->edn bookings-resp)
              notifications (body->edn notify-resp)
              audit-items (body->edn audit-resp)]
          (is (= 200 (:status create-resp)))
          (is (str/includes? (:body create-resp) "BOOKING CREATED id="))
          (is (= 1 (count bookings)))
          (is (= "10:00" (:slot (first bookings))))
          (is (= 1 (count notifications)))
          (is (<= 2 (count audit-items))))
        (finally
          (cleanup-system! system))))))

(deftest duplicate-slot-rejected-test
  (testing "Вторая бронь на тот же слот отклоняется"
    (let [system (make-test-system)]
      (try
        (let [handler (:handler system)
              first-resp (handler (request "/booking/create" {"slot" "11:00" "name" "Ann"}))
              second-resp (handler (request "/booking/create" {"slot" "11:00" "name" "Bob"}))
              _ (Thread/sleep 250)
              audit-resp (handler (request "/audit/list" {}))
              audit-items (body->edn audit-resp)
              event-types (set (map :event_type audit-items))]
          (is (= 200 (:status first-resp)))
          (is (= 409 (:status second-resp)))
          (is (str/includes? (:body second-resp) "BOOKING REJECTED"))
          (is (contains? event-types "booking/rejected")))
        (finally
          (cleanup-system! system))))))

(deftest get-booking-by-id-test
  (testing "Чтение брони по id"
    (let [system (make-test-system)]
      (try
        (let [handler (:handler system)
              create-resp (handler (request "/booking/create" {"slot" "12:00" "name" "Kate"}))
              booking-id (second (str/split (:body create-resp) #"id="))
              _ (Thread/sleep 200)
              get-resp (handler (request "/booking/get" {"id" booking-id}))
              booking (body->edn get-resp)]
          (is (= 200 (:status create-resp)))
          (is (= 200 (:status get-resp)))
          (is (= booking-id (:id booking)))
          (is (= "12:00" (:slot booking)))
          (is (= "Kate" (:customer_name booking))))
        (finally
          (cleanup-system! system))))))
