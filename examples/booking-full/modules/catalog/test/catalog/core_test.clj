(ns catalog.core-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [catalog.core :as sut]
            [catalog.db :as db]))

(defn- temp-sqlite-path []
  (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory
                          "booking-full-catalog-"
                          (make-array java.nio.file.attribute.FileAttribute 0)))]
    {:dir tmp-dir
     :path (str (.getAbsolutePath tmp-dir) java.io.File/separator "catalog.db")}))

(defn- cleanup-temp! [{:keys [dir]}]
  (when (and dir (.exists ^java.io.File dir))
    (doseq [f (reverse (file-seq dir))]
      (.delete ^java.io.File f))))

(defn- make-external-db-resource [path]
  {:backend-type "sqlite"
   :db-spec {:classname "org.sqlite.JDBC"
             :subprotocol "sqlite"
             :subname path}})

(defn- make-deps [db-resource]
  {:bus ::fake-bus
   :router (router/make-router)
   :logger (fn [_level _data] nil)
   :read-provider-registry (rpr/make-registry)
   :config {"storage.mode" "external-managed"
            "storage.backend" "sqlite"
            "storage.allow-self-managed" false}
   :db db-resource})

(deftest registers-provider-and-serves-slots-from-external-db-test
  (testing "init! registers catalog provider and serves seeded slots from external db"
    (let [tmp (temp-sqlite-path)
          store (db/make-sqlite-store {:path (:path tmp)})
          _ (db/init-schema! store)
          _ (db/seed-slots! store [{:id "slot-12-00"
                                    :label "Consultation 12:00"
                                    :status "open"
                                    :starts-at "2026-03-06T12:00:00Z"}])
          deps (make-deps (make-external-db-resource (:path tmp)))]
      (try
        (sut/init! deps)
        (let [get-slot (rpr/require-provider (:read-provider-registry deps) :catalog/get-slot-by-id)
              handler (router/as-ring-handler (:router deps))
              list-response (handler {:request-method :get
                                      :uri "/catalog/slots"
                                      :query-params {}})
              one-response (handler {:request-method :get
                                     :uri "/catalog/slots/slot-12-00"
                                     :path-params {:slot_id "slot-12-00"}})]
          (is (= "slot-12-00" (:id (get-slot {:slot-id "slot-12-00"}))))
          (is (= 200 (:status list-response)))
          (is (= 1 (count (edn/read-string (:body list-response)))))
          (is (= 200 (:status one-response)))
          (is (= "slot-12-00" (:id (edn/read-string (:body one-response))))))
        (finally
          (cleanup-temp! tmp))))))

(deftest self-managed-sqlite-seeds-demo-slots-test
  (testing "self-managed sqlite mode seeds demo slots"
    (let [tmp (temp-sqlite-path)
          deps {:bus ::fake-bus
                :router (router/make-router)
                :logger (fn [_level _data] nil)
                :read-provider-registry (rpr/make-registry)
                :config {"storage.mode" "self-managed"
                         "storage.backend" "sqlite"
                         "storage.allow-self-managed" true
                         "storage.sqlite.path" (:path tmp)}}]
      (try
        (sut/init! deps)
        (let [get-slot (rpr/require-provider (:read-provider-registry deps) :catalog/get-slot-by-id)]
          (is (= "slot-09-00" (:id (get-slot {:slot-id "slot-09-00"})))))
        (finally
          (cleanup-temp! tmp))))))

(deftest list-slots-by-status-via-browser-friendly-query-test
  (testing "GET /catalog/slots?status=open remains browser-friendly"
    (let [tmp (temp-sqlite-path)
          deps {:bus ::fake-bus
                :router (router/make-router)
                :logger (fn [_level _data] nil)
                :read-provider-registry (rpr/make-registry)
                :config {"storage.mode" "self-managed"
                         "storage.backend" "sqlite"
                         "storage.allow-self-managed" true
                         "storage.sqlite.path" (:path tmp)}}]
      (try
        (sut/init! deps)
        (let [handler (router/as-ring-handler (:router deps))
              response (handler {:request-method :get
                                 :uri "/catalog/slots"
                                 :query-params {"status" "open"}})
              slots (edn/read-string (:body response))]
          (is (= 200 (:status response)))
          (is (= 2 (count slots)))
          (is (every? #(= "open" (:status %)) slots)))
        (finally
          (cleanup-temp! tmp))))))

(deftest unsupported-backend-fails-fast-test
  (testing "unsupported external backend fails during init!"
    (let [deps {:bus ::fake-bus
                :router (router/make-router)
                :logger (fn [_level _data] nil)
                :read-provider-registry (rpr/make-registry)
                :config {"storage.mode" "external-managed"
                         "storage.backend" "jdbc"}
                :db {:backend-type "mysql"
                     :db-spec {:jdbc-url "jdbc:mysql://localhost/demo"}}}
          ex (try
               (sut/init! deps)
               nil
               (catch clojure.lang.ExceptionInfo ex
                 ex))]
      (is (some? ex))
      (is (re-find #"Unsupported external backend" (.getMessage ex)))
      (is (= :unsupported-backend (-> ex ex-data :reason))))))
