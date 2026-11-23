# PtitGo Backend Audit Plan (Risk-Ranked)

- **Run ID**: `2026-11-23-5d31630`
- **Commit**: `5d316306e7fe07514e295b6b5fcb5ff9725c257a` (`integration/backend-release`)
- **Risk Priority Scale** *(distinct from finding severity P0–P3)*: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` — ranked by business/financial impact, attack surface exposure, concurrency/state complexity, and baseline test gaps.
- **Explicit `N/A` Scope**: Live VNPay/MoMo/bank payment gateway webhooks & APIs (not implemented), tip payouts (`tip > 0` rejected), SOS emergency flow (does not exist), durable notification outbox (notifications are `AFTER_COMMIT` best-effort).

---

## Phase 1 — Business Flow Audits (`1.01–1.21` + `1.G`)

| Task ID | Risk Priority | Flow / Scope | Files / Entrypoints Covered | Dependencies | Status | Expected Output / Evidence |
|---|---|---|---|---|---|---|
| **1.15** | **CRITICAL** | Wallets (`CREDIT`/`CASH`/`CUSTOMER`), Withdrawals & Mock Top-Ups | `DriverWalletController`, `WalletOperationsController` (5 controllers), `WalletService`, `WithdrawalService`, `MockWalletService`, `V18..V22` (14 routes) | `0.00` | `PENDING` | `flows/1.15-wallets-withdrawals.md`: ledger invariants, lock ordering, withdrawal double-review race, mock top-up prod guard |
| **1.13** | **CRITICAL** | Payment Settlement (`CASH`/`WALLET`), Cash Confirm & Method Switch *(Live gateway webhooks = `N/A`)* | `CashPaymentController`, `CustomerPaymentController`, `PaymentService`, `PaymentMethodService`, `WalletService#settle`, `V1`, `V18`, `V23` (2 routes + `BEFORE_COMMIT` listener) | `0.00`, `1.15` | `PENDING` | `flows/1.13-payment-settlement.md`: verify `WALLET→CASH` fallback vs `docs/WALLET_MODULE.md`, concurrent switch vs complete, `0 VND` rides |
| **1.12** | **CRITICAL** | Ride Completion & Atomic Settlement Trigger | `RideProgressController#completeRide`, `RideProgressService`, `RideCompleted` (`BEFORE_COMMIT` & `AFTER_COMMIT` listeners) (1 route) | `0.00`, `1.13`, `1.15` | `PENDING` | `flows/1.12-ride-completion.md`: idempotency, waiting fee/final fare rounding, rollback & `AFTER_COMMIT` partial failure |
| **1.02** | **CRITICAL** | Authorization, RBAC & Resource Ownership (IDOR) | `SecurityConfig`, `FirebaseAuthFilter`, all `(filter-only)` controllers across 192 routes + STOMP `/ws-location` | `0.00` | `PENDING` | `flows/1.02-authz-ownership.md`: matrix of every `(filter-only)` & `@PreAuthorize` route, cross-tenant/cross-role tests |
| **1.03** | **CRITICAL** | Driver Onboarding, Native eKYC, Capture Storage & Identity Lock | `DriverOnboardingController`, `DriverVerificationWorkflowController` (38 routes), `EkycController` (1 route), `DriverVerificationWorkflowService`, `EkycClient`, `VerificationSync`, `V34`, `V38`, `V42` | `0.00` | `PENDING` | `flows/1.03-driver-ekyc.md`: identity lock, ephemeral HMAC fallback, file storage security, submit/decision/reverify races, error shape |
| **1.07** | **CRITICAL** | Sequential Dispatch, Offers, Accept/Reject, Timeout & Presence | `OfferController` (3 routes), `DriverPresenceController`, `RideSearchProgressController#progress`, `MatchingService`, `SequentialDispatchService`, `OfferTimeoutTask`, `LocationService#cleanupStaleDrivers`, `DriverReleaseService`, `dispatch_*.lua`, `V15` | `0.00` | `PENDING` | `flows/1.07-dispatch-offers.md`: Redis↔DB sync, accept vs timeout vs cancel races, Lua script logic review, driver release failure |
| **1.01** | **HIGH** | Authentication, Session, Token Revocation & Account Deletion | `AuthController` (8 routes), `AuthService`, `FirebaseAuthFilter`, `AuthenticatedUserCache`, `AccountCleanupOutboxWorker`, `V10` | `0.00` | `PENDING` | `flows/1.01-auth-session.md`: `checkRevoked` mismatch, cache eviction on block/delete, active ride/withdrawal deletion guards |
| **1.06** | **HIGH** | Ride Booking (Immediate), Idempotency & Dispatch Outbox | `RideController` (create/get/active/list, 7 routes), `RideService`, `RideDispatchOutboxWorker`, `RideDispatchRecoveryTask`, `V10`, `V12`, `V15` | `0.00`, `1.05`, `1.14` | `PENDING` | `flows/1.06-ride-booking.md`: `Idempotency-Key` races, 1-active-ride guard, outbox vs recovery double-dispatch |
| **1.08** | **HIGH** | Ride Cancellation (Customer / Driver / Admin / No-Driver) | `RideController#cancelRide` (2 routes), `AdminRideController#cancelRide`, `RideCancellationService`, `DriverReleaseService`, `RideLifecycleEventListener` | `0.00`, `1.07`, `1.14` | `PENDING` | `flows/1.08-ride-cancellation.md`: cancel vs accept/handover/complete races, voucher/promo release idempotency, Redis cleanup |
| **1.14** | **HIGH** | Vouchers, Campaigns, Claims & Promo Budget Ledger | `AdminVoucherController` (7 routes), `VoucherController` (3 routes), `VoucherService`, `AdminVoucherService`, `PromoBudgetService`, `VoucherGrantService`, `VoucherGrantListener`, `VoucherCampaignPublishedListener`, `V28..V30` | `0.00` | `PENDING` | `flows/1.14-vouchers-promo-budget.md`: `readOnly=true` + `REQUIRES_NEW`, budget depletion races, double-claim, timezone validity |
| **1.17** | **HIGH** | Admin Manual Ride Intervention (Assignable Drivers, Assign, Cancel) | `AdminRideController` (3 action routes), `AdminRideService`, `RideStateMachine#adminAssignDriver` | `0.00`, `1.07`, `1.08` | `PENDING` | `flows/1.17-admin-ride-intervention.md`: state-machine bypass, Redis `'ON_TRIP'` vs `'BUSY'` mismatch, concurrent offer race |
| **1.09** | **HIGH** | Scheduled Ride Booking & Scheduler Dispatch | `RideController#createRide` (`SCHEDULED`), `ScheduledRideScheduler` (`30s`), `RideService` | `0.00`, `1.06`, `1.07` | `PENDING` | `flows/1.09-scheduled-rides.md`: multi-instance duplicate dispatch, lead-time/expiry, TZ handling, credit check at dispatch |
| **1.10** | **HIGH** | Trip Progress & Approach Location | `RideProgressController` (`start-arriving`, `arrived`, `start`, `arrive-destination`, 4 routes), `RideSearchProgressController#location`, `RideProgressService`, `RideStateMachine` | `0.00`, `1.11` | `PENDING` | `flows/1.10-trip-progress.md`: transition guards, duplicate calls, waiting time calculation |
| **1.11** | **HIGH** | Pickup & Dropoff Handover & Correction Revisions | `RideHandoverController` (4 routes), `HandoverCorrectionController` (4 routes), `RideHandoverService`, `HandoverCorrectionService`, `V14` | `0.00`, `1.10` | `PENDING` | `flows/1.11-handover-corrections.md`: `(filter-only)` role checks, phase/revision ordering, concurrent confirm vs cancel |
| **1.05** | **MEDIUM** | Pricing Rules & Fare Estimation / Quotes | `RideController#estimateRide`, `AdminPricingRuleController` (5 routes), `PricingService`, `AdminPricingRuleService`, `MoneyRounding`, `V2`, `V12` | `0.00` | `PENDING` | `flows/1.05-pricing-quotes.md`: active rule uniqueness, night surcharge TZ, quote tampering/staleness, rounding |
| **1.04** | **MEDIUM** | Customer Garage, Vehicle Catalog & Driver Capabilities | `VehicleController` (7 routes), `VehicleCatalogController` (2 routes), `DriverCapabilityController` (3 routes), `LicenseEligibilityPolicy`, `V2`, `V6`, `V36`, `V37` | `0.00`, `1.03` | `PENDING` | `flows/1.04-garage-capabilities.md`: archiving in-use vehicle, plate uniqueness, capability toggle while online/busy |
| **1.18** | **MEDIUM** | Admin Operations (Dashboard, Customer/Driver/Ride Inspection, Force Offline) | `AdminDashboardController`, `AdminCustomerController` (6 routes), `AdminDriverController` (3 routes), `AdminRideController` (4 read routes) | `0.00`, `1.02` | `PENDING` | `flows/1.18-admin-operations.md`: force-offline mid-trip, customer block cache eviction, pagination/filter safety, PII scope |
| **1.20** | **MEDIUM** | Support Tickets (Legacy & Threaded Claim/Chat) *(Extra)* | `SupportController` (6 routes), `TicketController` (5 routes), `AdminTicketController` (4 routes), `SupportService`, `TicketService`, `V14`, `V25` | `0.00`, `1.02` | `PENDING` | `flows/1.20-support-tickets.md`: legacy `(filter-only)` admin routes, concurrent claim race, cross-user IDOR |
| **1.19** | **MEDIUM** | In-Ride Chat & Async Push *(Extra)* | `ChatController` (3 routes), `ChatService`, `ConversationService`, `ChatPushListener`, `V9`, `V17` | `0.00`, `1.02` | `PENDING` | `flows/1.19-in-ride-chat.md`: participant isolation across re-dispatch, terminal ride chat, `chatTaskExecutor` handling |
| **1.16** | **MEDIUM** | Ratings, Moderation & Notifications / Device Tokens | `RideRatingController` (9 routes), `AdminRatingController` (3 routes), `NotificationHistoryController` (4 routes), `DeviceTokenController` (2 routes), 4 `*NotificationListener`s | `0.00`, `1.02` | `PENDING` | `flows/1.16-ratings-notifications.md`: `AdminRatingService` TZ bug, duplicate rating, `(filter-only)` notification/token IDOR, FCM errors |
| **1.21** | **MEDIUM** | Profiles, Places, Routing/Goong, Live Tracking & Cloudinary Uploads *(Extra)* | `CustomerProfileController` (3), `CustomerPlaceController` (3), `DriverProfileController` (3), `RoutingController` (2), `PlaceSearchController` (3), `PlaceNearbyController` (1), `DriverLocationController` (1), `NearbyDriversController` (2), `CoordinateController` (STOMP), `UploadSignatureController` (1) | `0.00`, `1.02`, `1.03` | `PENDING` | `flows/1.21-profiles-tracking-routing-uploads.md`: `updateIdentity` vs eKYC lock, STOMP authZ, Goong timeout/cache, Cloudinary signature abuse |
| **1.G** | **HIGH** | Phase 1 Gate & Cross-Flow Consistency Review | All `flows/1.*.md` | `1.01–1.21` | `PENDING` | Verified 100% flow & endpoint coverage; updated `COVERAGE-MATRIX.md` & `CHECKPOINT.md` |

---

## Phase 2 — Cross-Cutting & Module Deep-Dive Audits (`2.01–2.10` + `2.G`)

| Task ID | Risk Priority | Audit Dimension | Primary Scope | Dependencies | Status | Expected Output |
|---|---|---|---|---|---|---|
| **2.01** | **CRITICAL** | Security, Auth, CORS, STOMP, Actuator/Swagger & Secrets Hygiene | `SecurityConfig`, `FirebaseAuthFilter`, `WebSocketConfig`, `application*.properties`, `docker-compose.production.yml` | Phase 1 | `PENDING` | `modules/2.01-security-hardening.md` |
| **2.02** | **CRITICAL** | Transactions, Locking, Concurrency & Idempotency | `@Transactional` boundaries, `FOR UPDATE` ordering, `lock_timeout=3s`, `BEFORE_COMMIT`/`AFTER_COMMIT`, outbox/idempotency tables | Phase 1 | `PENDING` | `modules/2.02-transactions-concurrency.md` |
| **2.03** | **CRITICAL** | PostgreSQL Schema, Flyway `V1..V42` & Data Integrity | `src/main/resources/db/migration/V1..V42`, JPA entities vs JDBC SQL, indexes/constraints | Phase 1 | `PENDING` | `modules/2.03-database-flyway.md` |
| **2.04** | **HIGH** | Redis Consistency, Lua Scripts & Caching | `RedisKeys`, `dispatch_*.lua`, `LocationService`, `SequentialDispatchService`, `AuthenticatedUserCache`, `PlaceQueryCache` | Phase 1 | `PENDING` | `modules/2.04-redis-cache.md` |
| **2.05** | **HIGH** | Money, Rounding, Timezone (`Asia/Ho_Chi_Minh`) & Numeric Precision | `MoneyRounding`, `PricingService`, `WalletService`, `PromoBudgetService`, `AdminRatingService`, `ScheduledRideScheduler`, `VoucherService` | Phase 1 | `PENDING` | `modules/2.05-money-timezone.md` |
| **2.06** | **HIGH** | External Integrations (Firebase, Goong, Modal eKYC, Cloudinary, VerificationSync) | Clients, timeouts, retries, circuit-breaking, error propagation, PII leakage | Phase 1 | `PENDING` | `modules/2.06-external-integrations.md` |
| **2.07** | **HIGH** | Schedulers, Outboxes & Async Executors | `SchedulingConfig` (`poolSize=4`), 6+1 `@Scheduled` jobs, `chatTaskExecutor`, multi-instance safety | Phase 1 | `PENDING` | `modules/2.07-schedulers-async.md` |
| **2.08** | **MEDIUM** | Error Handling, API Contract & Input Validation | `GlobalExceptionHandler`, `DriverVerificationWorkflowController` custom errors, `@Valid` gaps across 192 routes | Phase 1 | `PENDING` | `modules/2.08-api-contract-validation.md` |
| **2.09** | **MEDIUM** | Performance, N+1 Queries, Pagination & Resource Bounds | Repositories, JDBC queries, unbounded lists, `LocationService` full ZSET scans | Phase 1 | `PENDING` | `modules/2.09-performance-reliability.md` |
| **2.10** | **MEDIUM** | Deployment, Profiles (`prod` vs `production`), Observability & Logging PII | `application*.properties`, `docker-compose*.yml`, logging of CCCD/tokens/phone numbers | Phase 1 | `PENDING` | `modules/2.10-ops-profiles-logging.md` |
| **2.G** | **HIGH** | Phase 2 Gate & Variant Analysis Sweep | Cross-codebase variant check on all Phase 1–2 root causes | `2.01–2.10` | `PENDING` | Updated `FINDINGS.md`, `COVERAGE-MATRIX.md`, `CHECKPOINT.md` |

---

## Phase 3 — Cross-Repo Integration Audits (`3.01–3.04` + `3.G`) *(Subject to user permission to read sibling repos)*

| Task ID | Risk Priority | Scope | Sibling Repo on Disk | Dependencies | Status | Expected Output |
|---|---|---|---|---|---|---|
| **3.01** | **HIGH** | Mobile Client (`datxe_rn`) ↔ Backend API/STOMP Contract | `F:\datxe_rn` (`exists=True`) | Phase 2 | `PENDING` | `integrations/3.01-mobile-contract.md` |
| **3.02** | **MEDIUM** | Admin Web (`goAdmin`) ↔ Backend Admin API Contract | `F:\goAdmin` (`exists=True`) | Phase 2 | `PENDING` | `integrations/3.02-admin-contract.md` |
| **3.03** | **HIGH** | eKYC Service (`ekyc-service`) ↔ Backend `EkycClient` / `VerificationSync` | `F:\ekyc-service` (`exists=True`) | Phase 2 | `PENDING` | `integrations/3.03-ekyc-contract.md` |
| **3.04** | **MEDIUM** | End-to-End Cross-System Error/Auth/Status Mapping | All available repos | `3.01–3.03` | `PENDING` | `integrations/3.04-cross-system-alignment.md` |
| **3.G** | **MEDIUM** | Phase 3 Gate | All `integrations/3.*.md` | `3.01–3.04` | `PENDING` | Updated `COVERAGE-MATRIX.md` & `CHECKPOINT.md` |

---

## Phase 4 & Phase 5 — Independent Validation, Variant Analysis & Final Reporting

| Task ID | Risk Priority | Scope | Dependencies | Status | Expected Output |
|---|---|---|---|---|---|
| **4.00–4.02** | **HIGH** | Independent Finding Validation (CONFIRMED/SUSPECTED/NOT VERIFIED/REJECTED), Variant Analysis, Priority Ranking & Remediation Plan | Phases 1–3 | `PENDING` | Validated `FINDINGS.md`, `REMEDIATION-PLAN.md` |
| **5.00–5.01** | **HIGH** | Final Executive Audit Report & Post-Audit Handoff *(FIX/VERIFY only if user explicitly requests)* | Phase 4 | `PENDING` | `FINAL-AUDIT-REPORT.md`, final `CHECKPOINT.md` |
