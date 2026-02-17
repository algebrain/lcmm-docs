# Документация по API: Router

Этот документ описывает публичный API для компонента `Router`. Роутер служит центральной точкой для регистрации HTTP-маршрутов всеми модулями системы, реализуя принцип инверсии управления (IoC).
Имплементация доступна в [GitHub-репозитории](https://github.com/algebrain/lcmm-router).

## Конструктор

### `make-router`

Создает новый, пустой экземпляр роутера.

```clojure
(require '[lcmm.router :as router])

(def my-router (router/make-router))
```
Конструктор не принимает опций. Вся конфигурация происходит при добавлении маршрутов и компиляции.

## Основные функции

### `add-route!`

Регистрирует один HTTP-эндпоинт (обработчик) для указанного пути и метода. Функция является потокобезопасной.

- **Сигнатура:** `(add-route! router method path handler & [opts])`
- **`router`:** Экземпляр роутера, созданный `make-router`.
- **`method`:** Ключевое слово HTTP-метода (например, `:get`, `:post`).
- **`path`:** Строка пути, поддерживающая синтаксис `reitit` (например, `"/users/:id"`).
- **`handler`:** Ring-совместимая функция-обработчик.
- **`opts`:** (опционально) Карта с дополнительными опциями `reitit`, такими как:
  - `:name`: Имя маршрута (keyword) для его идентификации.
  - `:middleware`: Вектор Ring-middleware, применяемого только к этому маршруту.

**Пример от модуля `users`:**
```clojure
(defn users-module-init!
  "Функция инициализации модуля, получающая зависимости, включая роутер."
  [router]
  (let [find-user-handler (fn [req] {:status 200 :body "user found"})
        create-user-handler (fn [req] {:status 201 :body "user created"})]
    
    (router/add-route! router
                       :get "/api/users/:id"
                       find-user-handler
                       {:name ::find-user})

    (router/add-route! router
                       :post "/api/users"
                       create-user-handler
                       {:name ::create-user
                        :middleware [[my-auth-middleware "admin"]]})))
```

### `as-ring-handler`

Компилирует все зарегистрированные маршруты в один финальный `Ring-handler`, готовый для запуска в веб-сервере (например, `http-kit`).

- **Сигнатура:** `(as-ring-handler router & [opts])`
- **`router`:** Экземпляр роутера со всеми зарегистрированными маршрутами.
- **`opts`:** (опционально) Карта с глобальными опциями `reitit`, которые будут применены ко всем маршрутам. Чаще всего используется для добавления глобального `middleware`.

**Важная особенность:** Возвращаемый обработчик содержит в своих метаданных скомпилированный `reitit`-роутер под ключом `::router/router`. Это полезно для тестирования и интроспекции.

**Пример запуска системы:**
```clojure
(require '[org.httpkit.server :as http-kit])

(defn -main []
  (let [app-router (router/make-router)
        global-middleware [my-logging-middleware my-cors-middleware]]
    
    ;; Модули регистрируют свои маршруты
    (users-module-init! app-router)
    (orders-module-init! app-router)
    
    ;; Компилируем финальный обработчик с глобальным middleware
    (let [http-handler (router/as-ring-handler app-router {:middleware global-middleware})]
      (println "Starting web server on port 8080...")
      (http-kit/run-server http-handler {:port 8080}))))
```

## Тестирование

Для проверки специфичного для `reitit` функционала (например, поиск маршрута по имени) вы можете извлечь скомпилированный роутер из метаданных `ring-handler`'а.

**Пример теста:**
```clojure
(require '[clojure.test :refer :all]
         '[lcmm.router :as router]
         '[reitit.core :as reitit])

(deftest find-by-name-test
  (let [rtr (router/make-router)]
    (router/add-route! rtr :get "/test" (constantly {:status 200}) {:name ::my-test-route})

    (let [handler (router/as-ring-handler rtr)
          ;; Извлекаем роутер из метаданных
          compiled-router (::router/router (meta handler))]

      (is (some? (reitit/match-by-name compiled-router ::my-test-route))))))
```
