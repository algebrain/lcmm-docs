(ns booking-full.websocket-test
  (:require [booking-full.test-support :as support]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http-kit])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers WebSocket WebSocket$Listener]
           [java.util.concurrent CompletableFuture CompletionException LinkedBlockingQueue TimeUnit]))

(defn- start-http-server [handler]
  (let [server (http-kit/run-server handler {:port 0
                                             :legacy-return-value? false})]
    {:server server
     :port (http-kit/server-port server)}))

(defn- stop-http-server! [server]
  (when server
    (http-kit/server-stop! server {:timeout 1000})))

(defn- completed-future []
  (CompletableFuture/completedFuture nil))

(defn- make-websocket-listener [messages]
  (reify WebSocket$Listener
    (onOpen [_ web-socket]
      (.request web-socket 1)
      (completed-future))

    (onText [_ web-socket data last]
      (when last
        (.offer messages (str data)))
      (.request web-socket 1)
      (completed-future))

    (onError [_ _ error]
      (.offer messages error))))

(defn- connect-websocket [port user-id origin]
  (let [messages (LinkedBlockingQueue.)
        listener (make-websocket-listener messages)
        client (HttpClient/newHttpClient)
        builder (.newWebSocketBuilder client)
        _ (.header builder "Origin" origin)
        ws (.join (.buildAsync builder
                               (URI/create (str "ws://127.0.0.1:" port "/ws?user-id=" user-id))
                               listener))]
    {:client client
     :websocket ws
     :messages messages}))

(defn- receive-message [messages]
  (.poll messages 3 TimeUnit/SECONDS))

(defn- receive-json [messages]
  (some-> (receive-message messages)
          (json/read-str :key-fn keyword)))

(defn- http-get [port path]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder
                     (URI/create (str "http://127.0.0.1:" port path)))
                    (.GET)
                    (.build))]
    (.send client request (HttpResponse$BodyHandlers/ofString))))

(deftest ws-demo-page-is-served-test
  (testing "app serves a small built-in websocket demo page"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [{:keys [server port]} (start-http-server handler)]
          (try
            (let [response (http-get port "/ws-demo")]
              (is (= 200 (.statusCode response)))
              (is (str/includes? (.body response) "booking-full ws demo"))
              (is (str/includes? (.body response) "Connect"))
              (is (str/includes? (.body response) "/ws")))
            (finally
              (stop-http-server! server))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))

(deftest websocket-server-push-flow-test
  (testing "subscribed websocket client receives booking created event through real network flow"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [{:keys [server port]} (start-http-server handler)
              origin (str "http://127.0.0.1:" port)]
          (try
            (let [{:keys [websocket messages]} (connect-websocket port "u-alice" origin)]
              (try
                (.join (.sendText websocket "{\"type\":\"subscribe\",\"topic\":[\"user\",\"u-alice\"]}" true))
                (let [subscribed (receive-json messages)]
                  (is (= {:type "subscribed"
                          :topic ["user" "u-alice"]}
                         subscribed)))
                (let [response (http-get port "/bookings/actions/create?slot-id=slot-09-00&user-id=u-alice")]
                  (is (= 200 (.statusCode response))))
                (let [event-message (receive-json messages)]
                  (is (= "event" (:type event-message)))
                  (is (= "booking/created" (:event event-message)))
                  (is (= "u-alice" (get-in event-message [:payload :userId])))
                  (is (string? (get-in event-message [:payload :bookingId]))))
                (finally
                  (.join (.sendClose websocket WebSocket/NORMAL_CLOSURE "bye")))))
            (finally
              (stop-http-server! server))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))

(deftest forbidden-subscribe-returns-unified-error-test
  (testing "client cannot subscribe to another user topic and gets unified rejection"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [{:keys [server port]} (start-http-server handler)
              origin (str "http://127.0.0.1:" port)]
          (try
            (let [{:keys [websocket messages]} (connect-websocket port "u-admin" origin)]
              (try
                (.join (.sendText websocket "{\"type\":\"subscribe\",\"topic\":[\"user\",\"u-alice\"]}" true))
                (let [message (receive-json messages)]
                  (is (= "error" (:type message)))
                  (is (= "subscription_rejected" (:code message)))
                  (is (= "Subscription rejected" (:message message))))
                (finally
                  (.join (.sendClose websocket WebSocket/NORMAL_CLOSURE "bye")))))
            (finally
              (stop-http-server! server))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))

(deftest ping-and-unsubscribe-work-over-real-network-test
  (testing "client can ping and unsubscribe through real websocket flow"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [{:keys [server port]} (start-http-server handler)
              origin (str "http://127.0.0.1:" port)]
          (try
            (let [{:keys [websocket messages]} (connect-websocket port "u-alice" origin)]
              (try
                (.join (.sendText websocket "{\"type\":\"ping\"}" true))
                (is (= {:type "pong"}
                       (receive-json messages)))
                (.join (.sendText websocket "{\"type\":\"subscribe\",\"topic\":[\"user\",\"u-alice\"]}" true))
                (is (= {:type "subscribed"
                        :topic ["user" "u-alice"]}
                       (receive-json messages)))
                (.join (.sendText websocket "{\"type\":\"unsubscribe\",\"topic\":[\"user\",\"u-alice\"]}" true))
                (is (= {:type "unsubscribed"
                        :topic ["user" "u-alice"]}
                       (receive-json messages)))
                (finally
                  (.join (.sendClose websocket WebSocket/NORMAL_CLOSURE "bye")))))
            (finally
              (stop-http-server! server))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))

(deftest cross-origin-websocket-handshake-is-rejected-test
  (testing "cross-origin websocket handshake is rejected"
    (let [{:keys [handler test-db-resources]} (support/make-test-system)]
      (try
        (let [{:keys [server port]} (start-http-server handler)]
          (try
            (is (thrown? CompletionException
                         (connect-websocket port "u-alice" "http://evil.example")))
            (finally
              (stop-http-server! server))))
        (finally
          (doseq [resource test-db-resources]
            (support/cleanup-temp! resource)))))))
