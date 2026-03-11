# `booking-simple`

Соседние документы:

- справочник маршрутов: [ENDPOINTS.md](./ENDPOINTS.md)
- пошаговая ручная проверка: [BROWSER_API_CHECKS.md](./BROWSER_API_CHECKS.md)
- контракты модулей: [contracts/](./contracts/)

`booking-simple` показывает минимальный реактивный поток на LCMM:

- `booking` создает и читает бронирования;
- `notify` реактивно создает уведомления после успешной брони;
- `audit` накапливает события потока.

Это один процесс с простым browser-first HTTP API. Здесь нет app-level инфраструктуры из полного примера вроде `healthz`, `readyz`, `metrics`, guard или composition root с отдельными подпроектами.

## Запуск

Рекомендуемый способ для чистого локального состояния:

```bash
bb start.bb --reset
```

По умолчанию сервер поднимается на `http://localhost:3006`.

Если нужно сохранить локальные SQLite-данные между перезапусками, используйте:

```bash
bb start.bb --continue
```

Если порт уже занят другим процессом, запуск завершится с понятной ошибкой до очистки SQLite-файлов.
Для такого случая можно либо остановить старый процесс, либо выбрать другой порт:

```bash
bb start.bb --reset --port=3007
```

Запасной технический способ:

```bash
clj -M:run-main -- --reset
```

Конфиг по умолчанию берется из `resources/booking_config.toml`.

## Тесты

```bash
bb test.bb
```

## Что смотреть дальше

- [ENDPOINTS.md](./ENDPOINTS.md) для полного списка маршрутов;
- [BROWSER_API_CHECKS.md](./BROWSER_API_CHECKS.md) для ручной проверки через браузер;
- [contracts/](./contracts/) для контрактов модулей.
