# PtitGo Backend Architecture Map

- **Run ID**: `2026-11-23-5d31630`
- **Commit**: `5d316306e7fe07514e295b6b5fcb5ff9725c257a` (`integration/backend-release`)
- **Stack**: Kotlin `1.9.25` / JVM `21` / Spring Boot `3.5.x` / Gradle / PostgreSQL (Flyway `V1..V42`, `lock_timeout=3s`) / Redis (Lettuce + Lua scripts) / Spring WebSocket STOMP / Firebase Auth + FCM / Goong V1 & V2 / Cloudinary / Modal eKYC (`app.ekyc.*`)
- **Source Root**: `src/main/kotlin/com/nhom15/datxebackend/`

---

## 1. 20 Backend Modules & Ownership

| # | Module (`package`) | Primary Responsibility | Key Controllers | Key Services | Persistence / External |
|---|---|---|---|---|---|
| 1 | `admin` | Admin dashboard, customer/driver/ride/rating management, manual dispatch/cancel | `AdminCustomerController`, `AdminDashboardController`, `AdminDriverController`, `AdminRatingController`, `AdminRideController` | `AdminCustomerService`, `AdminDashboardService`, `AdminDriverService`, `AdminRatingService`, `AdminRideService` | JPA + JDBC (`customers`, `drivers`, `rides`, `ratings`, `admin_customer_actions`), Redis (`DriverLocationPort` / `DriverPresence`) |
| 2 | `auth` | Firebase ID token login/register (`CUSTOMER`/`DRIVER`/`ADMIN`), `/me`, logout, account deletion | `AuthController` | `AuthService`, `AccountCleanupOutboxWorker`, `FirebaseAuthFilter`, `AuthenticatedUserCache` | JPA (`admins`, `customers`, `drivers`), JDBC (`account_cleanup_outbox`), Firebase Auth |
| 3 | `chat` | In-ride 1-to-1 chat, read receipts, idempotent `clientMessageId`, async push | `ChatController` | `ChatService`, `ConversationService`, `ChatPushListener` | JPA (`conversations`, `chat_messages`), `NotificationPort` (FCM), `chatTaskExecutor` |
| 4 | `common` | Shared exceptions, `ApiResponse`, `GlobalExceptionHandler`, money rounding, timezone helpers | — | `GlobalExceptionHandler`, `MoneyRounding` | — |
| 5 | `config` | Security filter chain, WebSocket STOMP, Redis/Lua, Scheduling (`poolSize=4`), Firebase/Goong/OpenAPI beans | — | `SecurityConfig`, `WebSocketConfig`, `SchedulingConfig`, `FirebaseConfig`, `RedisConfig` | Spring Security, Redis, Firebase, Actuator/Swagger |
| 6 | `customer` | Customer profile, avatar, recent/popular places, place reporting | `CustomerProfileController`, `CustomerPlaceController` | `CustomerProfileService`, `CustomerPlaceService` | JPA (`customers`), JDBC (`rides` / places queries) |
| 7 | `driver` (+ `driver/verification`) | Driver profile/bank/identity, vehicle capabilities, native eKYC verification workflow, identity lock, optional sync | `DriverOnboardingController`, `DriverProfileController`, `DriverCapabilityController`, `DriverVerificationWorkflowController` | `DriverProfileService`, `DriverCapabilityService`, `DriverVerificationWorkflowService`, `DriverVerificationEligibilityService`, `VerificationSync` (conditional) | JPA (`drivers`), JDBC (`driver_vehicle_skills`, `driver_capability_preferences`, `driver_verification_*`, `verification_sync_checkpoint`, `driver_verification_projection`), local encrypted capture storage (`data/driver-verification-captures/`), `EkycClient` |
| 8 | `ekyc` | Proxy/client to Modal eKYC OCR, CCCD QR parsing, face quality & face comparison | `EkycController` (conditional `app.ekyc.enabled=true`) | `EkycClient`, `EkycService` | External HTTP (`app.ekyc.base-url`, Modal eKYC) |
| 9 | `matching` | Sequential driver dispatch, Redis+DB offer lifecycle, accept/reject, offer timeout, driver release & cancel cleanup | `OfferController` | `MatchingService`, `SequentialDispatchService`, `OfferService`, `OfferTimeoutTask`, `DriverReleaseService`, `RideCancellationService` | JDBC (`ride_dispatch_offers`), Redis GEO + hashes + Lua scripts (`dispatch_*.lua`), STOMP `/user/queue/trip`, `NotificationPort` |
| 10 | `notification` | Persistent notification history, unread count, FCM device token registration, async fan-out listeners | `NotificationHistoryController`, `DeviceTokenController` | `NotificationService`, `FcmService`, `DeviceTokenService`, `RideNotificationListener`, `PaymentNotificationListener`, `TicketNotificationListener`, `WithdrawalNotificationListener` | JPA (`device_tokens`), JDBC (`notifications`), Firebase Cloud Messaging (FCM) |
| 11 | `payment` | Cash/wallet ride settlement, `CASH↔WALLET` switch, driver `CREDIT`/`CASH` & customer `CUSTOMER` wallets, withdrawals, dev/demo mock top-up | `CashPaymentController`, `CustomerPaymentController`, `DriverWalletController`, `WalletOperationsController` (5 controllers) | `PaymentService`, `PaymentMethodService`, `WalletService`, `WithdrawalService`, `MockWalletService` | JPA (`wallets`, `wallet_transactions`, `withdrawal_requests`), JDBC (`payments`, `payment_transactions`, `ride_wallet_settlements`, `payment_method_changes`, `driver_mock_topups`, `customer_mock_topups`), `PromoBudgetService` |
| 12 | `pricing` | Active pricing rules CRUD, fare & night-surcharge & waiting-fee calculation, rounding | `AdminPricingRuleController` | `PricingService`, `AdminPricingRuleService` | JPA (`pricing_rules`) |
| 13 | `rating` | Customer↔Driver ride ratings, received reviews, visibility moderation | `RideRatingController` | `RideRatingService` | JDBC (`ratings`), updates aggregate rating on `drivers`/`customers` |
| 14 | `ride` | Quote/estimate, booking (immediate & scheduled), state machine, progress, pickup/dropoff handover & revisions, cancel, dispatch outbox & recovery, search progress | `RideController`, `RideProgressController`, `RideHandoverController`, `HandoverCorrectionController`, `RideSearchProgressController` | `RideService`, `RideStateMachine`, `RideProgressService`, `RideHandoverService`, `HandoverCorrectionService`, `RideDispatchOutboxWorker`, `RideDispatchRecoveryTask`, `ScheduledRideScheduler`, `RideLifecycleEventListener` | JPA (`rides`, `ride_status_histories`, `vehicle_handover_reports`, `handover_photos`), JDBC (`ride_create_idempotency`, `ride_dispatch_outbox`, `ride_route_quotes`, `handover_revisions`) |
| 15 | `routing` | Geocoding, directions, place search/detail/reverse/nearby with caching & route snapshot | `RoutingController`, `PlaceSearchController`, `PlaceNearbyController` | `RoutingService`, `GoongClient`, `PlaceSearchService`, `PlaceNearbyService` | External Goong API (V1 & V2), Redis / in-memory place query cache |
| 16 | `support` (+ `support/ticket`) | Legacy support tickets (`/api/support/tickets`) & threaded support tickets with admin claim/chat (`/api/tickets`, `/api/admin/tickets`) | `SupportController`, `TicketController`, `AdminTicketController` | `SupportService`, `TicketService` | JPA (`support_tickets`, `support_ticket_messages`), JDBC (`support_ticket_images`), `TicketReplied` event |
| 17 | `tracking` | Driver GPS ingestion (HTTP + STOMP), availability toggle (`ONLINE`/`OFFLINE`), nearby drivers, stale driver cleanup | `DriverLocationController`, `DriverPresenceController`, `NearbyDriversController`, `CoordinateController` (STOMP) | `LocationService`, `DriverPresenceService`, `NearbyDriversService` | Redis GEO (`drivers:geo`), presence/location keys, STOMP `/user/queue/rides/{rideId}/location`, `drivers` table |
| 18 | `uploads` | Signed Cloudinary upload parameters generation | `UploadSignatureController` | `CloudinarySignatureService` | Cloudinary API secret (local HMAC/SHA signature, no outbound HTTP) |
| 19 | `vehicle` | Customer garage CRUD, default vehicle, vehicle images, vehicle catalog & license-class eligibility | `VehicleController`, `VehicleCatalogController` | `VehicleService`, `VehicleCatalogService`, `LicenseEligibilityPolicy` | JDBC (`vehicles`, `vehicle_images`) |
| 20 | `voucher` | Voucher campaigns, customer claim, available vouchers, promo budget & ledger, welcome/post-ride grants | `AdminVoucherController`, `VoucherController` | `VoucherService`, `AdminVoucherService`, `PromoBudgetService`, `VoucherGrantService`, `VoucherGrantListener`, `VoucherCampaignPublishedListener` | JDBC (`vouchers`, `voucher_usages`, `promo_budget`, `promo_budget_ledger`, `voucher_campaign_budget`) |

---

## 2. High-Level Call & Dependency Graph

```text
[Client Apps: Mobile RN / Admin Web]
   │
   ├─ HTTP/REST (/api/**, /ekyc/driver-verification/v1/**)
   │    └─ SecurityConfig (Stateless) → FirebaseAuthFilter → AuthenticatedUserCache
   │         └─ @PreAuthorize / Filter-Only Controller
   │              └─ Service (@Transactional / JDBC / JPA)
   │                   ├─ PostgreSQL (Flyway V1..V42, lock_timeout=3s)
   │                   ├─ Redis (GEO, presence, offers, cache, Lua scripts)
   │                   ├─ ApplicationEventPublisher
   │                   │    ├─ BEFORE_COMMIT (MANDATORY): PaymentService.onRideCompleted -> WalletService.settle -> PromoBudgetService
   │                   │    ├─ AFTER_COMMIT: RideNotificationListener, PaymentNotificationListener, WithdrawalNotificationListener, TicketNotificationListener, DriverReleaseService, RideCancellationService, VoucherGrantListener, VoucherCampaignPublishedListener
   │                   │    ├─ @Async("chatTaskExecutor") + AFTER_COMMIT: ChatPushListener
   │                   │    └─ @EventListener (@Transactional): RideLifecycleEventListener.onNoDriverFound
   │                   └─ Outbound Clients: Firebase Auth/FCM, Goong V1/V2, Modal eKYC, Cloudinary (signing only)
   │
   └─ WebSocket STOMP (/ws-location)
        └─ StompAuthChannelInterceptor (Firebase token on CONNECT)
             ├─ Inbound: /app/location -> CoordinateController -> LocationService (Redis + STOMP fan-out)
             └─ Outbound: /user/queue/trip (offers/updates), /user/queue/rides/{rideId}/location (live GPS)
```

### Notable Cross-Module Dependencies (Audit Hotspots)
1. **`ride` ↔ `payment` ↔ `voucher`**: `RideProgressService.completeRide` publishes `RideCompleted`; `PaymentService.onRideCompleted` runs `BEFORE_COMMIT` (`Propagation.MANDATORY`) and calls `WalletService.settle` + `PromoBudgetService` in the same DB transaction. `VoucherGrantListener` runs `AFTER_COMMIT`.
2. **`ride` ↔ `matching` ↔ `tracking`**: `RideService.createRide` writes `ride_dispatch_outbox`; `RideDispatchOutboxWorker` (and `ScheduledRideScheduler`, `RideDispatchRecoveryTask`) invoke `MatchingService` → `SequentialDispatchService` → Redis Lua scripts + `ride_dispatch_offers` + `LocationService`. On terminal ride status, `DriverReleaseService` and `RideCancellationService` (`AFTER_COMMIT`) release Redis/DB driver state.
3. **`admin` → `ride` & `tracking`**: `AdminRideService.assignDriver` calls `RideStateMachine.adminAssignDriver` (bypasses `RideStatusTransitions` map) and updates Redis driver status directly (noted `'ON_TRIP'` vs `'BUSY'` — queued for Phase 1 audit).
4. **`driver/verification` → `driver`, `vehicle`, `ekyc`**: Verification approval updates `drivers` verification status, `driver_vehicle_skills`, and `driver_capability_preferences`, governing dispatch eligibility (`DriverRideEligibilityService`).
5. **`voucher` self-nesting**: `VoucherService.listAvailable` (`@Transactional(readOnly = true)`) invokes `VoucherGrantService.grantWelcome` (`REQUIRES_NEW`).

---

## 3. Trust Boundaries, Sensitive Data & Actor-Owned Resources

### Trust Boundaries
1. **Unauthenticated Edge**: `/api/auth/*/login`, `/api/auth/*/register`, `/api/vehicle-catalog` (verify exact `permitAll` rules in `SecurityConfig` during Phase 1/2), `/actuator/health`, Swagger/OpenAPI (note: disabled in `application-prod.properties`, while `docker-compose.production.yml` defaults `SPRING_PROFILES_ACTIVE=production`).
2. **Firebase Token Verification Boundary**: `FirebaseAuthFilter` (HTTP) and STOMP `CONNECT` interceptor. Note: `FirebaseAuthFilter` calls `verifyIdToken(idToken)` without `checkRevoked=true` (unlike `AuthService.verifyToken` which passes `true`) and caches principal in `AuthenticatedUserCache`.
3. **Role & Ownership Boundary**: `ROLE_CUSTOMER`, `ROLE_DRIVER`, `ROLE_ADMIN`. Multiple controllers rely on `(filter-only)` + in-service actor checks instead of `@PreAuthorize` (e.g., `RideController`, `RideProgressController`, `RideHandoverController`, `HandoverCorrectionController`, `SupportController`, `VoucherController`, `NotificationHistoryController`, `UploadSignatureController`).
4. **External Provider Boundary**: Firebase Auth/FCM, Goong Maps/Places API, Modal eKYC HTTP service, Cloudinary signed upload params, optional external Verification Sync service.
5. **Dev/Demo Guard Boundary**: Mock wallet top-up endpoints (`MockWalletController`, `DriverMockTopUpController`, `CustomerMockTopUpController`) gated by `@Profile("dev", "test", "demo")` and `wallet.mock-topup-enabled=true`.

### Sensitive Data & PII Inventory
- **Identity / eKYC (High Sensitivity)**: CCCD numbers, GPLX numbers, full name, DOB, address, QR payloads, face embeddings/scores, encrypted capture files under `data/driver-verification-captures/`, HMAC lookup hashes (`DATXE_VERIFICATION_LOOKUP_HMAC_KEY` / `DATXE_VERIFICATION_ENCRYPTION_KEY`).
- **Financial**: Wallet balances (`CREDIT`, `CASH`, `CUSTOMER`), ledger entries, bank account numbers/names on `drivers` & `withdrawal_requests`, promo budget ledger.
- **Location & Tracking**: Live GPS coordinates in Redis and `rides` pickup/dropoff coordinates, route polylines (`ride_route_quotes`).
- **Communications & Media**: In-ride `chat_messages`, `support_ticket_messages`, handover photos, vehicle images, device FCM tokens.

### Actor-Owned Resources
- **Customer-owned**: `customers`, `vehicles`, `vehicle_images`, `rides` (as `customer_id`), `wallets` (`CUSTOMER`), `voucher_usages`, `ratings` (given/received), `support_tickets`, `chat_messages`, `notifications`, `device_tokens`.
- **Driver-owned**: `drivers`, `driver_vehicle_skills`, `driver_capability_preferences`, `driver_verification_applications` (+ captures/submissions), `rides` (as `driver_id`), `ride_dispatch_offers`, `wallets` (`CREDIT`, `CASH`), `withdrawal_requests`, `ratings`, `support_tickets`, `chat_messages`, `notifications`, `device_tokens`.
- **Admin-managed**: `admins`, `pricing_rules`, `vouchers`, `promo_budget`, `voucher_campaign_budget`, `admin_customer_actions`, verification decisions, withdrawal reviews, ride manual assign/cancel, ticket claim/reply, rating visibility.

---

## 4. Database Schema, Entities, Locks & Redis Architecture

### JPA Entities (16) vs Flyway-Managed JDBC Tables (`V1..V42`)
- **16 JPA `@Entity` classes**: `admins`, `chat_messages`, `conversations`, `customers`, `drivers`, `device_tokens`, `wallets`, `wallet_transactions`, `withdrawal_requests`, `pricing_rules`, `handover_photos`, `rides`, `ride_status_histories`, `vehicle_handover_reports`, `support_tickets`, `support_ticket_messages`.
- **JDBC-managed tables (Flyway `V1..V42`)**: `notifications`, `payment_transactions` (no active live gateway), `payments`, `ratings`, `vehicle_images`, `vehicles`, `voucher_usages`, `vouchers`, `driver_vehicle_skills`, `admin_customer_actions`, `ride_create_idempotency`, `ride_dispatch_outbox`, `account_cleanup_outbox`, `ride_route_quotes`, `support_ticket_images`, `handover_revisions`, `ride_dispatch_offers`, `ride_wallet_settlements`, `driver_mock_topups`, `customer_mock_topups`, `payment_method_changes`, `promo_budget`, `promo_budget_ledger`, `voucher_campaign_budget`, `verification_sync_checkpoint`, `driver_verification_projection`, `driver_capability_preferences`, `driver_verification_applications`, `driver_verification_captures`, `driver_verification_submissions`, `driver_verification_submit_keys`, `driver_verification_audit`, `driver_verification_upload_attempts`.
- **Dropped legacy table**: `driver_documents` (`V42__remove_legacy_driver_verification.sql`).

### Critical DB Uniqueness, Check Constraints & Locking
- **Active ride uniqueness (`V15`)**: `uq_rides_active_customer`, `uq_rides_active_driver` (partial unique indexes on active statuses).
- **Dispatch offer uniqueness (`V15`)**: `uq_dispatch_offered_ride`, `uq_dispatch_offered_driver` (`WHERE status='OFFERED'`).
- **Money & Settlement guards (`V1`, `V18`, `V19`, `V22`, `V29`)**:
  - `wallets_balance_check`: `balance >= 0 OR wallet_type = 'CREDIT'`
  - `uq_wallet_trip_earning_ride`, `uq_wallet_customer_ride_type`, `uq_withdrawal_ledger`, `uq_promo_ledger_ride_action`
  - `ride_wallet_settlements` (idempotent per-ride settlement record)
- **Voucher & Garage guards (`V28`, `V30`, `V37`)**: `uq_voucher_usages_ride`, `uq_voucher_campaign_customer_claim`, `vehicles_plate_number_key` (`WHERE archived_at IS NULL`), `uq_garage_owner_default`.
- **Verification guards (`V38`)**: `uq_driver_verification_active_capture`, `driver_verification_submit_keys` (idempotency), identity lock on normalized CCCD/GPLX hashes.
- **Locking & Timeouts**: PostgreSQL `lock_timeout=3s` configured; pessimistic locks (`FOR UPDATE`) used on `wallets`, `promo_budget`, `rides`, `driver_verification_applications`, and outbox `SKIP LOCKED` polling. Optimistic locking (`@Version`) on `rides` / JPA entities where declared.

### Redis Keys, TTLs & Lua Scripts
- **Geo & Presence**: `drivers:geo` (GEO set), driver status/location/vehicle-type metadata hashes, stale cleanup every `10s` (`LocationService.cleanupStaleDrivers`).
- **Sequential Dispatch**: Offer locks, candidate queues, offer expiry ZSET polled every `1s` (`OfferTimeoutTask` with `offerGrace`), atomic Lua scripts under `src/main/resources/` (`dispatch_*.lua`, tested by `src/test/python/test_dispatch_presence.py`).
- **Caching**: Place query cache (`PlaceQueryCache`), `AuthenticatedUserCache` (in-memory Caffeine/Spring cache — verify eviction on status/role change in Phase 1/2).

### Schedulers (6 Active + 1 Conditional)
All share `ThreadPoolTaskScheduler(poolSize = 4)` in `config/SchedulingConfig.kt`:
1. `AccountCleanupOutboxWorker.processPendingCleanup` — `fixedDelay` `1000ms`
2. `OfferTimeoutTask.handleExpiredOffers` — `fixedDelay` `1000ms`
3. `RideDispatchOutboxWorker.dispatchPendingRides` — `fixedDelay` `1000ms`
4. `RideDispatchRecoveryTask.recoverLostDispatches` — `fixedDelay` `10000ms`
5. `LocationService.cleanupStaleDrivers` — `fixedDelay` `10000ms`
6. `ScheduledRideScheduler.processDueRides` — `fixedDelay` `30000ms`
7. *(Conditional `app.verification-sync.enabled=true`)* `VerificationSync.reconcile` — `fixedDelay` `2000ms`
