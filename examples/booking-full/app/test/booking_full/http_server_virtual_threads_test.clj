(ns booking-full.http-server-virtual-threads-test
  (:require [booking-full.test-support :as support]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http-kit]))

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
  (testing "booking-full handles real HTTP requests in virtual threads"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)
          handled-thread (atom nil)]
      (try
        (let [wrapped-handler (fn [request]
                                (reset! handled-thread (Thread/currentThread))
                                (handler request))
              {:keys [server port]} (start-http-server wrapped-handler)]
          (try
            (let [response (http-get port "/healthz")]
              (is (= 200 (.statusCode response)))
              (is (instance? Thread @handled-thread))
              (is (.isVirtual ^Thread @handled-thread)))
            (finally
              (stop-http-server! server))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))
