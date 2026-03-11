(ns app.http-server-virtual-threads-test
  (:require [app.audit :as audit]
            [app.booking :as booking]
            [app.notify :as notify]
            [app.sqlite :as sqlite]
            [clojure.test :refer [deftest is testing]]
            [event-bus :as bus]
            [lcmm.router :as router]
            [org.httpkit.server :as http-kit]
            [ring.middleware.params :refer [wrap-params]]))

(defn- make-test-system []
  (let [tmp-dir (.toFile (java.nio.file.Files/createTempDirectory "lcmm-example2-http-" (make-array java.nio.file.attribute.FileAttribute 0)))
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

(defn- start-http-server [handler]
  (let [server (http-kit/run-server handler {:port 0
                                             :legacy-return-value? false})]
    {:server server
     :port (http-kit/server-port server)}))

(defn- stop-http-server! [server]
  (when server
    (http-kit/server-stop! server {:timeout 1000})))

(defn- http-get [port path]
  (let [client (java.net.http.HttpClient/newHttpClient)
        request (-> (java.net.http.HttpRequest/newBuilder
                     (java.net.URI/create (str "http://127.0.0.1:" port path)))
                    (.GET)
                    (.build))]
    (.send client request
           (java.net.http.HttpResponse$BodyHandlers/ofString))))

(deftest http-kit-uses-virtual-threads-for-network-requests-test
  (testing "booking-simple handles real HTTP requests in virtual threads"
    (let [system (make-test-system)
          handled-thread (atom nil)]
      (try
        (let [wrapped-handler (fn [request]
                                (reset! handled-thread (Thread/currentThread))
                                ((:handler system) request))
              {:keys [server port]} (start-http-server wrapped-handler)]
          (try
            (let [response (http-get port "/booking/list")]
              (is (= 200 (.statusCode response)))
              (is (instance? Thread @handled-thread))
              (is (.isVirtual ^Thread @handled-thread)))
            (finally
              (stop-http-server! server))))
        (finally
          (cleanup-system! system))))))
