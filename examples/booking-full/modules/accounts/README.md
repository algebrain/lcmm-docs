# `accounts`

Module for user and role data.

## Responsibilities

1. Provide compact user records.
2. Expose sync-read providers.
3. Demonstrate module storage startup rules:
   - external-managed `:db`
   - self-managed SQLite fallback

## Read Providers

1. `:accounts/get-user-by-id`
2. `:accounts/get-user-by-login`

## HTTP

1. `GET /accounts/me?user-id=<user-id>`
2. `GET /accounts/users/:user_id`

## Examples

1. `http://localhost:3006/accounts/me?user-id=u-alice`
2. `http://localhost:3006/accounts/users/u-admin`

## Test

```bash
bb test.bb
```

`bb test.bb` runs `LINT`, then `TESTS`, then `FORMAT`.
The formatting step may update files in the working tree.
