# Спецификация API: Read-Provider Registry

Версия: `1.0-draft`

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

## 6. Минимальный end-to-end пример

```clojure
(ns app.main
  (:require [lcmm.read-provider-registry :as rpr]))

(def registry (rpr/make-registry))

;; users module (provider owner)
(rpr/register-provider! registry
  {:provider-id :users/get-user-by-id
   :module :users
   :provider-fn (fn [{:keys [user-id]}]
                  {:ok? true :value {:id user-id}})})

;; orders module (consumer)
(rpr/declare-requirements! registry :orders #{:users/get-user-by-id})
(def get-user (rpr/require-provider registry :users/get-user-by-id))

;; app startup-check
(rpr/assert-requirements! registry)
```
