(ns reference-app.accounts.core-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]
            [reference-app.accounts.core :as sut]
            [reference-app.accounts.db :as db]))

(defn- temp-sqlite-path []
  (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory
                          "reference-app-accounts-"
                          (make-array java.nio.file.attribute.FileAttribute 0)))]
    {:dir tmp-dir
     :path (str (.getAbsolutePath tmp-dir) java.io.File/separator "accounts.db")}))

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

(deftest registers-providers-and-serves-users-from-external-db-test
  (testing "init! registers accounts providers and uses the external db resource"
    (let [tmp (temp-sqlite-path)
          store (db/make-sqlite-store {:path (:path tmp)})
          _ (db/init-schema! store)
          _ (db/seed-users! store [{:id "u-42"
                                    :login "ann"
                                    :display-name "Ann Example"
                                    :role "user"
                                    :password "secret"}])
          deps (make-deps (make-external-db-resource (:path tmp)))]
      (try
        (sut/init! deps)
        (let [by-id (rpr/require-provider (:read-provider-registry deps) :accounts/get-user-by-id)
              by-login (rpr/require-provider (:read-provider-registry deps) :accounts/get-user-by-login)
              handler (router/as-ring-handler (:router deps))
              me-response (handler {:request-method :get
                                    :uri "/accounts/me"
                                    :query-params {"user-id" "u-42"}})
              user-response (handler {:request-method :get
                                      :uri "/accounts/users/u-42"
                                      :path-params {:user_id "u-42"}})]
          (is (= "u-42" (:id (by-id {:user-id "u-42"}))))
          (is (= "u-42" (:id (by-login {:login "ann"}))))
          (is (= 200 (:status me-response)))
          (is (= "u-42" (:id (edn/read-string (:body me-response)))))
          (is (= 200 (:status user-response))))
        (finally
          (cleanup-temp! tmp))))))

(deftest self-managed-sqlite-mode-seeds-demo-users-test
  (testing "self-managed sqlite is allowed only when config explicitly enables it"
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
        (let [by-login (rpr/require-provider (:read-provider-registry deps) :accounts/get-user-by-login)]
          (is (= "u-alice" (:id (by-login {:login "alice"})))))
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
                     :db-spec {:jdbc-url "jdbc:mysql://localhost/demo"}}}]
      (let [ex (try
                 (sut/init! deps)
                 nil
                 (catch clojure.lang.ExceptionInfo ex
                   ex))]
        (is (some? ex))
        (is (re-find #"Unsupported external backend" (.getMessage ex)))
        (is (= :unsupported-backend (-> ex ex-data :reason)))))))

(deftest provider-validation-error-shape-test
  (testing "provider returns documented error map for invalid input"
    (let [tmp (temp-sqlite-path)
          store (db/make-sqlite-store {:path (:path tmp)})
          _ (db/init-schema! store)
          deps (make-deps (make-external-db-resource (:path tmp)))]
      (try
        (sut/init! deps)
        (let [by-id (rpr/require-provider (:read-provider-registry deps) :accounts/get-user-by-id)]
          (is (= {:code :invalid-arg
                  :message "user-id must be non-empty string"
                  :retryable? false}
                 (by-id {:user-id ""}))))
        (finally
          (cleanup-temp! tmp))))))
