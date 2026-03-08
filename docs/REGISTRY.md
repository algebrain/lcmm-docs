# Спецификация API: Read-Provider Registry

Версия: `1.0-draft`
Репозиторий: `github.com/algebrain/lcmm-registry`

Для практического места этого механизма в startup sequence см. [`APP_COMPOSITION.md`](./APP_COMPOSITION.md).

## 1. Назначение

`read-provider registry` нужен для контролируемого синхронного чтения данных
между модулями LCMM без прямой связанности namespace -> namespace.

Механизм решает задачи:

1. Явно фиксирует, какие read-провайдеры модуль предоставляет.
2. Явно фиксирует, какие внешние read-провайдеры модулю обязательны.
3. Позволяет делать fail-fast startup-check до запуска HTTP.

## 2. Когда использовать

Используйте registry, когда:

1. Нужен sync-read данных другого модуля.
2. Нужна проверяемая и явная межмодульная зависимость.
3. Полный hydration-процесс для этого кейса избыточен.

Не используйте registry для:

1. Side-effects и реактивных процессов (для этого `event-bus`).
2. Каскадных межмодульных provider-вызовов provider -> provider.

## 3. Уровни применения API

- Уровень приложения (composition root): создание реестра и финальная
  startup-проверка.
- Уровень модуля-владельца: регистрация своих provider-функций.
- Уровень модуля-потребителя: декларация required-провайдеров и получение
  провайдера для вызова.

Практическая схема:

1. приложение создает один общий registry;
2. модули-владельцы данных регистрируют свои providers;
3. модули-потребители объявляют обязательные зависимости;
4. приложение выполняет `assert-requirements!` до старта HTTP;
5. provider затем используется либо внутри consumer-модуля, либо в app-level
   handler.

## 4. API по функциям

### 4.1 `make-registry`

Зачем нужна:
- Создает общий in-memory реестр для providers и requirements.

Где применять:
- В composition root приложения (`-main`), один раз на запуск.

Уровень:
- Приложение.

Пример:

```clojure
(def registry (rpr/make-registry))
```

### 4.2 `register-provider!`

Зачем нужна:
- Регистрирует read-провайдер модуля с уникальным `provider-id`.

Где применять:
- В `init!` модуля-владельца данных.

Уровень:
- Модуль (provider-owner).

Пример:

```clojure
(rpr/register-provider! registry
  {:provider-id :users/get-user-by-id
   :module :users
   :provider-fn (fn [{:keys [user-id]}]
                  {:ok? true
                   :value {:id user-id}})
   :meta {:version "1.0"}})
```

Поведение:
- При дублировании `provider-id` бросает `ex-info` с `:reason :duplicate-provider`.
- При невалидных аргументах бросает `ex-info` с `:reason :invalid-argument`.

### 4.3 `resolve-provider`

Зачем нужна:
- Возвращает provider-функцию по `provider-id` или `nil`, если провайдера нет.

Где применять:
- Когда отсутствие провайдера допустимо и обрабатывается явно в логике.

Уровень:
- Обычно модуль, иногда приложение.

Пример:

```clojure
(if-let [get-user (rpr/resolve-provider registry :users/get-user-by-id)]
  (get-user {:user-id "u-1"})
  {:ok? false :error {:code :provider-missing}})
```

Поведение:
- Если `provider-id` не keyword, бросает `ex-info` с `:reason :invalid-argument`.
- Если `registry` невалиден, бросает `ex-info` с `:reason :invalid-registry`.
- Если provider отсутствует, возвращает `nil`.

Когда выбирать:

1. когда отсутствие provider действительно допустимо;
2. когда логика умеет продолжать работу без него;
3. когда missing provider не считается ошибкой wiring.

### 4.4 `require-provider`

Зачем нужна:
- Возвращает provider-функцию, а при отсутствии сразу бросает исключение.

Где применять:
- В местах, где зависимость обязательна и должна быть fail-fast.

Уровень:
- Модуль-потребитель.

Пример:

```clojure
(let [get-user (rpr/require-provider registry :users/get-user-by-id)]
  (get-user {:user-id "u-1"}))
```

Поведение:
- Если провайдер отсутствует, бросает `ex-info` с `:reason :missing-provider`.

Когда выбирать:

1. когда зависимость обязательна;
2. когда отсутствие provider означает неправильный wiring приложения;
3. когда нужен fail-fast вместо мягкой деградации.

### 4.5 `declare-requirements!`

Зачем нужна:
- Объявляет обязательные внешние read-зависимости модуля.

Где применять:
- В `init!` модуля-потребителя.

Уровень:
- Модуль (consumer).

Пример:

```clojure
(rpr/declare-requirements! registry
  :orders
  #{:users/get-user-by-id :payments/get-method})
```

Поведение:
- Повторные вызовы для того же модуля объединяют множества требований.
- Невалидные аргументы -> `ex-info` с `:reason :invalid-argument`.

### 4.6 `validate-requirements`

Зачем нужна:
- Проверяет, что все declared requirements закрыты зарегистрированными providers.

Где применять:
- В startup-check или диагностике конфигурации.

Уровень:
- Приложение.

Пример:

```clojure
(rpr/validate-requirements registry)
;; => {:ok? true}
;; или
;; => {:ok? false :missing {:orders #{:users/get-user-by-id}}}
```

### 4.7 `assert-requirements!`

Зачем нужна:
- Выполняет fail-fast проверку готовности перед стартом HTTP.

Где применять:
- В composition root после `init!` всех модулей.

Уровень:
- Приложение.

Пример:

```clojure
(rpr/assert-requirements! registry)
;; true при успехе
```

Поведение:
- При незакрытых зависимостях бросает `ex-info` с
  `:reason :missing-required-providers` и картой `:missing`.

## 5. Рекомендуемый порядок инициализации

1. Приложение создает `registry` через `make-registry`.
2. Модули-владельцы регистрируют providers через `register-provider!`.
3. Модули-потребители объявляют зависимости через `declare-requirements!`.
4. Приложение вызывает `assert-requirements!`.
5. Только после успешной проверки стартует HTTP-сервер.

Boundary rules:

1. providers используются только для read-only доступа;
2. provider не должен выполнять side effects;
3. каскадные provider-вызовы provider -> provider запрещены;
4. missing required provider должен обнаруживаться до старта HTTP;
5. registry не заменяет event-bus и не предназначен для реактивных процессов.

## 6. Обычная картина использования

Если смотреть не на отдельные функции, а на живое приложение, то обычная схема
выглядит так:

1. приложение создает один общий registry;
2. модули-владельцы данных регистрируют свои провайдеры;
3. модуль-потребитель объявляет обязательные зависимости;
4. корень композиции вызывает `assert-requirements!` до старта HTTP;
5. провайдер затем используется либо в модуле-потребителе, либо в обработчике
   уровня приложения.

Именно эта картина и должна оставаться в голове при чтении API ниже.

## 7. Минимальный сквозной пример

```clojure
(ns app.main
  (:require [lcmm.read-provider-registry :as rpr]
            [lcmm.router :as router]))

(def registry (rpr/make-registry))

;; модуль accounts
(rpr/register-provider! registry
  {:provider-id :accounts/get-user-by-id
   :module :accounts
   :provider-fn (fn [{:keys [user-id]}]
                  (when (= user-id "u-alice")
                    {:id "u-alice" :login "alice"}))})

(rpr/register-provider! registry
  {:provider-id :accounts/get-user-by-login
   :module :accounts
   :provider-fn (fn [{:keys [login]}]
                  (when (= login "alice")
                    {:id "u-alice" :login "alice"}))})

;; модуль catalog
(rpr/register-provider! registry
  {:provider-id :catalog/get-slot-by-id
   :module :catalog
   :provider-fn (fn [{:keys [slot-id]}]
                  (when (= slot-id "slot-09-00")
                    {:id slot-id :status "open"}))})

;; модуль booking
(rpr/declare-requirements! registry
  :booking
  #{:accounts/get-user-by-id :catalog/get-slot-by-id})

;; проверка на старте в корне композиции приложения
(rpr/assert-requirements! registry)

;; обработчик уровня приложения
(defn install-security-routes! [app-router]
  (let [get-user-by-login (rpr/require-provider registry :accounts/get-user-by-login)]
    (router/add-route! app-router
                       :get "/auth/demo-login"
                       (fn [req]
                          (if-let [user (get-user-by-login {:login (get-in req [:query-params "login"])})]
                            {:status 200 :body (pr-str user)}
                            {:status 401 :body "invalid credentials"})))))
```

Что показывает этот пример:

1. один модуль может регистрировать несколько провайдеров;
2. провайдеры могут принадлежать разным модулям-владельцам;
3. модуль-потребитель объявляет именно те `provider_id`, которые ему нужны;
4. `assert-requirements!` живет в приложении и вызывается до старта HTTP;
5. приложение тоже может использовать провайдер напрямую, если это сценарий
   уровня приложения, как в `demo-login`.

## 8. Связь с контрактами модулей

`REGISTRY.md` описывает программный интерфейс.
Как фиксировать ожидания по провайдерам в YAML-контрактах модулей, описано в
[`CONTRACT.md`](./CONTRACT.md), в разделе `read_providers`.

Практическое правило:

1. `provider_id` в коде и контракте должен совпадать;
2. форма входа и результата не должна расходиться между кодом и YAML;
3. если поведение провайдера меняется, код и контракт обновляются вместе.
