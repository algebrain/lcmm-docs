# example2: booking demo on LCMM

This example demonstrates a small LCMM-based server with GET-only endpoints for manual browser testing.

## Endpoints

- `GET /booking/create?slot=<slot>&name=<name>`
- `GET /booking/get?id=<booking-id>`
- `GET /booking/list`
- `GET /notify/list`
- `GET /audit/list`

## Run

```bash
clj -M:run-main
```

Default config is loaded from `resources/booking_config.toml`. Environment variables are optional and only override TOML values.
