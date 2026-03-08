# reference-app/app

Корень композиции для текущего `reference-app`.

Это эталонный локальный сервер, который должен быть пригоден для ручной проверки
HTTP API из адресной строки браузера.

## Что делает приложение

1. Загружает конфиг через `lcmm-configure`.
2. Собирает `event-bus`, `router`, `read-provider registry`, guard и наблюдаемость.
3. Инициализирует модули `accounts`, `catalog`, `booking`, `notify`, `audit`.
4. Поднимает browser-first маршруты, доступные через `GET`.

## Режимы запуска

Основная точка запуска:

```bash
bb start.bb
```

Поддерживаются два режима:

1. `--reset`
   Очищает локальное demo-состояние и запускает сервер в каноническом эталонном виде.
   Это режим по умолчанию.
2. `--continue`
   Сохраняет существующие локальные SQLite-файлы и продолжает работу с уже
   накопленным состоянием.

Дополнительно можно передать:

1. `--port=<N>`
   Переопределяет HTTP-порт.

Примеры:

```bash
bb start.bb
bb start.bb --reset
bb start.bb --continue
bb start.bb --reset --port=3010
```

Во время запуска `start.bb` заранее показывает:

1. текущее время;
2. выбранный режим;
3. порт;
4. пути к локальным SQLite-файлам;
5. будет ли состояние очищено перед стартом.

Сервер корректно завершается по `Ctrl+C`.

## Стартовое состояние в режиме `--reset`

После запуска в режиме `--reset` приложение приходит к одному и тому же
начальному состоянию:

1. в `accounts` существуют demo-пользователи `u-admin` и `u-alice`;
2. в `catalog` существуют demo-слоты `slot-09-00`, `slot-10-00`, `slot-11-00`;
3. `booking`, `notify` и `audit` стартуют с пустыми списками записей.

Именно на это состояние должен опираться следующий этап с браузерными
проверками API.

## Полезные эндпойнты

Приложение публикует, в частности, такие маршруты:

1. `GET /healthz`
2. `GET /readyz`
3. `GET /metrics`
4. `GET /accounts/me?user-id=u-alice`
5. `GET /accounts/users/u-admin`
6. `GET /catalog/slots`
7. `GET /catalog/slots?status=open`
8. `GET /catalog/slots/slot-09-00`
9. `GET /bookings/actions/create?slot-id=slot-09-00&user-id=u-alice`
10. `GET /bookings`
11. `GET /notifications`
12. `GET /audit`
13. `GET /auth/demo-login?login=alice`
14. `GET /ops/guard/unban?ip=203.0.113.10`

Подробная последовательность ручных проверок описана отдельным документом
в [BROWSER_API_CHECKS.md](./BROWSER_API_CHECKS.md).

## Запуск без `start.bb`

Если нужно запустить приложение напрямую:

```bash
clj -M:run-main -- --reset
clj -M:run-main -- --continue
```

## Тесты

```bash
clj -M:test
```
