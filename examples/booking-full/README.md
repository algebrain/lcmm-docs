# `booking-full`

Соседние документы:

- справочник маршрутов: [ENDPOINTS.md](./ENDPOINTS.md)
- пошаговая ручная проверка: [BROWSER_API_CHECKS.md](./BROWSER_API_CHECKS.md)

`booking-full` показывает монолитное приложение, собранное из модулей на основе архитектуры LCMM.

Структура примера такая:

- [app/](./app/) — приложение-сервер HTTP API, содержит startup mode, guard, `healthz`, `readyz`, `metrics` и общей wiring-логикой;
- [modules/accounts/](./modules/accounts/) — модуль пользователей и ролей;
- [modules/catalog/](./modules/catalog/) — модуль слотов;
- [modules/booking/](./modules/booking/) — модуль бронирования;
- [modules/notify/](./modules/notify/) — модуль уведомлений;
- [modules/audit/](./modules/audit/) — модуль аудита событий.

Корневые документы нужны для общего входа в собранное приложение. Техническая документация подпроектов остается внутри `app/` и `modules/*`.

## Запуск

Рекомендуемый способ:

```bash
bb start.bb
```

Полезные варианты:

```bash
bb start.bb --reset
bb start.bb --continue
bb start.bb --reset --port=3010
```

По умолчанию сервер поднимается на `http://localhost:3006`.

## Веб-сокет в примере

`booking-full` сохраняет browser-first HTTP-проверяемость из адресной строки.

Для ручной проверки веб-сокета в пример добавлена отдельная встроенная страница:

- `http://localhost:3006/ws-demo`

Эта страница нужна только для ручной проверки transport flow:

1. открыть соединение;
2. подписаться на события пользователя;
3. увидеть push при создании бронирования;
4. проверить переподключение и повторное чтение состояния.

Это не отдельное фронтенд-приложение и не замена обычным HTTP-проверкам.

## Тесты

Приложение-компоновщик:

```bash
cd app
bb test.bb
```

Отдельный модуль:

```bash
cd modules/<module>
bb test.bb
```

## Как читать этот пример

Если нужен общий пользовательский сценарий, начните с:

- [ENDPOINTS.md](./ENDPOINTS.md)
- [BROWSER_API_CHECKS.md](./BROWSER_API_CHECKS.md)

Если нужна архитектура и технические детали подпроектов, переходите отдельно в:

- [app/README.md](./app/README.md)
- [modules/accounts/README.md](./modules/accounts/README.md)
- [modules/catalog/README.md](./modules/catalog/README.md)

У модулей `booking`, `notify` и `audit` сейчас основной технический источник истины лежит в коде, тестах и `contracts/`.
