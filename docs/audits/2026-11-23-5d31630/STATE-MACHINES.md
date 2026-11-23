# PtitGo Backend State Machines Inventory

- **Run ID**: `2026-11-23-5d31630`
- **Purpose**: Baseline map of all stateful aggregates, their transitions, guards, persisted state, side effects, test coverage, and observed documentation-vs-code contradictions (for validation in Phase 1–2).

---

## 1. Ride Lifecycle (`RideStatus` — `rides.status`, `V15` CHECK constraint)

14 states: `PENDING`, `SEARCHING`, `DRIVER_ASSIGNED`, `DRIVER_ARRIVING`, `DRIVER_ARRIVED`, `VEHICLE_HANDOVER`, `IN_PROGRESS`, `ARRIVED_DESTINATION`, `VEHICLE_RETURNED`, `COMPLETED`, `CANCELLED`, `NO_DRIVER_FOUND`, `EXPIRED`, `FAILED_SYSTEM`.
Terminal states: `COMPLETED`, `CANCELLED`, `NO_DRIVER_FOUND`, `EXPIRED`, `FAILED_SYSTEM`.

| From | Event / Trigger | Actor / Guard | To | Persisted State | Side Effects | Test Coverage |
|---|---|---|---|---|---|---|
| *(none)* | `createRide` | `CUSTOMER` (1 active ride, valid vehicle, no `VNPAY`/`MOMO`) | `PENDING` | `rides`, `ride_status_histories`, `ride_create_idempotency`, `ride_dispatch_outbox` (if immediate), `voucher_usages` | Outbox worker picks up immediate ride; scheduled ride waits for `ScheduledRideScheduler` | Unit (`RideCreationValidatorTests`); PG skipped |
| `PENDING` | Dispatch start | `SYSTEM` (outbox/scheduler/recovery) | `SEARCHING` | `rides.status`, `ride_status_histories` | Sequential offer loop in Redis + `ride_dispatch_offers` | `SequentialDispatchTests`, `ScheduledRideSchedulerTests` |
| `SEARCHING` | Offer accepted | `DRIVER` (active offer, eligible, 1 active ride) | `DRIVER_ASSIGNED` | `rides.driver_id`, `rides.status`, `ride_dispatch_offers.status=ACCEPTED`, `drivers.status=BUSY` | Redis driver `BUSY`, STOMP/FCM to customer | `SequentialDispatchTests`; PG skipped |
| `NO_DRIVER_FOUND` / `DRIVER_ASSIGNED` / `DRIVER_ARRIVING` | Admin manual assign | `ADMIN` (`RideStateMachine.adminAssignDriver` — **bypasses `RideStatusTransitions` map**) | `DRIVER_ASSIGNED` | `rides.driver_id`, `rides.status`, `ride_status_histories` | Redis driver set to `'ON_TRIP'` (verified non-breaking in `1.08`), notification; see `F-001`, `F-002` | `AdminRideCapabilitiesHttpTests` (partial) |
| `DRIVER_ASSIGNED` | `start-arriving` | Assigned `DRIVER` | `DRIVER_ARRIVING` | `rides.status`, `ride_status_histories` | `RideStatusChanged` → FCM/STOMP | Partial |
| `DRIVER_ARRIVING` | `arrived` | Assigned `DRIVER` | `DRIVER_ARRIVED` | `rides.status`, `arrived_at`, `ride_status_histories` | `RideStatusChanged` → FCM/STOMP | Partial |
| `DRIVER_ARRIVED` | Pickup handover created (`PICKUP_HANDOVER_CREATED`) | Assigned `DRIVER` submits pickup report; `CUSTOMER` confirms (`confirmedByCustomer=true`) while in `VEHICLE_HANDOVER` before `START_TRIP` | `VEHICLE_HANDOVER` | `vehicle_handover_reports`, `handover_photos`, `rides.status` | `RideStatusChanged`; `PickupHandoverConfirmed` on customer confirm → FCM | `HandoverCorrectionTests`, `HandoverNotificationTests` |
| `VEHICLE_HANDOVER` | `start` (`START_TRIP`) | Assigned `DRIVER` (`mayDriveForRide(starting=true)` + `PICKUP` report `confirmedByCustomer=true`) | `IN_PROGRESS` | `rides.status`, `started_at`, `ride_status_histories` | `RideStatusChanged` → FCM | Partial |
| `IN_PROGRESS` | `arrive-destination` (`ARRIVE_DESTINATION`) | Assigned `DRIVER` | `ARRIVED_DESTINATION` | `rides.status`, `ride_status_histories` | `RideStatusChanged` → FCM | Partial |
| `ARRIVED_DESTINATION` | Dropoff handover created (`DROPOFF_HANDOVER_CREATED`) | Assigned `DRIVER` submits dropoff report; `CUSTOMER` confirms (`confirmedByCustomer=true`) while in `VEHICLE_RETURNED` before `COMPLETE` | `VEHICLE_RETURNED` | `vehicle_handover_reports`, `handover_photos`, `rides.status` | `RideStatusChanged`; `DropoffHandoverConfirmed` on customer confirm → FCM | `HandoverCorrectionTests`, `HandoverNotificationTests` |
| `VEHICLE_RETURNED` | `complete` (`COMPLETE`) | Assigned `DRIVER` (`DROPOFF` report `confirmedByCustomer=true`) | `COMPLETED` | `rides.status`, `completed_at`, `final_fare`, `payments`, `wallets`, `wallet_transactions`, `ride_wallet_settlements`, `promo_budget_ledger` | `BEFORE_COMMIT`: `PaymentService#onRideCompleted`; `AFTER_COMMIT`: `DriverReleaseService`, `VoucherGrantListener`, `RideNotificationListener` | `PaymentSettlementTests`, `WalletPostgresTests`, `VoucherSettlementPostgresTests`, `ZeroVndVoucherWalletTests` |
| `PENDING`, `SEARCHING`, `DRIVER_ASSIGNED`, `DRIVER_ARRIVING`, `DRIVER_ARRIVED`, `VEHICLE_HANDOVER`, `NO_DRIVER_FOUND` *(disallowed once `IN_PROGRESS`+)* | `cancel` | `CUSTOMER` / `DRIVER` / `ADMIN` / `SYSTEM` (driver cancel from `DRIVER_ASSIGNED`/`DRIVER_ARRIVING` triggers `retryDispatch` → `SEARCHING` or `NO_DRIVER_FOUND`) | `CANCELLED` *(or `SEARCHING`/`NO_DRIVER_FOUND` on driver retryDispatch)* | `rides.status`, `cancel_reason`, voucher/promo release | `AFTER_COMMIT`: `DriverReleaseService`, `RideCancellationService`, `RideNotificationListener` | `RideCancellationHttpTests` |
| `SEARCHING` | Candidates exhausted | `SYSTEM` (`RideNoDriverFound`) | `NO_DRIVER_FOUND` | `rides.status`, voucher/promo release | `RideLifecycleEventListener`, notification | `SequentialDispatchTests` |
| `PENDING` / `SEARCHING` | Scheduled/search expiry | `SYSTEM` | `EXPIRED` / `FAILED_SYSTEM` | `rides.status`, voucher/promo release | Cleanup listeners + notification | `ScheduledRideSchedulerTests` |

---

## 2. Driver Presence & Dispatch Offer (`drivers.status` + Redis + `ride_dispatch_offers.status`)

- **DB `drivers.status` (`V19` CHECK)**: `OFFLINE`, `ONLINE`, `BUSY`, `BLOCKED`.
- **Offer statuses (`V15`)**: `OFFERED`, `ACCEPTED`, `REJECTED`, `EXPIRED`, `CANCELLED`.

| Aggregate | From → To | Event / Actor | Guard / Constraint | Side Effects |
|---|---|---|---|---|
| Driver | `OFFLINE → ONLINE` | `DRIVER` (`PUT /api/driver/availability`) | Verified (`DriverVerificationEligibility`), credit wallet within debt limit, fresh location, enabled capability | Added to Redis `drivers:geo` + status `ONLINE` |
| Driver | `ONLINE → OFFLINE` | `DRIVER` / `ADMIN` (`force-offline`) / `LocationService#cleanupStaleDrivers` | Not `BUSY` *(verify force-offline on `BUSY` in Phase 1)* | Removed from Redis `drivers:geo`, pending offers cleared |
| Driver | `ONLINE → BUSY` | Offer accept / Admin assign | `uq_rides_active_driver` | Redis status updated (`BUSY` on accept; `'ON_TRIP'` noted in `AdminRideService`) |
| Driver | `BUSY → ONLINE` / `OFFLINE` | `DriverReleaseService` (`AFTER_COMMIT` on terminal ride) | Ride reached terminal status | DB & Redis status released |
| Offer | `(none) → OFFERED` | `SequentialDispatchService` | `uq_dispatch_offered_ride`, `uq_dispatch_offered_driver` | Redis offer key + expiry ZSET + STOMP/FCM |
| Offer | `OFFERED → ACCEPTED` | `DRIVER` (`POST /offers/{rideId}/accept`) | Offer not expired, ride `SEARCHING` | Ride `DRIVER_ASSIGNED`, driver `BUSY` |
| Offer | `OFFERED → REJECTED` / `EXPIRED` | `DRIVER` reject / `OfferTimeoutTask` | Grace window elapsed for timeout | Advances to next candidate or `RideNoDriverFound` |
| Offer | `OFFERED → CANCELLED` | `RideCancellationService` | Ride cancelled while `SEARCHING` | Cleans Redis offer state |

---

## 3. Payment & Settlement (`payments` + `ride_wallet_settlements` + `payment_method_changes`)

- **Supported methods**: `CASH`, `WALLET` (`VNPAY`/`MOMO` rejected).
- **Transitions**:
  - Pre-completion: `CASH ↔ WALLET` via `PUT /api/customer/rides/{rideId}/payment/method` (`PaymentMethodService`, logged in `payment_method_changes`).
  - Completion (`BEFORE_COMMIT` `MANDATORY`): `PaymentService#onRideCompleted` → `WalletService#settle` → writes `ride_wallet_settlements` + `wallet_transactions` + `promo_budget_ledger`.
    - `CASH`: deducts platform commission from driver `CREDIT` wallet; credits voucher subsidy to driver `CASH` wallet if applicable; payment awaits driver cash confirmation (`POST /api/driver/rides/{rideId}/payment/cash/confirm`).
    - `WALLET`: debits customer `CUSTOMER` wallet, credits driver `CASH` wallet (net of commission); **if customer balance insufficient, code falls back to `CASH` settlement** (`WalletService.kt:131-147`).

---

## 4. Wallet Withdrawal (`withdrawal_requests.status`, `V22` CHECK)

States: `PENDING`, `SUCCESS`, `REJECTED`.

| From | Event | Actor / Guard | To | Persisted State & Side Effects |
|---|---|---|---|---|
| *(none)* | `create` | `DRIVER` (sufficient `CASH` wallet balance, bank info present) | `PENDING` | Locks `CASH` wallet, debits withdrawal amount (`uq_withdrawal_ledger`), creates `withdrawal_requests` |
| `PENDING` | `review(APPROVE)` | `ADMIN` | `SUCCESS` | Updates status + reviewer; `AFTER_COMMIT` `WithdrawalNotificationListener` |
| `PENDING` | `review(REJECT)` | `ADMIN` (non-blank `rejection_reason` per `V22` CHECK) | `REJECTED` | Refunds driver `CASH` wallet (`uq_withdrawal_ledger`), updates status; `AFTER_COMMIT` notification |

---

## 5. Native Driver Verification (`driver_verification_applications` — `V38`)

- **States (verified in `1.03`)**: `DRAFT`, `SUBMITTED`, `UNDER_REVIEW`, `NEEDS_MORE_INFO`, `APPROVED`, `REJECTED`, `REVOKED` (plus `reverify` from `APPROVED`/`REJECTED` → `DRAFT`, and `cancel-reverify` from `DRAFT` → `APPROVED`).
- **Guards**: Per-slot active capture uniqueness (`uq_driver_verification_active_capture`), idempotency key (`driver_verification_submit_keys`), identity lock on HMAC hashes of normalized CCCD/GPLX across drivers.
- **Side effects on `APPROVED`**: Updates `drivers` verification status, derives `driver_vehicle_skills` & `driver_capability_preferences` from verified license classes, writes `driver_verification_audit`.

---

## 6. Other Stateful Aggregates

- **Handover Reports & Revisions (`vehicle_handover_reports`, `handover_revisions`)**: `PENDING_CONFIRMATION` → `CORRECTION_REQUESTED` → `REVISED` → `CONFIRMED` (per phase `PICKUP` / `DROPOFF`).
- **Support Tickets (`support_tickets` — `V14`, `V25`, `V26`)**: Statuses `OPEN`, `IN_PROGRESS`, `WAITING_FOR_USER`, `RESOLVED`, `CLOSED` + threaded admin claim (`handled_by` via `FOR UPDATE`) & user reply (currently unlocks and reopens even `CLOSED` tickets without `FOR UPDATE` — see `F-037`; legacy `PATCH` bypasses claim guard & omits `TicketReplied` — see `F-038`).
- **Voucher Campaigns (`vouchers`, `voucher_campaign_budget`, `promo_budget`)**: Campaign status (`ACTIVE` / `PAUSED` / `EXPIRED` / `DEPLETED` — verify exact enum in Phase 1.14), customer claim (`uq_voucher_campaign_customer_claim`), ride usage (`uq_voucher_usages_ride`), promo budget reserve/settle/release (`uq_promo_ledger_ride_action`).
- **Customer Account (`customers.status`, `UserStatus` enum)**: `ACTIVE` ↔ `INACTIVE` / `BANNED` + flag state (`admin_customer_actions`).
- **In-Ride Conversations (`conversations` + `chat_messages`, `V1`, `V9`, `V17`)**: Implicit lifecycle derived from `(ride_id, driver_id)` + `rides.status`: `NONE` (`PENDING`/`SEARCHING`) → `OPEN` (`DRIVER_ASSIGNED`..`VEHICLE_RETURNED`, upserted on driver assign/reassign) → `PRESERVED_ARCHIVED` (on driver cancel/reassign, old driver blocked, new driver gets fresh row; see `F-036`) or `CLOSED` (terminal ride states: send/upload rejected `409 CHAT_CLOSED`, read/markRead allowed).

---

## 7. Contradicted Rules Discovered Between Documentation & Code (Phase 0 Notes)

| # | Document Claim | Actual Code Behavior | Status |
|---|---|---|---|
| **C-1** | `README.md` states only `CASH` payment is supported and no admin voucher CRUD exists. | Code implements `WALLET` settlement, `CASH↔WALLET` switching, withdrawals, mock top-ups, and full `AdminVoucherController` + `PromoBudgetService`. | `DOC_STALE` — verify in `1.13`, `1.14`, `1.15` |
| **C-2** | `docs/WALLET_MODULE.md` states insufficient `WALLET` balance at ride completion rolls back ride completion. | `WalletService.settle` (`WalletService.kt:131-147`) automatically falls back to `PaymentMethod.CASH` settlement instead of rolling back. | `PRODUCT_DECISION_REQUIRED` — verify in `1.13` |
| **C-3** | `docker-compose.production.yml` defaults `SPRING_PROFILES_ACTIVE=production`. | Swagger/OpenAPI disabling is configured in `application-prod.properties` (profile `prod`), while `application-production.properties` sets `require-protected-storage=true`. | `NEEDS_VALIDATION` — verify in `2.01`, `2.10` |
