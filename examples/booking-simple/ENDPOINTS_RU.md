# Проверка `example2` через браузер

Документ описывает все GET-endpoint'ы demo-сервера и простой сценарий ручной проверки.

## Базовый адрес

- `http://localhost:3006`

Порт берется из `resources/booking_config.toml` (`http.port`).

## Endpoint'ы

1. Создать бронь  
`GET /booking/create?slot=<slot>&name=<name>`

Пример:  
`http://localhost:3006/booking/create?slot=10:00&name=Ann`

Ожидаемо:
- `200` и строка `BOOKING CREATED id=...` при успехе.
- `409` и строка `BOOKING REJECTED reason=slot-taken`, если слот уже занят.

2. Получить бронь по id  
`GET /booking/get?id=<booking-id>`

Пример:  
`http://localhost:3006/booking/get?id=<id-из-create>`

Ожидаемо:
- `200` и EDN-карта брони.
- `404`, если такой брони нет.

3. Список броней  
`GET /booking/list`

Пример:  
`http://localhost:3006/booking/list`

Ожидаемо: EDN-список всех броней.

4. Список уведомлений  
`GET /notify/list`

Пример:  
`http://localhost:3006/notify/list`

Ожидаемо: EDN-список уведомлений, которые реактивно создал модуль `notify`.

5. Аудит событий  
`GET /audit/list`

Пример:  
`http://localhost:3006/audit/list`

Ожидаемо: EDN-список записей аудита (`event_type`, `booking_id`, `details`).

## Пошаговый сценарий проверки

1. Запустите сервер:

```bash
cd example2
clj -M:run-main
```

2. Создайте первую бронь в браузере:

`http://localhost:3006/booking/create?slot=10:00&name=Ann`

3. Откройте список броней:

`http://localhost:3006/booking/list`

Убедитесь, что бронь появилась.

4. Откройте список уведомлений:

`http://localhost:3006/notify/list`

Убедитесь, что появилось уведомление для этой брони.

5. Откройте аудит:

`http://localhost:3006/audit/list`

Проверьте, что есть события как минимум:
- `booking/create-requested`
- `booking/created`
- `notify/booking-created`

6. Проверьте конфликт (занятый слот):

`http://localhost:3006/booking/create?slot=10:00&name=Bob`

Ожидаемо: ответ `BOOKING REJECTED reason=slot-taken`.

7. Снова откройте аудит:

`http://localhost:3006/audit/list`

Проверьте, что добавилось событие `booking/rejected`.

## Что важно

- Все endpoint'ы специально сделаны через `GET`, чтобы проверять систему прямо из адресной строки.
- Конфиг по умолчанию берется из TOML, env-переменные не обязательны.
