# Admin search and filter contract

Deploy backend before goAdmin. `GET /api/admin/rides/filter-capabilities` now returns
`{search:true, createdRange:true}` inside the standard API response envelope.

| Endpoint | Filters |
| --- | --- |
| `GET /api/admin/rides` | `q` (customer/driver name or phone, exact ride ID with optional `#`), comma-separated `status`, `createdFrom`, `createdTo`, `page`, `size` |
| `GET /api/admin/drivers` | `search`, `verification_status` (or `verificationStatus`), `online`, `flagged`, `page`, `size` |
| `GET /api/admin/customers` | `q`, `status`, `flagged`, `sort` (`createdAt`, `totalTrips`, `cancelRate30d`), `page`, `size` |
| `GET /api/admin/customers/{id}/rides` | `status`, `from`, `to` (Vietnam local dates), `page`, `size` |
| `GET /api/admin/customers/{id}/wallet-transactions` | `q` (description or exact transaction/ride ID), `type` (legacy ledger type), `status`, `createdFrom`, `createdTo`, `page`, `size` |
| `GET /api/admin/tickets` | `q` (ticket code, owner name/phone or ride ID), `status`, `ownerRole`, `category`, `createdFrom`, `createdTo`, `page`, `size` |
| `GET /api/admin/withdrawals` | `q` (driver name/phone, bank name/account or exact request ID), `status`, `createdFrom`, `createdTo`, `page`, `size` |

- Filters combine with AND; name/phone/ID alternatives inside a keyword combine with OR.
- `createdFrom` is inclusive, `createdTo` exclusive. Both are ISO UTC instants.
  Selecting 08/11/2026 on the web sends `[2026-11-07T17:00:00Z, 2026-11-08T17:00:00Z)`.
- Search is case insensitive, trimmed and limited to 200 characters. `%`, `_` and `!`
  are literal characters, not wildcard commands. SQL values are bound parameters.
- All paginated searches filter before counting/paging. Ordering includes ID as a tie breaker.
- Page starts at 0; size must be 1–100. Web offers 20/50/100.
- Online drivers are ONLINE/BUSY; BLOCKED drivers are excluded from the online filter.
- Web keeps filters in the URL, resets page on filter/size changes, and preserves
  filters when returning from details. Wallet filters have their own URL prefix.
- Pricing returns the complete list, so web filters it locally. Driver document review
  combines every API page before grouping, searching and paginating applications.

## Regression checks

`./gradlew test --tests '*AdminRideCapabilitiesHttpTests' --tests '*TicketServiceTests' --tests '*WithdrawalServiceTests'`

`AdminFiltersPostgresTests` additionally uses `TEST_DB_URL`, `TEST_DB_USER`,
`TEST_DB_PASSWORD`. It creates/drops a unique schema and requires a disposable database.

Run `npm test -- --maxWorkers=1` and `npm run build && npm run lint` in goAdmin.
