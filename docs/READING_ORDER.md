# READING_ORDER

Этот файл — основная точка входа в документацию LCMM.

Здесь есть:

1. базовый порядок первого чтения;
2. короткие тематические маршруты под разные задачи;
3. полный список документов, чтобы ничего не потерялось.

Если вы читаете LCMM впервые, начинайте с базового порядка.
Если у вас уже есть конкретная задача, переходите к нужному тематическому маршруту ниже.

## 1. Базовый порядок первого чтения

Этот маршрут дает общую картину без лишних ответвлений.

1. [PRAGMATISM](./PRAGMATISM.md)
2. [ARCH](./ARCH.md)
3. [APP_COMPOSITION](./APP_COMPOSITION.md)
4. [MODULE](./MODULE.md)
5. [BUS](./BUS.md)
6. [ROUTER](./ROUTER.md)
7. [DATABASE](./DATABASE.md)
8. [REGISTRY](./REGISTRY.md)
9. [CONTRACT](./CONTRACT.md)
10. [CONFIGURE](./CONFIGURE.md)
11. [CONFIGURE_ADMIN](./CONFIGURE_ADMIN.md)
12. [LOGGING](./LOGGING.md)
13. [HTTP](./HTTP.md)
14. [TRACE_FLOW](./TRACE_FLOW.md)
15. [OBSERVABILITY](./OBSERVABILITY.md)
16. [OBSERVE_APP](./OBSERVE_APP.md)
17. [OBSERVE_MODULE](./OBSERVE_MODULE.md)
18. [SECURITY](./SECURITY.md)
19. [SECURE_APP](./SECURE_APP.md)
20. [SECURE_MODULE](./SECURE_MODULE.md)
21. [GUARD](./GUARD.md)
22. [GUARD_API](./GUARD_API.md)
23. [GUARD_BACKEND](./GUARD_BACKEND.md)
24. [HTTP_FRONTEND_NOTES](./HTTP_FRONTEND_NOTES.md)
25. [HYDRATION](./HYDRATION.md)
26. [SAGA](./SAGA.md)
27. [TRANSACT](./TRANSACT.md)

## 2. Тематические маршруты

### 2.1 Если вы собираете приложение целиком

Читайте так:

1. [PRAGMATISM](./PRAGMATISM.md)
2. [ARCH](./ARCH.md)
3. [APP_COMPOSITION](./APP_COMPOSITION.md)
4. [CONFIGURE](./CONFIGURE.md)
5. [CONFIGURE_ADMIN](./CONFIGURE_ADMIN.md)
6. [DATABASE](./DATABASE.md)
7. [ROUTER](./ROUTER.md)
8. [HTTP](./HTTP.md)
9. [OBSERVE_APP](./OBSERVE_APP.md)
10. [SECURE_APP](./SECURE_APP.md)
11. [GUARD](./GUARD.md)
12. [TRACE_FLOW](./TRACE_FLOW.md)

### 2.2 Если вы проектируете модуль

Читайте так:

1. [PRAGMATISM](./PRAGMATISM.md)
2. [ARCH](./ARCH.md)
3. [MODULE](./MODULE.md)
4. [CONTRACT](./CONTRACT.md)
5. [BUS](./BUS.md)
6. [ROUTER](./ROUTER.md)
7. [DATABASE](./DATABASE.md)
8. [CONFIGURE](./CONFIGURE.md)
9. [OBSERVE_MODULE](./OBSERVE_MODULE.md)
10. [SECURE_MODULE](./SECURE_MODULE.md)

### 2.3 Если вас интересуют межмодульные зависимости и чтение данных

Читайте так:

1. [ARCH](./ARCH.md)
2. [REGISTRY](./REGISTRY.md)
3. [CONTRACT](./CONTRACT.md)
4. [MODULE](./MODULE.md)
5. [APP_COMPOSITION](./APP_COMPOSITION.md)

### 2.4 Если вам нужна сквозная цепочка от запроса до аудита

Читайте так:

1. [HTTP](./HTTP.md)
2. [BUS](./BUS.md)
3. [LOGGING](./LOGGING.md)
4. [TRACE_FLOW](./TRACE_FLOW.md)
5. [APP_COMPOSITION](./APP_COMPOSITION.md)

### 2.5 Если вы настраиваете наблюдаемость

Читайте так:

1. [OBSERVABILITY](./OBSERVABILITY.md)
2. [OBSERVE_APP](./OBSERVE_APP.md)
3. [OBSERVE_MODULE](./OBSERVE_MODULE.md)
4. [LOGGING](./LOGGING.md)
5. [TRACE_FLOW](./TRACE_FLOW.md)

### 2.6 Если вы настраиваете защитный слой

Читайте так:

1. [SECURITY](./SECURITY.md)
2. [SECURE_APP](./SECURE_APP.md)
3. [SECURE_MODULE](./SECURE_MODULE.md)
4. [GUARD](./GUARD.md)
5. [GUARD_API](./GUARD_API.md)
6. [GUARD_BACKEND](./GUARD_BACKEND.md)
7. [HTTP](./HTTP.md)

### 2.7 Если вас интересуют продвинутые темы

Читайте так:

1. [HYDRATION](./HYDRATION.md)
2. [SAGA](./SAGA.md)
3. [TRANSACT](./TRANSACT.md)

## 3. Полный список документов

Ниже перечислены все документы в `docs/`, чтобы порядок чтения не терял ни один файл.

1. [APP_COMPOSITION](./APP_COMPOSITION.md)
2. [ARCH](./ARCH.md)
3. [BUS](./BUS.md)
4. [CONFIGURE](./CONFIGURE.md)
5. [CONFIGURE_ADMIN](./CONFIGURE_ADMIN.md)
6. [CONTRACT](./CONTRACT.md)
7. [DATABASE](./DATABASE.md)
8. [GUARD](./GUARD.md)
9. [GUARD_API](./GUARD_API.md)
10. [GUARD_BACKEND](./GUARD_BACKEND.md)
11. [HTTP](./HTTP.md)
12. [HTTP_FRONTEND_NOTES](./HTTP_FRONTEND_NOTES.md)
13. [HYDRATION](./HYDRATION.md)
14. [LOGGING](./LOGGING.md)
15. [MODULE](./MODULE.md)
16. [OBSERVABILITY](./OBSERVABILITY.md)
17. [OBSERVE_APP](./OBSERVE_APP.md)
18. [OBSERVE_MODULE](./OBSERVE_MODULE.md)
19. [PRAGMATISM](./PRAGMATISM.md)
20. [REGISTRY](./REGISTRY.md)
21. [ROUTER](./ROUTER.md)
22. [SAGA](./SAGA.md)
23. [SECURE_APP](./SECURE_APP.md)
24. [SECURE_MODULE](./SECURE_MODULE.md)
25. [SECURITY](./SECURITY.md)
26. [TRACE_FLOW](./TRACE_FLOW.md)
27. [TRANSACT](./TRANSACT.md)

## 4. Как этим пользоваться

1. Если вы не знаете, с чего начать, идите по базовому порядку из раздела 1.
2. Если у вас уже есть конкретная задача, берите тематический маршрут из раздела 2.
3. Если нужно проверить, не забыт ли какой-то документ, смотрите полный список из раздела 3.
