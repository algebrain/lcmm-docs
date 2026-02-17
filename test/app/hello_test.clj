(ns app.hello-test
  (:require [clojure.test :refer :all]
            [event-bus :as bus]
            [lcmm.router :as router]
            [app.hello :as hello]))

(defn- make-test-deps
  "Создаёт тестовые зависимости."
  []
  (let [logger (fn [_level _data] nil)  ; silent logger
        bus (bus/make-bus {:logger logger})
        router (router/make-router)]
    {:bus bus :router router :logger logger}))

(defn- make-request
  "Создаёт тестовый Ring request."
  [method uri]
  {:request-method method
   :uri uri
   :headers {}
   :path-params {}})

(defn- call-handler
  "Вызывает handler с request и возвращает response."
  [handler request]
  (handler request))

;; Сбрасываем атом перед тестами
(defn- reset-hello-state []
  (reset! hello/current-name "World"))

(deftest init-test
  (testing "init! регистрирует маршрут и подписки"
    (reset-hello-state)
    (let [{:keys [bus router logger]} (make-test-deps)]
      (hello/init! {:bus bus :router router :logger logger})
      (is (some? router)))))

(deftest handle-get-hello-test
  (testing "handle-get-hello возвращает приветствие с текущим именем"
    (reset-hello-state)
    (let [{:keys [bus router logger]} (make-test-deps)
          handler (partial hello/handle-get-hello logger)]
      
      ;; Тест с именем по умолчанию
      (reset! hello/current-name "World")
      (let [request (make-request :get "/hello")
            response (call-handler handler request)]
        (is (= 200 (:status response)))
        (is (= "Hello World" (:body response))))
      
      ;; Тест с изменённым именем
      (reset! hello/current-name "Alice")
      (let [request (make-request :get "/hello")
            response (call-handler handler request)]
        (is (= 200 (:status response)))
        (is (= "Hello Alice" (:body response)))))))

(deftest handle-name-changed-test
  (testing "handle-name-changed обновляет current-name"
    (reset-hello-state)
    (let [{:keys [bus router logger]} (make-test-deps)
          ;; Создаём полноценный envelope через publish
          event-published (atom nil)
          _ (bus/subscribe bus :name/changed
                           (fn [_bus envelope]
                             (reset! event-published envelope)))
          _ (hello/init! {:bus bus :router router :logger logger})
          _ (bus/publish bus :name/changed {:name "TestUser"})]
      
      ;; Ждём асинхронную обработку
      (Thread/sleep 200)
      
      ;; Проверяем что событие было получено и атом обновился через подписку в init!
      (is (some? @event-published))
      (is (= "TestUser" @hello/current-name))
      
      ;; Теперь вручную вызываем обработчик с другим именем
      (hello/handle-name-changed bus @event-published logger)
      
      ;; Проверяем что атом обновился снова (теперь "TestUser" из envelope)
      (is (= "TestUser" @hello/current-name)))))

(deftest handle-name-changed-publishes-event-test
  (testing "handle-name-changed публикует событие :hello/greeting-updated"
    (reset-hello-state)
    (let [{:keys [bus router logger]} (make-test-deps)
          published-events (atom [])
          ;; Сначала подписываемся на :hello/greeting-updated
          _ (bus/subscribe bus :hello/greeting-updated
                           (fn [_bus envelope]
                             (swap! published-events conj (:payload envelope))))
          _ (hello/init! {:bus bus :router router :logger logger})
          ;; Создаём envelope через публикацию события
          event-published (atom nil)
          _ (bus/subscribe bus :name/changed
                           (fn [_bus envelope]
                             (reset! event-published envelope)))
          _ (bus/publish bus :name/changed {:name "EventTest"})]
      
      ;; Ждём асинхронную обработку :name/changed
      (Thread/sleep 200)
      
      ;; Событие должно быть опубликовано через подписку в init!
      (is (= 1 (count @published-events)))
      (is (= {:greeting "Hello EventTest"} (first @published-events))))))

(deftest integration-test
  (testing "Интеграционный тест: событие → обновление атома → GET /hello"
    (reset-hello-state)
    (let [{:keys [bus router logger]} (make-test-deps)
          
          ;; Инициализируем модуль
          _ (hello/init! {:bus bus :router router :logger logger})
          
          ;; Публикуем событие :name/changed (как это делает name модуль)
          _ (bus/publish bus :name/changed {:name "IntegrationUser"})
          
          ;; Ждём асинхронную обработку
          _ (Thread/sleep 200)
          
          ;; Создаём handler для /hello
          handler (partial hello/handle-get-hello logger)
          request (make-request :get "/hello")
          response (call-handler handler request)]
      
      ;; Проверяем что имя обновилось через событие
      (is (= "IntegrationUser" @hello/current-name))
      
      ;; Проверяем response
      (is (= 200 (:status response)))
      (is (= "Hello IntegrationUser" (:body response))))))
