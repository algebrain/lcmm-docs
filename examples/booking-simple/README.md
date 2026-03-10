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

Рекомендуемый способ:

```bash
bb start.bb
```

По умолчанию сервер поднимается на `http://localhost:3006`.

Запасной технический способ:

```bash
clj -M:run-main
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
