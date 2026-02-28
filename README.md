# LCMM Documentation Draft

This repository contains a draft of the LCMM documentation and a runnable sample app that demonstrates the architecture and APIs described in `docs/`.

**What is here**
- Library documentation in `docs/`
- A booking server sample in `example/` (GET-first demo for browser-based manual checks)

**Minimal developer setup**
- `Java 25` (required for virtual threads support)
- Installed `Clojure` CLI (`clj`)
- Installed `Babashka` (`bb`)

**Recommended reading order**
- [Pragmatism](docs/PRAGMATISM.md)
- [Architecture](docs/ARCH.md)
- [Event Bus API](docs/BUS.md)

Other docs in `docs/` as needed for your current task:
- [Router API](docs/ROUTER.md)
- [Configure (Developer)](docs/CONFIGURE.md)
- [Configure (Admin)](docs/CONFIGURE_ADMIN.md)
- [Logging & Observability](docs/LOGGING.md)
- [Modules](docs/MODULE.md)
- [Database Abstraction](docs/DATABASE.md)
- [Contract Specification](docs/CONTRACT.md)
- [Hydration Protocol](docs/HYDRATION.md)
- [Security Baseline](docs/SECURITY.md)
- [Security: App Level](docs/SECURE_APP.md)
- [Security: Module Level](docs/SECURE_MODULE.md)
- [Transact](docs/TRANSACT.md)
- [Sagas](docs/SAGA.md)

**Booking server sample (`example/`)**

Location: `example/`

`example` demonstrates a small LCMM-style booking service with:
- module boundaries (`booking`, `notify`, `audit`);
- asynchronous cross-module communication through `event-bus`;
- SQLite persistence for bookings, notifications, and audit trail;
- TOML-based configuration through `lcmm-configure`;
- GET-only endpoints for easy manual checks in browser address bar.

Run the booking server:
```bash
cd example
clj -M:run-main
```

Run tests, lint, and formatting for booking sample:
```bash
cd example
bb test.bb
```

Notes
- The sample uses the current `event-bus` and `router` APIs described in the docs.
- The sample test runner includes linting and formatting steps; see `example/test.bb` for details.

Internal docs for `example`:
- [example/README.md](example/README.md)
- [example/ENDPOINTS_RU.md](example/ENDPOINTS_RU.md)
