# reference-app/app

Composition root for the current `reference-app` skeleton.

## Current Scope

1. Loads config via `lcmm-configure`.
2. Creates `event-bus`, `router`, `read-provider registry` and `observe` registry.
3. Initializes `accounts` and `catalog`.
4. Publishes browser-first endpoints:
   - `GET /healthz`
   - `GET /readyz`
   - `GET /metrics`
   - `GET /accounts/me?user-id=u-alice`
   - `GET /accounts/users/u-admin`
   - `GET /catalog/slots`
   - `GET /catalog/slots?status=open`
   - `GET /catalog/slots/slot-09-00`

## Run

```bash
clj -M:run-main
```

## Test

```bash
clj -M:test
```
