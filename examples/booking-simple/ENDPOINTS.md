# Маршруты `booking-simple`

Соседние документы:

- обзор примера: [README.md](./README.md)
- пошаговая ручная проверка: [BROWSER_API_CHECKS.md](./BROWSER_API_CHECKS.md)

Базовый адрес по умолчанию:

- `http://localhost:3006`

Все маршруты специально сделаны через `GET`, чтобы их можно было открывать прямо из адресной строки браузера.

## `GET /booking/create?slot=<slot>&name=<name>`

Назначение: создать бронь.

Параметры:

- `slot` обязателен;
- `name` обязателен.

Пример:

- `http://localhost:3006/booking/create?slot=10:00&name=Ann`

Ожидаемые статусы:

- `200` при успехе, в ответе строка вида `BOOKING CREATED id=...`;
- `409`, если слот уже занят, в ответе строка вида `BOOKING REJECTED reason=slot-taken`.

## `GET /booking/get?id=<booking-id>`

Назначение: получить бронь по id.

Параметры:

- `id` обязателен.

Пример:

- `http://localhost:3006/booking/get?id=<booking-id>`

Ожидаемые статусы:

- `200` и EDN-карта брони;
- `404`, если такой брони нет.

## `GET /booking/list`

Назначение: получить список всех броней.

Пример:

- `http://localhost:3006/booking/list`

Ожидаемые статусы:

- `200` и EDN-список бронирований.

## `GET /notify/list`

Назначение: получить список уведомлений, которые реактивно создает модуль `notify`.

Пример:

- `http://localhost:3006/notify/list`

Ожидаемые статусы:

- `200` и EDN-список уведомлений.

## `GET /audit/list`

Назначение: получить накопленный аудит событий.

Пример:

- `http://localhost:3006/audit/list`

Ожидаемые статусы:

- `200` и EDN-список записей аудита с полями вроде `event_type`, `booking_id`, `details`.

## Что удобно проверять прямо из браузера

Все маршруты подходят для ручной проверки из адресной строки, но чаще всего в таком порядке:

- сначала `GET /booking/list`, `GET /notify/list`, `GET /audit/list`;
- затем `GET /booking/create?...`;
- потом повторно `GET /booking/list`, `GET /notify/list`, `GET /audit/list`;
- в конце `GET /booking/get?id=...`.
