(ns app.integration-test
  (:require [clojure.test :refer :all]
            [event-bus :as bus]
            [lcmm.router :as router]
            [ring.middleware.params :refer [wrap-params]]
            [app.name :as name]
            [app.hello :as hello]))

(defn- make-test-deps
  "Создаёт тестовые зависимости."
  []
  (let [logger (fn [_level _data] nil)  ; silent logger
        bus (bus/make-bus {:logger logger})
        router (router/make-router)]
    {:bus bus :router router :logger logger}))

(defn- make-app-handler
  "Создаёт полный HTTP handler приложения."
  []
  (let [{:keys [bus router logger]} (make-test-deps)
        deps {:bus bus :router router :logger logger}]
    
    ;; Инициализируем модули
    (name/init! deps)
    (hello/init! deps)
    
    ;; Создаём handler с роутером и wrap-params
    (let [router-handler (router/as-ring-handler router)]
      (wrap-params router-handler))))

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

(deftest full-flow-test
  (testing "Полный поток: /name → событие → /hello"
    (let [handler (make-app-handler)
          
          ;; 1. Запрос на установку имени
          name-request (make-request :get "/name" {"value" "FullFlowTest"})
          name-response (call-handler handler name-request)
          
          ;; Ждём асинхронную обработку событий
          _ (Thread/sleep 300)
          
          ;; 2. Запрос на получение приветствия
          hello-request (make-request :get "/hello")
          hello-response (call-handler handler hello-request)]
      
      ;; Проверяем response от /name
      (is (= 200 (:status name-response)))
      (is (= "Name set to: FullFlowTest" (:body name-response)))
      
      ;; Проверяем response от /hello
      (is (= 200 (:status hello-response)))
      (is (= "Hello FullFlowTest" (:body hello-response))))))

(deftest default-name-test
  (testing "Имя по умолчанию - World"
    (let [handler (make-app-handler)
          
          ;; Запрос без параметра value
          request (make-request :get "/name" {})
          response (call-handler handler request)
          
          _ (Thread/sleep 300)
          
          hello-request (make-request :get "/hello")
          hello-response (call-handler handler hello-request)]
      
      (is (= 200 (:status response)))
      (is (= "Name set to: World" (:body response)))
      (is (= "Hello World" (:body hello-response))))))

(deftest multiple-changes-test
  (testing "Множественные изменения имени"
    (let [handler (make-app-handler)]
      
      ;; Меняем имя несколько раз
      (doseq [name ["First" "Second" "Third"]]
        (let [request (make-request :get "/name" {"value" name})]
          (call-handler handler request)
          (Thread/sleep 100)))
      
      ;; Ждём обработки последнего события
      (Thread/sleep 300)
      
      ;; Проверяем последнее имя
      (let [hello-request (make-request :get "/hello")
            hello-response (call-handler handler hello-request)]
        (is (= 200 (:status hello-response)))
        (is (= "Hello Third" (:body hello-response)))))))

(deftest special-characters-test
  (testing "Специальные символы в имени"
    (let [handler (make-app-handler)
          test-name "Test-User_123"
          request (make-request :get "/name" {"value" test-name})
          response (call-handler handler request)
          
          _ (Thread/sleep 300)
          
          hello-request (make-request :get "/hello")
          hello-response (call-handler handler hello-request)]
      
      (is (= 200 (:status response)))
      (is (= (str "Name set to: " test-name) (:body response)))
      (is (= (str "Hello " test-name) (:body hello-response))))))
