# Ручная проверка `booking-full`

Соседние документы:

- обзор примера: [README.md](./README.md)
- справочник маршрутов: [ENDPOINTS.md](./ENDPOINTS.md)

Этот документ описывает внешний сценарий ручной проверки собранного приложения. Технические детали проекта-компоновщика остаются в [app/README.md](./app/README.md).

## Подготовка

Запустите пример из корня:

```bash
bb start.bb --reset
```

Если порт не переопределялся, дальше используется:

```text
http://localhost:3006
```

Режим `--reset` важен, потому что он возвращает пример к каноническому стартовому состоянию.

## 1. Проверка app-level endpoint'ов

Откройте:

- `http://localhost:3006/healthz`
- `http://localhost:3006/readyz`
- `http://localhost:3006/metrics`

Проверьте:

- `healthz` отвечает `200` и показывает статус `ok`;
- `readyz` отвечает `200` и показывает успешную проверку `read-providers`;
- `metrics` отвечает `200`, а в тексте есть `http_server_requests_total`.

## 2. Проверка seeded data в `accounts` и `catalog`

Откройте:

- `http://localhost:3006/accounts/me?user-id=u-alice`
- `http://localhost:3006/accounts/users/u-admin`
- `http://localhost:3006/catalog/slots`
- `http://localhost:3006/catalog/slots?status=open`
- `http://localhost:3006/catalog/slots/slot-09-00`

Проверьте:

- пользователь `u-alice` существует и отдается без поля `:password`;
- пользователь `u-admin` существует;
- в каталоге есть `slot-09-00`, `slot-10-00`, `slot-11-00`;
- открытые слоты это `slot-09-00` и `slot-10-00`;
- `slot-09-00` имеет статус `"open"`.

## 3. Проверка пустого стартового состояния для side effects

Откройте:

- `http://localhost:3006/bookings`
- `http://localhost:3006/notifications`
- `http://localhost:3006/audit`

Проверьте:

- списки пустые: `[]`.

## 4. Проверка успешного создания бронирования

Откройте:

```text
http://localhost:3006/bookings/actions/create?slot-id=slot-09-00&user-id=u-alice
```

Проверьте:

- HTTP `200`;
- в ответе есть `:id`;
- `:slot_id` равен `"slot-09-00"`;
- `:user_id` равен `"u-alice"`;
- `:status` равен `"created"`.

Сохраните `BOOKING_ID` из ответа.

Затем откройте:

- `http://localhost:3006/bookings/BOOKING_ID`
- `http://localhost:3006/bookings`
- `http://localhost:3006/bookings?user-id=u-alice`

Проверьте:

- чтение по id возвращает ту же бронь;
- в общем списке одна бронь;
- в списке пользователя тоже одна бронь.

## 5. Проверка реактивных последствий

Откройте:

- `http://localhost:3006/notifications`
- `http://localhost:3006/audit`

Проверьте:

- в `notifications` появилась одна запись с тем же `:booking_id`;
- в `audit` есть как минимум:
  - `"booking/create-requested"`
  - `"booking/created"`
  - `"notify/booking-created"`

## 6. Отрицательные сценарии `booking`

Проверьте по очереди:

- `http://localhost:3006/bookings/actions/create?user-id=u-alice`
- `http://localhost:3006/bookings/actions/create?slot-id=slot-09-00`
- `http://localhost:3006/bookings/actions/create?slot-id=slot-09-00&user-id=missing-user`
- `http://localhost:3006/bookings/actions/create?slot-id=missing-slot&user-id=u-alice`
- `http://localhost:3006/bookings/actions/create?slot-id=slot-11-00&user-id=u-alice`
- `http://localhost:3006/bookings/missing-booking`

Ожидаемо:

- `400` и `slot-id is required`;
- `400` и `user-id is required`;
- `404` и `user not found`;
- `404` и `slot not found`;
- `409` и `slot not open`;
- `404` и `booking not found`.

## 7. Guard-сценарий

Этот сценарий проходите **только после разделов 1-6**.

Причина простая: здесь пример намеренно банит IP после повторной ошибки логина.
После этого следующие запросы с того же IP начнут получать `429`, пока вы не снимете блокировку.

Этот сценарий удобнее проходить из одного и того же окна браузера, чтобы запросы шли с одного IP.

Проверьте по очереди:

- `http://localhost:3006/auth/demo-login?login=alice`
- `http://localhost:3006/auth/demo-login?login=missing`
- еще раз `http://localhost:3006/auth/demo-login?login=missing`
- `http://localhost:3006/auth/demo-login?login=alice`

Ожидаемо:

- первый успешный логин дает `200`;
- первая ошибка дает `401` и код `invalid_credentials`;
- повторная ошибка дает `429`;
- следующий даже успешный логин тоже дает `429`, потому что guard уже заблокировал IP.

Затем снимите блокировку:

```text
http://localhost:3006/ops/guard/unban?ip=127.0.0.1
```

Если ваш реальный клиентский IP другой, подставьте его.
Для локального запуска на этой машине practical default — `127.0.0.1`.

После этого снова откройте:

```text
http://localhost:3006/auth/demo-login?login=alice
```

Ожидаемо:

- `200`;
- успешный логин снова работает.

Если хотите прогнать весь документ повторно с самого начала, проще всего заново запустить:

```bash
bb start.bb --reset
```

## 8. Ручная проверка веб-сокета через встроенную страницу

Этот раздел не заменяет предыдущие HTTP-проверки.
Он нужен только для ручной проверки websocket-доставки в обычном браузере.

Откройте:

- `http://localhost:3006/ws-demo`

На странице:

1. выберите пользователя `u-alice`;
2. нажмите `Load Snapshot`;
3. нажмите `Connect`;
4. нажмите `Subscribe`;
5. нажмите `Create Booking`.

Проверьте:

1. в логе появляется `subscribed`;
2. затем появляется событие `booking/created`;
3. в сообщении есть `bookingId`, `userId` и `slotId`;
4. если закрыть соединение, страница делает повторное чтение `/bookings`.

Отдельно можно проверить отказ в подписке:

1. выберите `u-admin`;
2. нажмите `Connect`;
3. в DevTools Console выполните:

```js
wsDemo.send({ type: "subscribe", topic: ["user", "u-alice"] })
```

Ожидаемо:

1. в логе или в сообщении от сервера будет единый отказ
   `subscription_rejected`;
2. отказ не должен раскрывать, существует ли другая сущность или нет.
