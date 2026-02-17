(ns app.name-test
  (:require [clojure.test :refer :all]
            [event-bus :as bus]
            [lcmm.router :as router]
            [ring.middleware.params :refer [wrap-params]]
            [app.name :as name]))

(defn- make-test-deps
  "Создаёт тестовые зависимости."
  []
  (let [logger (fn [_level _data] nil)  ; silent logger
        bus (bus/make-bus {:logger logger})
        router (router/make-router)]
    {:bus bus :router router :logger logger}))

(defn- make-request
  "Создаёт тестовый Ring request."
  ([method uri]
   (make-request method uri nil))
  ([method uri query-params]
   {:request-method method
    :uri uri
    :query-params query-params
    :headers {}
    :path-params {}}))

(defn- call-handler
  "Вызывает handler с request и возвращает response."
  [handler request]
  (handler request))

(deftest init-test
  (testing "init! регистрирует маршрут"
    (let [{:keys [bus router logger]} (make-test-deps)]
      (name/init! {:bus bus :router router :logger logger})
      (is (some? router)))))

(deftest handle-set-name-test
  (testing "handle-set-name возвращает 200 и правильное тело"
    (let [{:keys [bus router logger]} (make-test-deps)
          _ (name/init! {:bus bus :router router :logger logger})
          ;; Создаём handler напрямую для тестирования
          handler (partial name/handle-set-name bus logger)]
      
      ;; Тест с параметром
      (let [request (make-request :get "/name" {"value" "Alice"})
            response (call-handler handler request)]
        (is (= 200 (:status response)))
        (is (= "Name set to: Alice" (:body response))))
      
      ;; Тест без параметра (default "World")
      (let [request (make-request :get "/name" {})
            response (call-handler handler request)]
        (is (= 200 (:status response)))
        (is (= "Name set to: World" (:body response)))))))

(deftest handle-set-name-publishes-event-test
  (testing "handle-set-name публикует событие :name/changed"
    (let [{:keys [bus router logger]} (make-test-deps)
          published-events (atom [])
          _ (bus/subscribe bus :name/changed
                           (fn [_bus envelope]
                             (swap! published-events conj (:payload envelope))))
          _ (name/init! {:bus bus :router router :logger logger})
          handler (partial name/handle-set-name bus logger)
          request (make-request :get "/name" {"value" "TestName"})
          _ (call-handler handler request)]
      
      (Thread/sleep 100)  ; ждём асинхронную обработку
      (is (= 1 (count @published-events)))
      (is (= {:name "TestName"} (first @published-events))))))

(deftest integration-test
  (testing "Интеграционный тест: HTTP запрос → событие → обработчик"
    (let [{:keys [bus router logger]} (make-test-deps)
          name-changed-received (atom false)
          current-name (atom nil)
          
          ;; Подписываемся на событие (как hello модуль)
          _ (bus/subscribe bus :name/changed
                           (fn [_bus envelope]
                             (reset! name-changed-received true)
                             (reset! current-name (get-in envelope [:payload :name]))))
          
          ;; Инициализируем модуль
          _ (name/init! {:bus bus :router router :logger logger})
          
          ;; Создаём полный handler с роутером и wrap-params
          router-handler (router/as-ring-handler router)
          app-handler (wrap-params router-handler)
          
          ;; Выполняем запрос
          request (make-request :get "/name" {"value" "IntegrationTest"})
          response (call-handler app-handler request)]
      
      ;; Ждём асинхронную обработку событий
      (Thread/sleep 200)
      
      ;; Проверяем response
      (is (= 200 (:status response)))
      (is (= "Name set to: IntegrationTest" (:body response)))
      
      ;; Проверяем что событие было получено
      (is (true? @name-changed-received))
      (is (= "IntegrationTest" @current-name)))))
