# Документация: Паттерн Сага

Этот документ описывает стандартный подход к реализации паттерна "Сага" для управления распределенными транзакциями в системе. Саги позволяют поддерживать консистентность данных между несколькими модулями без использования блокирующих 2PC (двухфазных) транзакций.

## 1. Основная Концепция

**Сага** — это последовательность локальных транзакций, где каждая транзакция обновляет данные в рамках одного модуля и публикует событие, которое запускает следующую транзакцию. Если какой-либо шаг терпит неудачу, сага выполняет **компенсирующие транзакции**, которые отменяют результаты предыдущих успешных шагов.

## 2. Архитектура Саги в Системе

Наша реализация саг основана на четырех ключевых компонентах:

1.  **Определение Саги (Saga Definition):** Декларативная структура данных (карта Clojure), описывающая стейт-машину саги.
2.  **Менеджер Саг (Saga Manager):** Сервис, который исполняет логику саги на основе ее определения, управляет ее жизненным циклом и отвечает за персистентность.
3.  **Хранилище Состояний (State Store):** Таблица в базе данных для хранения состояния каждого активного экземпляра саги.
4.  **Шина Событий (`event-bus`):** Весь обмен сообщениями между сагой и модулями системы происходит через `event-bus`.

## 3. Подробный Пример: Сага Создания Заказа

Рассмотрим полный пример саги для обработки нового заказа.

### 3.1. Бизнес-процесс
Когда пользователь создает заказ, система должна выполнить два действия:
1.  Списать заказанные товары со склада (модуль `inventory`).
2.  Снять оплату с клиента (модуль `billing`).

Оба шага должны завершиться успешно. Если оплата не проходит после того, как товар был списан, списание должно быть отменено (компенсировано), чтобы товар вернулся на склад.

### 3.2. Определение Саги
Сначала мы декларативно описываем всю логику в виде данных.

```clojure
;; в файле, например, order_processing/saga.clj

(def saga-type :order-processing/create-order)

(def definition
  {::steps
   [;; ШАГ 1: Работа со складом
    {:name           :debit-stock
     :command        :inventory/debit-stock  ; Что отправить
     :on-success     :inventory/stock-debited  ; Чего ждать при успехе
     :on-failure     :inventory/debit-failed   ; Чего ждать при неудаче
     :compensation   :inventory/return-stock} ; Команда для отката этого шага

    ;; ШАГ 2: Работа с биллингом
    {:name           :charge-customer
     :command        :billing/charge-customer
     :on-success     :billing/customer-charged
     :on-failure     :billing/charge-failed
     :compensation   :billing/refund-customer}]})
```

### 3.3. Запуск Саги (Модуль `orders`)
Сага стартует в модуле `orders`, который, например, обрабатывает HTTP-запрос на создание заказа.

```clojure
;; в модуле orders

(defn- handle-create-order
  [saga-manager request]
  (let [order-data (:body request)]
    ;; Запускаем сагу и немедленно отвечаем пользователю.
    ;; Дальнейшая обработка заказа пойдет асинхронно.
    (saga-manager/start-saga! saga-type order-data)
    {:status 202 :body {:message "Order processing started."}}))

(defn init! [{:keys [router saga-manager]}]
  (router/add-route! router :post "/orders" (partial handle-create-order saga-manager)))
```

### 3.4. Участие в Саге (Модуль `inventory`)
Модуль `inventory` выполняет свою часть работы, ничего не зная о саге в целом. Он просто реагирует на команды.

```clojure
;; в модуле inventory

;; Обработчик команды на списание
(defn- handle-debit-stock [bus envelope]
  (let [{:keys [items saga-id]} (:payload envelope)] ; Извлекаем saga-id
    (if (internal/try-debit-stock! items)
      ;; Успех: публикуем ответ, сохранив saga-id
      (bus/publish bus :inventory/stock-debited {:saga-id saga-id} {:parent-envelope envelope})
      ;; Неудача: публикуем ответ, сохранив saga-id
      (bus/publish bus :inventory/debit-failed {:saga-id saga-id, :reason "Not enough stock"} {:parent-envelope envelope}))))

;; Обработчик компенсирующей команды
(defn- handle-return-stock [bus envelope]
    (let [{:keys [items saga-id]} (:payload envelope)]
      (internal/return-stock! items)
      ;; Компенсирующие действия обычно не требуют ответа
      (println "COMPENSATION: Stock returned for saga" saga-id)))

(defn init! [{:keys [bus]}]
  ;; Подписываемся на команды от саги (или любого другого источника)
  (bus/subscribe bus :inventory/debit-stock handle-debit-stock)
  (bus/subscribe bus :inventory/return-stock handle-return-stock))
```

### 3.5. Участие в Саге (Модуль `billing`)
Модуль `billing` работает аналогично `inventory`.

```clojure
;; в модуле billing

(defn- handle-charge-customer [bus envelope]
  (let [{:keys [customer-id amount saga-id]} (:payload envelope)]
    (if (internal/try-charge! customer-id amount)
      (bus/publish bus :billing/customer-charged {:saga-id saga-id} {:parent-envelope envelope})
      (bus/publish bus :billing/charge-failed {:saga-id saga-id, :reason "Payment declined"} {:parent-envelope envelope}))))

(defn init! [{:keys [bus]}]
  (bus/subscribe bus :billing/charge-customer handle-charge-customer))
```

### 3.6. Что делает Менеджер Саг "за кадром"
*   **Счастливый путь**:
    1.  Получает ответ `:inventory/stock-debited`.
    2.  Находит сагу по `saga-id`.
    3.  Видит, что шаг `:debit-stock` успешен.
    4.  Смотрит в определение и видит, что следующий шаг — `:charge-customer`.
    5.  Публикует команду `:billing/charge-customer`.
    6.  Получает ответ `:billing/customer-charged`, видит, что это последний шаг, и помечает сагу как `:completed`.
*   **Путь с компенсацией**:
    1.  Получает ответ `:inventory/stock-debited`.
    2.  Публикует команду `:billing/charge-customer`.
    3.  Получает ответ **`:billing/charge-failed`**.
    4.  Находит сагу по `saga-id`, видит, что шаг `:charge-customer` провалился.
    5.  Начинает откат: смотрит на все *успешно выполненные* шаги (в нашем случае это `:debit-stock`).
    6.  Находит у шага `:debit-stock` ключ `:compensation` и публикует команду **`:inventory/return-stock`**.
    7.  Помечает сагу как `:compensated`.

## 4. Восстановление после сбоев

Благодаря хранению состояния в БД, система устойчива к перезапускам. При старте приложения Менеджер Саг:
1.  Находит в таблице `saga_instances` все саги, которые не были завершены (`:running` или `:awaiting-response`).
2.  Для каждой такой саги он возобновляет логику ожидания (например, заново подписывается на `on-success`/`on-failure` события текущего шага). Ему не нужно заново отправлять команду, так как паттерн Outbox уже гарантировал ее отправку до сбоя.
