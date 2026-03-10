# catalog

Demo module for available booking slots in `booking-full`.

## Responsibilities

1. Provide browser-first read endpoints for slot checks.
2. Expose sync-read provider for downstream booking validation.
3. Demonstrate module storage startup rules:
   - external-managed `:db`
   - self-managed SQLite fallback

## Read Provider

1. `:catalog/get-slot-by-id`

## HTTP

1. `GET /catalog/slots`
2. `GET /catalog/slots?status=open`
3. `GET /catalog/slots/:slot_id`

## Browser Examples

1. `http://localhost:3006/catalog/slots`
2. `http://localhost:3006/catalog/slots?status=open`
3. `http://localhost:3006/catalog/slots/slot-09-00`

## Test

```bash
clj -M:test
```
