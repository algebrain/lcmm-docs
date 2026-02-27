# Module Contracts for example

Этот каталог содержит контракты модулей `example` по спецификации `docs/CONTRACT.md`.

Файлы:
- `booking.contract.yaml`
- `notify.contract.yaml`
- `audit.contract.yaml`

Замечание:
- `app.main` и `app.sqlite` не оформлены как отдельные module contracts,
  потому что они выступают как composition root и storage layer, а не как самостоятельные бизнес-модули с внешним контрактом.
