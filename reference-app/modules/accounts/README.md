# accounts

Demo module for user and role data in `reference-app`.

## Responsibilities

1. Provide compact user records for browser-first manual checks.
2. Expose sync-read providers for downstream modules.
3. Demonstrate module storage startup rules:
   - external-managed `:db`
   - self-managed SQLite fallback

## Read Providers

1. `:accounts/get-user-by-id`
2. `:accounts/get-user-by-login`

## HTTP

1. `GET /accounts/me?user-id=<user-id>`
2. `GET /accounts/users/:user_id`

## Browser Examples

1. `http://localhost:3006/accounts/me?user-id=u-alice`
2. `http://localhost:3006/accounts/users/u-admin`

## Test

```bash
clj -M:test
```
