# PtitGo Backend Test Inventory & Baseline Execution

- **Run ID**: `2026-11-23-5d31630`
- **Baseline Date**: `2026-11-23`
- **Test Suites Discovered**: **80** Kotlin JUnit 5 test classes (`302` test methods; +13 regression suites / +46 tests across Batch 1A–P2 Sprint) + **1** Python Lua unittest file (`src/test/python/test_dispatch_presence.py`).

---

## 1. Baseline Command Execution Results

| Command | Outcome | Exact Counts / Observation | Environment Notes |
|---|---|---|---|
| `.\gradlew.bat test --info` (parsed via `build/test-results/test/TEST-*.xml`) | **BUILD SUCCESSFUL** (0 failures, 0 errors; 61 tests skipped by env guards) | **67** XML suites, **256** total, **195** passed, **0** failed, **0** errors, **61** skipped | `TEST_DB_URL`, `VERIFICATION_E2E_PG_URL`, `VERIFICATION_SYNC_TEST_DB`, `RUN_GOONG_LIVE_TEST`, `DATXE_EKYC_SMOKE`, `SPRING_PROFILES_ACTIVE` were all `<unset>`. |
| `.\gradlew.bat test --rerun-tasks` (on `ptitgo_audit_test` PG 17-alpine, NO `public` schema reset, after isolation fixes on `DriverVerificationIdentityLockTest` & `VerificationProjectionPostgresTest`) | **BUILD SUCCESSFUL** (0 failures, 0 errors, 253 passed, 3 skipped) | **67** XML suites, **256** total, **253** passed, **0** failed, **0** errors, **3** skipped (live Goong/Modal APIs) | Both `ptitgo_audit_tester` & `sync_test` verified denied on dev DB `datxe`. Redis mocked (`redis://127.0.0.1:1`). **42/42 Flyway migrations (`V1..V42`) succeeded.** Verified repeatable back-to-back (2 consecutive runs of both verification suites + full suite with zero schema resets). |
| `python -m unittest discover -s src/test/python -v` | **ERROR (1)** — Blocked by host dependency | `TypeError: LoadLibrary() argument 1 must be str, not None` (`liblua5.4` not installed on Windows host) | Lua dispatch script tests (`dispatch_*.lua`) could not execute on this host. |

---

## 2. Full Test Suite Categorization (67 Kotlin Classes + 1 Python File)

### 2.1 Unit / Pure Logic / Mocked Service Tests (Executed in Baseline — 195 Passed)
| Test Class | Total | Passed | Skipped | Category / Focus |
|---|---:|---:|---:|---|
| `admin.AdminRideCapabilitiesHttpTests` | 2 | 2 | 0 | WebMVC/HTTP (mocked) — admin ride filter capabilities |
| `admin.AdminRideRedisReassignTests` | 4 | 4 *(Redis 7)* | 0 | Disposable Redis 7 (`TEST_REDIS_PORT`) — `F-001` Lua reassign & recovery |
| `auth.security.AuthenticatedUserCacheTests` | 1 | 1 | 0 | Unit — auth principal cache |
| `chat.ChatServiceTests` | 6 | 6 | 0 | Unit (mocked) — chat send/read/idempotency |
| `common.util.MoneyRoundingTests` | 2 | 2 | 0 | Unit — money rounding |
| `driver.DriverBankProfileTests` | 3 | 3 | 0 | Unit — driver bank profile validation |
| `driver.verification.CccdQrBackendTest` | 7 | 7 | 0 | Unit — CCCD QR parsing & validation |
| `driver.verification.DriverCapabilitiesTest` | 3 | 3 | 0 | Unit — driver vehicle capability preferences |
| `driver.verification.DriverRideEligibilityTest` | 4 | 4 | 0 | Unit — ride eligibility by license/capability |
| `driver.verification.DriverVerificationEligibilityTest` | 4 | 4 | 0 | Unit — verification eligibility gating |
| `driver.verification.DriverVerificationIdentityLockTest` | 4 | 3 | 1 | Hybrid — 3 unit pass, 1 PG-gated skipped |
| `driver.verification.DriverVerificationWorkflowTest` | 7 | 7 | 0 | Unit (mocked) — native verification workflow |
| `driver.verification.LicenseEligibilityPolicyTest` | 6 | 6 | 0 | Unit — GPLX class → vehicle group mapping |
| `driver.verification.VerificationDecisionRequestTests` | 2 | 2 | 0 | Unit — decision request validation |
| `driver.verification.VerificationSyncTransportTest` | 1 | 1 | 0 | Unit (mocked HTTP) — verification sync client |
| `ekyc.EkycClientTest` | 8 | 8 | 0 | Unit (mocked HTTP) — Modal eKYC client |
| `matching.DriverSelectionTests` | 5 | 5 | 0 | Unit — candidate driver ranking/selection |
| `matching.SequentialDispatchTests` | 18 | 18 | 0 | Unit (mocked) — sequential dispatch & timeout |
| `notification.FcmMessageFactoryTests` | 5 | 5 | 0 | Unit — FCM payload mapping |
| `notification.HandoverNotificationTests` | 3 | 3 | 0 | Unit — handover notification listener |
| `notification.NotificationHistoryMappingTests` | 4 | 4 | 0 | Unit — notification history DTO mapping |
| `notification.NotificationParallelTests` | 2 | 2 | 0 | Unit — parallel notification fan-out |
| `notification.ws.StompAuthInterceptorSecurityTests` | 4 | 4 | 0 | In-memory Spring STOMP broker — `F-014` SEND spoofing & SUBSCRIBE authz |
| `payment.CustomerWalletEndpointsTests` | 3 | 3 | 0 | WebMVC/HTTP (mocked) — customer wallet endpoints |
| `payment.DriverCreditAccessTests` | 1 | 1 | 0 | Unit — driver credit wallet gating |
| `payment.PaymentSettlementTests` | 1 | 1 | 0 | Unit (mocked) — payment settlement |
| `payment.WalletErrorResponseTests` | 3 | 3 | 0 | WebMVC/HTTP — wallet error responses |
| `payment.WalletServiceTests` | 14 | 14 | 0 | Unit (mocked) — wallet settlement & balance logic |
| `payment.WithdrawalNotificationTests` | 3 | 3 | 0 | Unit — withdrawal review notification |
| `payment.WithdrawalServiceTests` | 5 | 5 | 0 | Unit (mocked) — withdrawal request & review |
| `payment.ZeroVndVoucherWalletTests` | 4 | 4 | 0 | Unit (mocked) — 0 VND voucher-covered rides |
| `pricing.PricingRideQuoteTests` | 3 | 3 | 0 | Unit — fare quote calculation |
| `rating.CustomerReviewsTests` | 1 | 1 | 0 | Unit — customer review listing |
| `ride.FareRoundingTests` | 2 | 2 | 0 | Unit — ride fare rounding |
| `ride.HandoverCorrectionTests` | 3 | 3 | 0 | Unit — handover correction/revision flow |
| `ride.RideCancellationHttpTests` | 1 | 1 | 0 | WebMVC/HTTP — ride cancel endpoint |
| `ride.RideCreationValidatorTests` | 3 | 3 | 0 | Unit — ride creation validation |
| `ride.RideDetailResponseMappingTests` | 2 | 2 | 0 | Unit — ride detail mapping |
| `ride.RideDriverProfileTests` | 5 | 5 | 0 | Unit — driver profile snapshot in ride |
| `ride.RideHistoryFilterTests` | 4 | 4 | 0 | Unit — ride history filter parsing |
| `ride.RidePickupNoteTests` | 1 | 1 | 0 | Unit — pickup note handling |
| `ride.ScheduledRideSchedulerTests` | 3 | 3 | 0 | Unit (mocked) — scheduled ride polling |
| `routing.PlaceNearbyTests` | 4 | 4 | 0 | Unit (mocked) — nearby places |
| `routing.PlaceQueryCacheTests` | 3 | 3 | 0 | Unit — place query cache |
| `routing.PlaceSearchTests` | 7 | 6 | 1 | Hybrid — 6 unit pass, 1 live Goong skipped |
| `routing.RideRouteSnapshotTests` | 2 | 2 | 0 | Unit — route quote snapshot |
| `routing.VehicleRoutingTests` | 5 | 4 | 1 | Hybrid — 4 unit pass, 1 live Goong skipped |
| `support.ticket.TicketReplyPayloadTests` | 1 | 1 | 0 | Unit — ticket reply payload |
| `support.ticket.TicketServiceTests` | 6 | 6 | 0 | Unit (mocked) — threaded ticket service |
| `tracking.DriverLocationPolicyTests` | 1 | 1 | 0 | Unit — driver location policy |
| `tracking.DriverOnlineVehicleTypeTests` | 3 | 3 | 0 | Unit — online vehicle type validation |
| `tracking.LocationFreshnessTests` | 3 | 3 | 0 | Unit — location staleness check |
| `vehicle.GarageBookingRequirementTest` | 2 | 2 | 0 | Unit — garage vehicle requirement |
| `voucher.VoucherServiceTests` | 2 | 2 | 0 | Unit (mocked) — voucher selection |

### 2.2 Postgres / Flyway / Concurrency / Context / Live-API Tests (61 Skipped in Baseline)
| Test Class | Total | Skipped | Gate / Requirement |
|---|---:|---:|---|
| `DatxebackendApplicationTests` | 2 | 2 | Spring Boot full context / env guard |
| `admin.AdminDashboardPostgresTests` | 1 | 1 | Real PostgreSQL (`TEST_DB_URL`) |
| `admin.AdminFiltersPostgresTests` | 6 | 6 | Real PostgreSQL (`TEST_DB_URL`) |
| `driver.verification.DriverVerificationIdentityLockTest` | 4 *(1 skip)* | 1 | Real PostgreSQL (`VERIFICATION_E2E_PG_URL`) |
| `driver.verification.LegacyVerificationRemovalPostgresTest` | 1 | 1 | Real PostgreSQL (`VERIFICATION_E2E_PG_URL`) |
| `driver.verification.VerificationConcurrencyPostgresTest` | 6 | 6 | Real PostgreSQL (`VERIFICATION_E2E_PG_URL`) |
| `driver.verification.VerificationProjectionPostgresTest` | 2 | 2 | Real PostgreSQL (`VERIFICATION_SYNC_TEST_DB`) |
| `ekyc.EkycModalSmokeTest` | 1 | 1 | Live Modal eKYC (`DATXE_EKYC_SMOKE=true`) |
| `integration.IntegrationReleaseRegressionPostgresTests` | 1 | 1 | Real PostgreSQL (`TEST_DB_URL`) |
| `matching.DispatchPersistenceTests` | 7 | 7 | Real PostgreSQL (`TEST_DB_URL`) |
| `payment.VoucherSettlementPostgresTests` | 4 | 4 | Real PostgreSQL (`TEST_DB_URL`) |
| `payment.WalletPostgresTests` | 13 | 13 | Real PostgreSQL (`TEST_DB_URL`) |
| `ride.RideHistoryFilterPostgresTests` | 2 | 2 | Real PostgreSQL (`TEST_DB_URL`) |
| `ride.ScheduledRideBookingPostgresTests` | 1 | 1 | Real PostgreSQL (`TEST_DB_URL`) |
| `routing.PlaceSearchTests` | 7 *(1 skip)* | 1 | Live Goong API (`RUN_GOONG_LIVE_TEST=true`) |
| `routing.VehicleRoutingTests` | 5 *(1 skip)* | 1 | Live Goong API (`RUN_GOONG_LIVE_TEST=true`) |
| `support.SupportPersistenceTests` | 9 | 9 | Real PostgreSQL (`TEST_DB_URL`) |
| `voucher.VoucherReleasePostgresTests` | 2 | 2 | Real PostgreSQL (`TEST_DB_URL`) |

### 2.3 Python Lua Dispatch Test (1 File — Errored in Baseline)
- `src/test/python/test_dispatch_presence.py`: Verifies Redis Lua scripts (`dispatch_*.lua`) using `liblua5.4`. Failed to load on Windows because `liblua5.4` is unavailable.

---

## 3. High-Level Test Coverage Gaps Identified in Phase 0

1. **Zero Real PostgreSQL / Flyway Execution in Default Run**: All 15 Postgres/migration/concurrency test classes (`56` tests) were skipped because `TEST_DB_URL`, `VERIFICATION_E2E_PG_URL`, and `VERIFICATION_SYNC_TEST_DB` are unset. Real DB constraints, `FOR UPDATE` lock ordering, `lock_timeout=3s`, and `BEFORE_COMMIT` settlement rollbacks are unverified in default `./gradlew test`.
2. **Zero Real Redis / Lua Execution**: `test_dispatch_presence.py` errored due to missing `liblua5.4`, and no Java/Kotlin Testcontainers Redis test exists.
3. **Security / AuthZ / IDOR Gaps**: Almost no negative-path HTTP security tests exist for `(filter-only)` endpoints (`RideController`, `RideProgressController`, `RideHandoverController`, `HandoverCorrectionController`, `SupportController`, `VoucherController`, `NotificationHistoryController`, `UploadSignatureController`, `AuthController`).
4. **WebSocket STOMP Untested**: No tests for `/ws-location` STOMP auth handshake, `/app/location` ingestion, or `/user/queue/rides/{rideId}/location` subscription isolation.
5. **Thin Coverage on Core State Transitions & Admin Ops**: `RideProgressService`, `AdminRideService` (manual assign/cancel), `AdminCustomerController` (status/flag), `AdminDriverController` (force-offline), `AdminRatingService`, `PaymentMethodService` (switch race), `AccountCleanupOutboxWorker`, and `RideDispatchOutboxWorker`/`RideDispatchRecoveryTask` have minimal or no dedicated unit tests.

## Kiểm tra phát hành ngày 23/11/2026

- Runner dùng PostgreSQL 17 và Redis 7 disposable trên loopback. Chạy `gradlew.bat --offline build` với `TEST_DB_URL`, `VERIFICATION_E2E_PG_URL`, `VERIFICATION_SYNC_TEST_DB` và `TEST_REDIS_PORT` trỏ tới dịch vụ đó. Các cờ kiểm thử live Goong và Modal đặt `false`.
- Targeted tài chính. 29/29 sau 5 RED dự kiến. Gồm `WalletPostgresTests`, `WithdrawalServiceTests` và `AdminFiltersPostgresTests`.
- Targeted dispatch/lịch/GPS/tín dụng voucher. 44/44 trên 7 suite. Sau review F-003, `DispatchPersistenceTests` và `SequentialDispatchTests` đạt 27/27.
- Targeted tích hợp nâng cấp và CORS. `IntegrationReleaseRegressionPostgresTests`, `FrontendOriginConfigTests` đạt 3/3 sau khi điều chỉnh cách đếm dòng schema của Flyway trong test.
- Lua fixture độc lập. 11/11 trong container `python:3.11-slim` với `liblua5.4-0`. Các test Redis sống chạy trong Gradle qua Redis 7.
- Full lượt đầu. 324 test, 320 PASS, 1 FAIL và 3 SKIPPED. Test V43 yêu cầu đúng một migration, nhưng khi không giới hạn target đã áp dụng V43–V46. Đã đặt target V43 trong test chuyên biệt. Test tích hợp riêng tiếp tục chạy V33 → V46.
- Các ghi chú blocker về thiếu Lua, PostgreSQL và Redis ở phần đầu mô tả baseline lịch sử.
- Targeted hỗ trợ. Năm suite chạy thành công trên PostgreSQL disposable, gồm `SupportPersistenceTests`, `TicketServiceTests`, `P2GroupDSupportTests`, `TicketReplyPayloadTests` và `AdminFiltersPostgresTests`.
- Đối chiếu client. RN `npm run check` 320/320. goAdmin typecheck/lint và hai suite Vitest 12/12 ở lượt chạy lại với một worker. Lượt goAdmin trước đó 10/12, có voucher timeout và lỗi tìm ticket.
- Full cuối. `BUILD SUCCESSFUL`, 82 suite, 324 test, 321 PASS, 0 FAIL, 0 ERROR và 3 SKIPPED theo cờ live provider. PostgreSQL 17.11 xác nhận 46 migration thành công trên database mới, Redis 7.4.12 đã dùng cho các suite runtime.
