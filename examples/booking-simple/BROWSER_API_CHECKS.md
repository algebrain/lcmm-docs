# Ручная проверка `booking-simple`

Соседние документы:

- обзор примера: [README.md](./README.md)
- справочник маршрутов: [ENDPOINTS.md](./ENDPOINTS.md)

## Подготовка

Запустите сервер:

```bash
bb start.bb
```

Если порт не менялся, дальше используется:

```text
http://localhost:3006
```

## 1. Проверка стартового состояния

Откройте:

- `http://localhost:3006/booking/list`
- `http://localhost:3006/notify/list`
- `http://localhost:3006/audit/list`

Ожидаемо:

- список броней пустой или содержит только уже накопленные локальные данные, если вы не начинали с чистого состояния;
- список уведомлений соответствует бронированиям;
- аудит показывает уже накопленные события.

Для канонического прохождения сценария лучше запускать пример на чистой локальной базе.

## 2. Проверка успешного создания брони

Откройте:

```text
http://localhost:3006/booking/create?slot=10:00&name=Ann
```

Ожидаемо:

- HTTP `200`;
- строка вида `BOOKING CREATED id=...`.

Сохраните `id` из ответа.

## 3. Проверка, что данные появились в чтении

Откройте:

- `http://localhost:3006/booking/list`
- `http://localhost:3006/notify/list`
- `http://localhost:3006/audit/list`

Проверьте:

- в списке броней появилась новая запись со слотом `10:00`;
- в списке уведомлений появилась запись для новой брони;
- в аудите есть как минимум события:
  - `booking/create-requested`
  - `booking/created`
  - `notify/booking-created`

## 4. Проверка конфликта по занятому слоту

Откройте:

```text
http://localhost:3006/booking/create?slot=10:00&name=Bob
```

Ожидаемо:

- HTTP `409`;
- строка `BOOKING REJECTED reason=slot-taken`.

После этого снова откройте:

```text
http://localhost:3006/audit/list
```

Проверьте, что появился `booking/rejected`.

## 5. Проверка чтения по id

Подставьте `BOOKING_ID`, который получили на шаге 2:

```text
http://localhost:3006/booking/get?id=BOOKING_ID
```

Ожидаемо:

- HTTP `200`;
- EDN-карта брони;
- `:id` совпадает с `BOOKING_ID`.
