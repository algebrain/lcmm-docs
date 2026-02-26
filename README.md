# LCMM Documentation Draft

This repository contains a draft of the LCMM documentation and a runnable sample app that demonstrates the architecture and APIs described in `docs/`.

**What is here**
- Library documentation in `docs/`
- A working sample application in `example/`

**Docs index**
- [Architecture](docs/ARCH.md)
- [Event Bus API](docs/BUS.md)
- [Router API](docs/ROUTER.md)
- [Logging & Observability](docs/LOGGING.md)
- [Modules](docs/MODULE.md)
- [Database Abstraction](docs/DATABASE.md)
- [Sagas](docs/SAGA.md)
- [Transact](docs/TRANSACT.md)
- [Configure (Developer)](docs/CONFIGURE.md)
- [Configure (Admin)](docs/CONFIGURE_ADMIN.md)

**Sample app**
Location: `example/`

Run the app:
```bash
cd example
clj -M:run-main
```

Run the sample tests, lint, and formatting:
```bash
cd example
bb test.bb
```

Notes
- The sample uses the current `event-bus` and `router` APIs described in the docs.
- The sample test runner includes linting and formatting steps; see `example/test.bb` for details.
