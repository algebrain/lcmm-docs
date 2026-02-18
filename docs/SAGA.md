# Документация: Паттерн Сага

Этот документ описывает стандартный подход к реализации паттерна "Сага" для управления распределенными транзакциями в системе. Саги позволяют поддерживать консистентность данных между несколькими модулями без использования блокирующих 2PC (двухфазных) транзакций.

## 1. Основная Концепция

**Сага** — это последовательность локальных транзакций, где каждая транзакция обновляет данные в рамках одного модуля и публикует событие, которое запускает следующую транзакцию. Если какой-либо шаг терпит неудачу, сага выполняет **компенсирующие транзакции**, которые отменяют результаты предыдущих успешных шагов.

## 2. Сага — обычный модуль LCMM

В LCMM **сага — это такой же модуль, как и остальные**. На этом уровне нет отдельных сущностей вроде "менеджеров" или "движков" саг: есть модули, `event-bus`, `router` и `logger`.

Ключевые свойства:
*   Сага запускается **через событие** в `event-bus`, а не через прямой вызов другого компонента.
*   Вся коммуникация с другими модулями **только через события**.
*   Состояние саги хранится внутри модуля (в памяти или в БД, как его зависимость), и обновляется на события.

## 3. Пример: Сага создания заказа как модуль

Ниже показан минимальный пример модуля-саги. Он:
1.  принимает событие старта;
2.  публикует команды шагов;
3.  реагирует на ответы;
4.  запускает компенсацию при ошибке.

### 3.1. Определение шагов
Определение можно хранить как данные, это делает логику прозрачной.

```clojure
;; order_processing/saga.clj

(def saga-type :order-processing/create-order)

(def definition
  {::steps
   [;; ШАГ 1: Работа со складом
    {:name         :debit-stock
     :command      :inventory/debit-stock
     :on-success   :inventory/stock-debited
     :on-failure   :inventory/debit-failed
     :compensation :inventory/return-stock}

    ;; ШАГ 2: Работа с биллингом
    {:name         :charge-customer
     :command      :billing/charge-customer
     :on-success   :billing/customer-charged
     :on-failure   :billing/charge-failed
     :compensation :billing/refund-customer}]})
```

### 3.2. Модуль саги

```clojure
;; order_processing/module.clj
(ns order-processing.module
  (:require [event-bus :as bus]))

(defn- publish-command [bus step payload parent-envelope]
  (bus/publish bus (:command step) payload {:parent-envelope parent-envelope}))

(defn- publish-compensation [bus step payload parent-envelope]
  (when-let [comp (:compensation step)]
    (bus/publish bus comp payload {:parent-envelope parent-envelope})))

(defn- handle-start
  [bus logger definition envelope]
  (let [payload (:payload envelope)
        first-step (first (::steps definition))]
    (logger :info {:component ::saga, :event :saga-started, :saga-type saga-type})
    (publish-command bus first-step payload envelope)))

(defn- handle-step-result
  [bus logger definition envelope]
  (let [event-type (:event-type envelope)
        payload (:payload envelope)
        steps (::steps definition)
        idx (first (keep-indexed (fn [i s]
                                   (when (or (= event-type (:on-success s))
                                             (= event-type (:on-failure s)))
                                     i))
                                 steps))
        step (nth steps idx)
        success? (= event-type (:on-success step))]
    (if success?
      (if-let [next-step (nth steps (inc idx) nil)]
        (do
          (logger :info {:component ::saga, :event :step-succeeded, :step (:name step)})
          (publish-command bus next-step payload envelope))
        (logger :info {:component ::saga, :event :saga-completed, :saga-type saga-type}))
      (do
        (logger :warn {:component ::saga, :event :step-failed, :step (:name step)})
        (publish-compensation bus step payload envelope)
        (logger :info {:component ::saga, :event :saga-compensated, :saga-type saga-type})))))

(defn init!
  "Модуль саги. Подписывается на старт и ответы шагов."
  [{:keys [bus logger]}]
  ;; старт саги через событие
  (bus/subscribe bus :order/create-requested
                 (fn [bus envelope]
                   (handle-start bus logger definition envelope)))

  ;; ответы шагов
  (doseq [step (::steps definition)
          event-type [(:on-success step) (:on-failure step)]]
    (bus/subscribe bus event-type
                   (fn [bus envelope]
                     (handle-step-result bus logger definition envelope))))))
```

### 3.3. Запуск саги через событие

Модуль, принимающий HTTP-запрос, **не** вызывает сагу напрямую. Он публикует событие старта.

```clojure
;; в модуле orders
(defn- handle-create-order
  [bus request]
  (let [order-data (:body request)]
    (bus/publish bus :order/create-requested order-data)
    {:status 202 :body {:message "Order processing started."}}))

(defn init! [{:keys [router bus]}]
  (router/add-route! router :post "/orders" (partial handle-create-order bus)))
```

## 4. Восстановление после сбоев

Состояние саги хранится в модуле (например, в БД). При рестарте модуль считывает незавершенные экземпляры и продолжает обработку, ожидая события `on-success`/`on-failure` текущего шага. Повторная отправка команд не нужна, если доставка команд уже гарантирована (например, через outbox).
