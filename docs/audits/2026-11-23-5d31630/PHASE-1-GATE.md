# Phase 1 — Business Flow Coverage Gate (`PROMPT 1.G`)

- **Run ID**: `2026-11-23-5d31630` | **Date**: `2026-11-23`
- **Mode**: `AUDIT` (read-only on production source; zero production edits, zero commits/pushes/deploys)
- **Git Branch**: `integration/backend-release` | **HEAD SHA**: `5d316306e7fe07514e295b6b5fcb5ff9725c257a`

---

## 1. Executive Summary

All **21 / 21 Phase 1 business flow tasks** (`1.01`–`1.18` + extended `1.19`–`1.21`) have been independently verified against source `5d316306e7fe07514e295b6b5fcb5ff9725c257a`. The audit registry contains **49 unique, non-duplicated confirmed findings** (**0 P0**, **9 P1**, **25 P2**, **15 P3**, all `CONFIRMED (source)`) and **5 open product decisions** (`OBS-01`, `OBS-11`, `OBS-12`, `OBS-13`, `OBS-14`). A fresh full-suite Gradle execution against isolated PostgreSQL 17 (`ptitgo_audit_test`, no `public` schema reset) passed **253 / 256 tests (67 suites, 0 failed, 0 errors, 3 live-API skipped)**.

| Gate Dimension | Verdict | Rationale |
|---|---|---|
| **A. Business Flow Coverage Gate** | **`PASS`** | 21/21 flows `COVERED`; 192/192 HTTP routes, 1/1 STOMP endpoint, 7/7 schedulers, 14/14 listeners, 17/17 Lua scripts, 3/3 DB triggers mapped. |
| **B. Evidence Quality Gate** | **`PASS`** | 49/49 findings have exact `file:line`, root cause, preconditions, expected vs actual, reproduction, impact, and zero duplicates (`F-030`/`F-031` and `F-039`/`F-040`/`F-041` properly split). |
| **C. Test Execution Gate** | **`PARTIAL`** | Fresh Gradle full suite passed 253/253 non-live tests (incl. 15 PG suites), **but** Python Lua suite (`test_dispatch_presence.py`) is blocked by missing `liblua5.4` on Windows, and 49 findings lack automated regression tests (notably STOMP `F-014` E2E & concurrency races). |
| **D. Production Release Readiness** | **`NOT READY`** | **9 unresolved P1 blockers** (`F-001`, `F-002`, `F-004`, `F-010`, `F-014`, `F-017`, `F-020`, `F-023`, `F-024`) plus critical P2 operational bugs (`F-013`, `F-015`, `F-029`, `F-033`, `F-035`). |

---

## 2. Source Baseline & Git Status

- **Branch / HEAD**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a` (all 21 flow reports audited on this exact SHA).
- **Modified tracked files (3 test-only isolation edits preserved untouched)**:
  - `src/test/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationIdentityLockTest.kt` (`+11/-2`)
  - `src/test/kotlin/com/nhom15/datxebackend/driver/verification/VerificationProjectionPostgresTest.kt` (`+19/-10`)
  - `src/test/kotlin/com/nhom15/datxebackend/ride/ScheduledRideBookingPostgresTests.kt` (`+2/-0`)
- **Production source (`src/main/**`, `build.gradle.kts`, migrations)**: **0 files modified**.
- **Untracked**: `.agents/`, `skills-lock.json`, `docs/audits/2026-11-23-5d31630/`.

---

## 3. Coverage Validation (21 / 21 Phase 1 Tasks)

| Task | Report File | Status | Findings / Open Decisions | Verification Notes |
|---|---|---|---|---|
| `1.01` | [1.01-auth-account-lifecycle.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.01-auth-account-lifecycle.md) | `COVERED` | `F-023` (P1), `F-024` (P1), `F-025` (P2), `F-026` (P3), `OBS-11`, `OBS-12` | Full auth, cache, deletion & outbox traced; 16/16 tests passed. |
| `1.02` | [1.02-authz-ownership.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.02-authz-ownership.md) | `COVERED` | `F-014` (P1), `F-015` (P2), `F-016` (P2) | All 192 HTTP routes + STOMP mapped in `AUTHORIZATION-MATRIX.md`. |
| `1.03` | [1.03-driver-ekyc.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.03-driver-ekyc.md) | `COVERED` | `F-017` (P1), `F-018` (P2), `F-019` (P2), `F-020` (P1), `F-021` (P2), `F-022` (P3) | 55/56 tests passed (1 live Modal skipped). |
| `1.04` | [1.04-vehicle-garage-capability.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.04-vehicle-garage-capability.md) | `COVERED` | `F-044` (P2), `F-045` (P3) | 79/79 tests passed; `V37` trigger & `1.05` invariant verified. |
| `1.05` | [1.05-pricing-fare-estimation.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.05-pricing-fare-estimation.md) | `COVERED` | `F-042` (P3), `F-043` (P3), `OBS-14` | 62/64 tests passed (2 live Goong skipped); `F-039` split into `F-039..F-041`. |
| `1.06` | [1.06-instant-ride-booking.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.06-instant-ride-booking.md) | `COVERED` | `F-029` (P2) | 32/34 tests passed (2 live Goong skipped). |
| `1.07` | [1.07-matching-dispatch-availability.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.07-matching-dispatch-availability.md) | `COVERED` | `F-004` (P1) | All 15+ Lua scripts reviewed; Python Lua test blocked (`liblua5.4`). |
| `1.08` | [1.08-accept-reject-reassignment.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.08-accept-reject-reassignment.md) | `COVERED` | `F-001` (P1), `F-002` (P1), `F-003` (P2) | `DispatchPersistenceTests` 7/7 passed in full PG suite. |
| `1.09` | [1.09-arrival-pickup-handover-evidence.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.09-arrival-pickup-handover-evidence.md) | `COVERED` | *(0 new; split `F-030`/`F-031`)* | 36/36 tests passed; strict linear handover guards verified. |
| `1.10` | [1.10-ride-in-progress-completion.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.10-ride-in-progress-completion.md) | `COVERED` | `F-027` (P3) | 112/112 tests passed; `STATE-MACHINES.md` aligned to source. |
| `1.11` | [1.11-cancellation-incident-exceptional.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.11-cancellation-incident-exceptional.md) | `COVERED` | `F-028` (P3) | 128/128 tests passed; `CANCEL` blocked from `IN_PROGRESS`+. |
| `1.12` | [1.12-scheduled-rides-time-windows.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.12-scheduled-rides-time-windows.md) | `COVERED` | `F-030` (P2), `F-031` (P2) | 17/17 tests passed; UTC/`Asia/Ho_Chi_Minh` verified. |
| `1.13` | [1.13-payment-settlement.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.13-payment-settlement.md) | `COVERED` | `F-005` (P2), `F-006` (P2), `OBS-01` | `WalletPostgresTests` 13/13 & `VoucherSettlementPostgresTests` 4/4 passed in full suite. |
| `1.14` | [1.14-vouchers-promo-budget.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.14-vouchers-promo-budget.md) | `COVERED` | `F-009` (P2), `F-010` (P1), `F-011` (P2), `F-012` (P2), `F-013` (P2) | `VoucherReleasePostgresTests` 2/2 passed in full suite. |
| `1.15` | [1.15-wallets-withdrawals.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.15-wallets-withdrawals.md) | `COVERED` | `F-007` (P2), `F-008` (P3) | Mock top-up verified blocked in `production`/`prod`. |
| `1.16` | [1.16-ratings-reputation.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.16-ratings-reputation.md) | `COVERED` | `F-046` (P2), `F-047` (P2), `F-048` (P2), `F-049` (P3) | 27/27 tests passed. |
| `1.17` | [1.17-notifications-realtime.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.17-notifications-realtime.md) | `COVERED` | `F-033` (P2) | 37/37 tests passed; framework-traced `F-014` (`UserDestinationMessageHandler`). |
| `1.18` | [1.18-admin-operations.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.18-admin-operations.md) | `COVERED` | `F-032` (P3) | 30/30 tests passed; all 12 admin controllers audited. |
| `1.19` | [1.19-in-ride-chat.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.19-in-ride-chat.md) | `COVERED` | `F-036` (P3), `OBS-13` | 42/42 tests passed; confirmed 0 contradiction on `SUBSCRIBE /topic/rides/**`. |
| `1.20` | [1.20-support-tickets.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.20-support-tickets.md) | `COVERED` | `F-037` (P2), `F-038` (P2), `F-039` (P3), `F-040` (P3), `F-041` (P3) | 34/34 tests passed (incl. `SupportPersistenceTests` 9/9 PG). |
| `1.21` | [1.21-profiles-routing-tracking-uploads.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/flows/1.21-profiles-routing-tracking-uploads.md) | `COVERED` | `F-034` (P3), `F-035` (P2) | 26/28 tests passed (2 live Goong skipped). |

---

## 4. Entrypoint & State-Machine Reconciliation

- **Entrypoints (100% Mapped, 0 Unmapped)**:
  - **HTTP**: 192 / 192 routes (`AUTHORIZATION-MATRIX.md`).
  - **STOMP**: `/ws-location` (`CONNECT`, `SUBSCRIBE /user/queue/**`, `SEND /app/location`).
  - **Scheduled Jobs (7/7)**: `AccountCleanupOutboxWorker`, `VerificationSync`, `OfferTimeoutTask`, `RideDispatchOutboxWorker`, `RideDispatchRecoveryTask`, `ScheduledRideScheduler`, `LocationService#cleanupStaleDrivers`.
  - **Spring Event Listeners (14/14 handlers across 10 classes)**: `ChatPushListener` (2), `DriverReleaseService`, `RideCancellationService`, `PaymentNotificationListener`, `RideNotificationListener` (3), `TicketNotificationListener`, `WithdrawalNotificationListener`, `PaymentService#onRideCompleted` (`BEFORE_COMMIT`), `RideLifecycleEventListener`, `VoucherCampaignPublishedListener`, `VoucherGrantListener`.
  - **Redis Lua Scripts (17/17)**: `AdminRideService` (3), `DispatchScripts` (11), `LocationService` (2), `RedisRateLimiter` (1).
  - **Database Triggers (3/3)**: `provision_driver_wallets`, `provision_customer_wallets` (`V19`), `rides_immutable_vehicle_snapshot` (`V37`).
- **State-Machine Anomalies Confirmed**:
  - `Ride`: `adminAssignDriver` revives `NO_DRIVER_FOUND → DRIVER_ASSIGNED` without re-consuming released voucher (`F-002`) and corrupts old driver's Redis `current_ride` (`F-001`).
  - `Driver Presence`: `forceOffline` (`F-004`), `syncProjectionAndDriver` (`F-020`), `statusAfterRide` (`F-027`), and `DriverCapabilityService#update` (`F-044`) break DB/Redis/capability invariants.
  - `Support Tickets`: `sendUserMessage` reopens `CLOSED` tickets without `FOR UPDATE` (`F-037`); legacy `PATCH` bypasses `IN_PROGRESS` claim (`F-038`).

---

## 5. Findings Quality & Deduplication Verification

Direct count from `FINDINGS.md`: **`49` rows, `49` unique IDs (`F-001`..`F-049`), `0 P0`, `9 P1`, `25 P2`, `15 P3`**.
- All 49 findings are `CONFIRMED (source)` (`0 NEEDS_VALIDATION`, `0 REJECTED`, `0 NOT_APPLICABLE`).
- Verified splits: `F-030` vs `F-031` (independent scheduled-ride root causes); `F-039`, `F-040`, `F-041` (independent support-ticket root causes).
- Verified non-duplication: `F-014` covers all STOMP `SEND` spoofing targets (kept `CONFIRMED (source)` pending E2E STOMP integration test); `F-028` verified unique to `TicketService#validateMessage` via repo-wide `res.cloudinary.com` sweep.

---

## 6. Cross-Flow Invariant Validation

| Domain | Invariant | Status | Violating Findings |
|---|---|---|---|
| **A. Ride** | Single active ride per customer/driver; terminal rides immutable; linear handover; no cancel after `IN_PROGRESS` | **PARTIAL** | `F-002` (revive `NO_DRIVER_FOUND` with stale voucher), `F-003` (offer ledger corruption), `F-024` (active ride on deleted account), `F-031` (`INACTIVE` customer scheduled dispatch) |
| **B. Driver** | DB/Redis presence sync; eKYC/capability matches ride; force-offline safe on `BUSY` | **BROKEN** | `F-001`, `F-004`, `F-020`, `F-027`, `F-044` |
| **C. Money** | No double settlement/refund/voucher; `driverNetEarning + commission - subsidy = payableFare` (0%–100% voucher, `CASH`/`WALLET`) | **PARTIAL** | Arithmetic conservation **VERIFIED**; violated by `F-002` (double voucher), `F-005` (payment-method voucher bypass), `F-006` (silent `WALLET→CASH` fallback), `F-009`/`F-010`/`F-012`/`F-030` (promo budget corruption/lockup), `F-007` (unvalidated withdrawal bank info) |
| **D. AuthN/Z** | Ownership at all paths; dual-role isolation; token revocation; STOMP `SEND` restriction | **BROKEN** | `F-014` (STOMP `SEND` spoofing), `F-016` (revoked token / stale STOMP session), `F-023` (dual-role Firebase deletion), `F-015` (Swagger exposed in `production`) |
| **E. Privacy** | eKYC PII, private addresses, chat/support evidence, anonymous ratings | **BROKEN** | `F-018` (eKYC PII retained on deletion), `F-019` (ephemeral HMAC/key rotation), `F-035` (`/places/popular` leaks single-user home addresses), `F-046` (hidden reviews exposed), `F-049` (`rideId` in anonymous rating push), `OBS-12`/`OBS-13` |

---

## 7. Root-Cause Clusters (9 Clusters)

| Cluster | Finding IDs | Shared Pattern / Blast Radius |
|---|---|---|
| **A. Driver Status, Eligibility & Redis Sync** | `F-001`, `F-004`, `F-020`, `F-027`, `F-044` | Uncoordinated mutations to `drivers.status`/`onlineVehicleType`/Redis presence bypassing `BUSY`/`BLOCKED`/offer guards. |
| **B. Voucher, Promo Budget & Payment Restrictions** | `F-002`, `F-005`, `F-006`, `F-009`, `F-010`, `F-011`, `F-012`, `F-013`, `F-030` | Promo budget accounting asymmetry (`F-010`), missing locks/checks, and voucher-method/revival drift. |
| **C. AuthN, Identity Lock & Account Lifecycle** | `F-016`, `F-017`, `F-018`, `F-019`, `F-023`, `F-024`, `F-025`, `F-026` | Shared Firebase UID destruction, unlocked deletion races, unfiltered CCCD HMAC DoS, ephemeral HMAC keys. |
| **D. WebSocket STOMP & Notification Delivery** | `F-014`, `F-016`, `F-033`, `F-047` | Unrestricted STOMP `SEND`, broken SQL in `AFTER_COMMIT`, pre-commit sync FCM push in `rate()`. |
| **E. DB Connections/Locks Held Over HTTP I/O** | `F-013`, `F-021`, `F-029`, `F-034`, `F-047` | HikariCP pool=10 (`lock_timeout=3s`) starvation from Goong/Modal/FCM I/O, nested `REQUIRES_NEW`, and unthrottled GPS DB queries. |
| **F. Data Retention & Cloudinary Cleanup** | `F-018`, `F-028`, `F-045` (+ `OBS-12`, `OBS-13`) | Orphaned files/PII on soft delete and custom Cloudinary URL validator bypass (`F-028`). |
| **G. Admin & Legacy API Consistency** | `F-001`, `F-002`, `F-008`, `F-015`, `F-022`, `F-031`, `F-032`, `F-036`, `F-037`, `F-038` | Legacy endpoints and admin overrides bypassing newer guards, claim locks, notifications, or timezone rules. |
| **H. Pricing Auditability & Quote Consistency** | `F-042`, `F-043` (+ `OBS-14`) | Estimate-vs-create geofence drift, in-place active tariff edits, no ride-level fare breakdown. |
| **I. Ratings, Support & User Privacy** | `F-035`, `F-039`, `F-040`, `F-041`, `F-046`, `F-048`, `F-049` | Missing k-anonymity, hidden-review leak, rating aggregate race, ticket N+1/unbounded/idempotency gaps. |

---

## 8. Open Product Decisions (5 `PRODUCT_DECISION_REQUIRED`)

| ID | Summary | Blocks Fix? | Dependent Findings |
|---|---|---|---|
| **`OBS-01`** | `WALLET` insufficient balance at completion: automatic fallback to `CASH` (current code) vs rollback (docs). | Partial (UI/settlement UX) | `F-005`, `F-006` |
| **`OBS-11`** | `BLOCKED`/`SUSPENDED` driver login & read-only API access policy (currently allowed while `deletedAt == null`). | No | — |
| **`OBS-12`** | Self-deletion with positive wallet balance, regulatory eKYC retention period, and CCCD/GPLX re-registration policy. | Yes (for final `F-018` retention policy) | `F-018`, `F-023`, `F-024` |
| **`OBS-13`** | Chat & support ticket/attachment retention/anonymization after account deletion, orphan upload cleanup, and admin read-audit logging. | Yes (for deletion/retention scope) | `F-036`, `F-045` |
| **`OBS-14`** | Estimate-to-create fare snapshot (`409 PRICE_CHANGED` on admin tariff/night-window change) vs live re-pricing. | Yes (if snapshot enforcement desired) | `F-043` |

---

## 9. Fresh Full-Suite Test Execution & Missing Coverage

Command executed on `ptitgo_audit_test` (NO `public` schema reset, all 3 PG env var sets configured, live APIs unset):
```powershell
.\gradlew.bat test --rerun-tasks
```
- **Fresh JUnit XML Result**: **`BUILD SUCCESSFUL` (4m 15s)** — **67 suites, 256 tests: 253 PASSED, 0 FAILED, 0 ERRORS, 3 SKIPPED** (`EkycModalSmokeTest`: 1 live Modal, `PlaceSearchTests`: 1 live Goong, `VehicleRoutingTests`: 1 live Goong).
- **Blocked**: `src/test/python/test_dispatch_presence.py` (missing `liblua5.4` on Windows host).
- **Critical Missing Regression Tests**: STOMP `SEND`/`SUBSCRIBE` integration test (`F-014`/`F-016`), Redis Lua `COMMIT_ADMIN_RIDE_ASSIGNMENT` (`F-001`), dual-role account deletion & concurrent booking-vs-delete (`F-023`/`F-024`), promo budget `hold`/`release` cycle (`F-010`), CCCD draft/rejected DoS (`F-017`), `ChatService`/`VehicleService`/`TicketService`/`RideRatingService` PostgreSQL concurrency tests, and `uploads.*` tests (0 exist).

---

## 10. P1 Release Blockers (9 Findings — Must Fix Before Production)

1. **`F-014`** (STOMP `SEND` spoofing) — Immediate containment: reject any `SEND` not starting with `/app/`.
2. **`F-017`** (CCCD identity-lock DoS via unsubmitted `DRAFT`/rejected row + fail-open `runCatching`).
3. **`F-010`** (`PromoBudgetService` `issuance_reserved` inflation DoS on welcome-voucher book→cancel cycles).
4. **`F-023`** (Deleting one role of dual-role account destroys shared Firebase user).
5. **`F-024`** (`AccountDeletionService` unlocked read race + nulling bank account on `PENDING` withdrawals).
6. **`F-001`** (`COMMIT_ADMIN_RIDE_ASSIGNMENT` Lua arg bug permanently sticking old driver in Redis).
7. **`F-002`** (`adminAssignDriver` reviving `NO_DRIVER_FOUND` with released voucher → double discount + budget corruption).
8. **`F-004`** (`AdminDriverService#forceOffline` bricking `BUSY` driver `START_TRIP` & downgrading `BLOCKED`).
9. **`F-020`** (`syncProjectionAndDriver` variant of `F-004` bricking `BUSY` driver `START_TRIP`).

---

## 11. Phase 2 Execution Plan & Gate Verdict

Detailed risk-ordered Phase 2 plan (`2.01`–`2.10`) is documented in [PHASE-2-PRIORITY.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/PHASE-2-PRIORITY.md).
- **Recommended First Phase 2 Task**: **`2.01` — Security, AuthN/AuthZ, STOMP & Privacy Deep Dive** (covering P1 `F-014`, `F-017`, `F-023` and P2 `F-015`, `F-016`, `F-018`, `F-019`, `F-035`, `F-046`).
- **Gate Verdict**: Business Flow Coverage **`PASS`** | Evidence Quality **`PASS`** | Test Execution **`PARTIAL`** | Production Release Readiness **`NOT READY`**.
