# Маршруты `booking-full`

Соседние документы:

- обзор примера: [README.md](./README.md)
- пошаговая ручная проверка: [BROWSER_API_CHECKS.md](./BROWSER_API_CHECKS.md)

Базовый адрес по умолчанию:

- `http://localhost:3006`

Ниже маршруты сгруппированы по их источнику в коде:

- часть маршрутов определяет само приложение `app`;
- часть маршрутов добавляют модули.

Снаружи все эти маршруты публикует одно собранное приложение.

## Маршруты проекта `app`

### `GET /healthz`

Назначение: быстрая проверка, что процесс жив.

Пример:

- `http://localhost:3006/healthz`

Ожидаемо:

- `200`;
- EDN-ответ со статусом `ok`.

### `GET /readyz`

Назначение: проверка готовности приложения-компоновщика и обязательных read-provider'ов.

Пример:

- `http://localhost:3006/readyz`

Ожидаемо:

- `200`;
- EDN-ответ со статусом `ok`;
- в `:checks` есть проверка `:read-providers`.

### `GET /metrics`

Назначение: отдать Prometheus-метрики приложения.

Пример:

- `http://localhost:3006/metrics`

Ожидаемо:

- `200`;
- текстовый ответ;
- в ответе встречается `http_server_requests_total`.

### `GET /auth/demo-login?login=<login>`

Назначение: логин через guard и read-provider `accounts/get-user-by-login`.

Пример:

- `http://localhost:3006/auth/demo-login?login=alice`

Ожидаемые статусы:

- `200` при успешном входе;
- `401`, если логин неизвестен;
- `429`, если guard уже заблокировал IP.

### `GET /ops/guard/unban?ip=<ip>`

Назначение: снять блокировку guard для IP.

Пример:

- `http://localhost:3006/ops/guard/unban?ip=203.0.113.10`

Ожидаемо:

- `200`;
- EDN-ответ с `:ok true`.

## Маршруты модуля `accounts`

### `GET /accounts/me?user-id=<user-id>`

Назначение: получить пользователя по query-параметру `user-id`.

Пример:

- `http://localhost:3006/accounts/me?user-id=u-alice`

Ожидаемые статусы:

- `200`, если пользователь найден;
- `400`, если `user-id` не передан;
- `404`, если пользователь не найден.

### `GET /accounts/users/<user-id>`

Назначение: получить пользователя по id из пути.

Пример:

- `http://localhost:3006/accounts/users/u-admin`

Ожидаемые статусы:

- `200`, если пользователь найден;
- `404`, если пользователь не найден.

## Маршруты модуля `catalog`

### `GET /catalog/slots`

Назначение: получить список слотов.

Примеры:

- `http://localhost:3006/catalog/slots`
- `http://localhost:3006/catalog/slots?status=open`

Ожидаемо:

- `200`;
- EDN-список слотов;
- при `status=open` остаются только открытые слоты.

### `GET /catalog/slots/<slot-id>`

Назначение: получить слот по id.

Пример:

- `http://localhost:3006/catalog/slots/slot-09-00`

Ожидаемые статусы:

- `200`, если слот найден;
- `404`, если слот не найден.

## Маршруты модуля `booking`

### `GET /bookings/actions/create?slot-id=<slot-id>&user-id=<user-id>`

Назначение: создать бронь.

Пример:

- `http://localhost:3006/bookings/actions/create?slot-id=slot-09-00&user-id=u-alice`

Ожидаемые статусы:

- `200`, если бронь создана;
- `400`, если не передан `slot-id` или `user-id`;
- `404`, если пользователь или слот не найдены;
- `409`, если слот не открыт.

### `GET /bookings`

Назначение: получить список броней.

Примеры:

- `http://localhost:3006/bookings`
- `http://localhost:3006/bookings?user-id=u-alice`

Ожидаемо:

- `200`;
- EDN-список броней;
- при `user-id=...` список фильтруется по пользователю.

### `GET /bookings/<booking-id>`

Назначение: получить бронь по id.

Пример:

- `http://localhost:3006/bookings/<booking-id>`

Ожидаемые статусы:

- `200`, если бронь найдена;
- `404`, если брони нет.

## Маршрут модуля `notify`

### `GET /notifications`

Назначение: получить список реактивно созданных уведомлений.

Пример:

- `http://localhost:3006/notifications`

Ожидаемо:

- `200`;
- EDN-список уведомлений.

## Маршрут модуля `audit`

### `GET /audit`

Назначение: получить аудит событий потока бронирования.

Пример:

- `http://localhost:3006/audit`

Ожидаемо:

- `200`;
- EDN-список записей аудита.
