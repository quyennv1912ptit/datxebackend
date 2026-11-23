# Fast-Track Fix Batch 1A Report (`F-014`, `F-001`)

- **Run ID**: `2026-11-23-5d31630`
- **Branch / Baseline HEAD**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Mode**: `FIX` (TDD: RED → minimal FIX → GREEN; no commits/pushes/deploys; 3 baseline test-only edits preserved; `F-016` intentionally excluded)

---

## 1. Files Modified & Added

### Production Files Modified (3 files: `+19 / -4`)
1. [StompAuthInterceptor.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/notification/ws/StompAuthInterceptor.kt#L38-L82): Added `requireAllowedSendDestination` on `StompCommand.SEND` allowing only `/app/location` and requiring an authenticated `Recipient.Driver` principal; rejects all direct broker/user/unsupported client `SEND` destinations (`/user/**`, `/queue/**`, `/topic/**`, `/app/unknown`, `null`).
2. [AdminRideService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/admin/service/AdminRideService.kt#L338-L343): Fixed `COMMIT_ADMIN_RIDE_ASSIGNMENT` Lua script `ZREM available_drivers` from `ARGV[2]` (`rideId`) to `ARGV[3]` (`newDriverId`), and fixed old driver `current_ride` ownership check from `GET KEYS[8] == ARGV[3]` to `GET KEYS[8] == ARGV[2]` (`rideId`).
3. [RideDispatchRecoveryTask.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/ride/service/RideDispatchRecoveryTask.kt#L113-L115): Extended orphaned-driver reconciliation guard so drivers in `OFFLINE` status with a stale `driver:{id}:current_ride` key are also reconciled via `driverRelease.reconcileIdle`.

### Regression Tests Added (2 suites, 8 tests)
1. [StompAuthInterceptorSecurityTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/notification/ws/StompAuthInterceptorSecurityTests.kt) (4 tests): In-memory Spring STOMP broker (`DefaultSimpUserRegistry` + `UserDestinationMessageHandler` + `SimpleBrokerMessageHandler`) testing Principal A → Principal B spoofing (`PAYMENT_UPDATED`, `CHAT_MESSAGE`, `RIDE_ASSIGNED`, GPS location), direct broker/unsupported `SEND`, unauthorized `SUBSCRIBE`, role enforcement on `/app/location`, and preserved server-side delivery.
2. [AdminRideRedisReassignTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/admin/AdminRideRedisReassignTests.kt) (4 tests): Executes exact production `COMMIT_ADMIN_RIDE_ASSIGNMENT` and `RELEASE_ADMIN_DRIVER_RESERVATION` Lua scripts, `AdminRideService#assignDriver`, and `RideDispatchRecoveryTask` against disposable `redis:7-alpine` (`TEST_REDIS_PORT=6399`, non-6379 guarded).

---

## 2. TDD Verification Results

| Finding | RED Result | GREEN Targeted Result | Status |
|---|---|---|---|
| **`F-014`** (P1) | `StompAuthInterceptorSecurityTests`: **4 run, 3 failed** (Principal A spoofed `PAYMENT_UPDATED`/`CHAT_MESSAGE`/`RIDE_ASSIGNED` to Principal B; direct broker `SEND` allowed; customer `SEND` to `/app/location` allowed). | `notification.*`, `tracking.*`, `chat.*`: **`BUILD SUCCESSFUL`** — **31 passed, 0 failed, 0 skipped** (all 4 new STOMP security tests + GPS/chat/notification suites GREEN). | **`VERIFIED_FIXED`** |
| **`F-001`** (P1) | `AdminRideRedisReassignTests` on disposable Redis 7 (`127.0.0.1:6399`): **`production COMMIT_ADMIN_RIDE_ASSIGNMENT` FAILED** (`driver:10:current_ride` remained `"99"` instead of `null`). | `AdminRideRedisReassignTests`, `AdminRideCapabilitiesHttpTests`, `matching.*` on disposable Redis 7 (`127.0.0.1:6399`): **`BUILD SUCCESSFUL`** — **4/4 new Redis 7 tests passed** (Ride 99: Driver 10 → Driver 20 cleanup, `ZREM` uses `driverId` not `rideId`, retry-safe no-op, failure/ownership guards, `OFFLINE` stale `current_ride` recovery). *(7 PG-gated `DispatchPersistenceTests` skipped in this targeted run.)* | **`VERIFIED_FIXED`** |

---

## 3. Full Gradle Suite Results

- **Command**: `.\gradlew.bat test --rerun-tasks` with `TEST_REDIS_HOST=127.0.0.1`, `TEST_REDIS_PORT=6399` (disposable `redis:7-alpine`, stopped & removed after run); `RUN_GOONG_LIVE_TEST` and `DATXE_EKYC_SMOKE` unset.
- **Outcome**: **`BUILD SUCCESSFUL`** (1m 27s) — **69 suites, 264 total: 203 passed, 0 failed, 0 errors, 61 skipped**.
- **Skipped / Blocked Breakdown (61)**:
  - **58 PostgreSQL-gated tests (`BLOCKED` in this shell)**: `ptitgo_audit_tester` isolation on `ptitgo_audit_test` was confirmed (`datxe` access denied), but host TCP authentication requires `TEST_DB_PASSWORD` / `VERIFICATION_SYNC_TEST_PASSWORD` which are not set in this shell environment (per rules, `.env` was not read and DB roles were not reset).
  - **3 Live External API tests (`SKIPPED`)**: `EkycModalSmokeTest` (1), `PlaceSearchTests` (1), `VehicleRoutingTests` (1).
  - **Python Lua unittest (`src/test/python/test_dispatch_presence.py`)**: Remains `BLOCKED` on Windows host (`liblua5.4` unavailable).

---

## 4. Remaining Risks

- **`F-016` (P2)** (Firebase `checkRevoked=true` and active STOMP session revocation on ban/deletion) was intentionally excluded from Batch 1A.
- **`AdminRideRedisReassignTests`** auto-skips if `TEST_REDIS_PORT` is unset (safety guard to never touch `datxe_redis:6379`); CI should supply a disposable Redis 7 port.
- **PostgreSQL-gated tests (58)** should be re-executed when `TEST_DB_PASSWORD` / `VERIFICATION_SYNC_TEST_PASSWORD` for `ptitgo_audit_test` are exported in the shell.

---

## 5. Xác Minh Sau Sửa (`VERIFY` Mode — Tiếng Việt)

- **Kiểm tra biến môi trường PostgreSQL (`ptitgo_audit_test`)**: Thiếu cả 8 biến môi trường bắt buộc trong phiên shell hiện tại (`UNSET`):
  - `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`
  - `VERIFICATION_E2E_PG_URL`, `VERIFICATION_E2E_PG_USER`, `VERIFICATION_E2E_PG_PASSWORD`
  - `VERIFICATION_SYNC_TEST_DB`, `VERIFICATION_SYNC_TEST_PASSWORD`
  - Không đọc `.env`, không in mật khẩu, không thay đổi DB roles, không reset DB phát triển; đã dừng toàn bộ nhóm test cần PostgreSQL credentials.
- **Xác minh 8 regression tests mới (`F-014` & `F-001`)**:
  - Lệnh: `$env:TEST_REDIS_HOST="127.0.0.1"; $env:TEST_REDIS_PORT="6399"; .\gradlew.bat test --tests "com.nhom15.datxebackend.notification.ws.StompAuthInterceptorSecurityTests" --tests "com.nhom15.datxebackend.admin.AdminRideRedisReassignTests" --rerun-tasks` (chạy trên disposable `redis:7-alpine`, đã dừng & xóa container sau khi chạy).
  - Kết quả: **`BUILD SUCCESSFUL`** (0 lỗi biên dịch) — **`8 PASSED / 0 FAILED / 0 SKIPPED / 0 BLOCKED`** (`StompAuthInterceptorSecurityTests`: 4/4, `AdminRideRedisReassignTests`: 4/4).
- **Tổng trạng thái xác minh Batch 1A**:
  - **PASSED**: 8/8 regression tests mới (`F-014`, `F-001`); 203/203 non-PG tests từ lần chạy full suite trước đó (không có regression).
  - **FAILED**: 0.
  - **SKIPPED**: 3 live external API tests (`EkycModalSmokeTest`, `PlaceSearchTests`, `VehicleRoutingTests` — `RUN_GOONG_LIVE_TEST` & `DATXE_EKYC_SMOKE` chủ động bỏ trống).
  - **BLOCKED**: 58 PostgreSQL-gated tests (thiếu 8 biến credentials cho `ptitgo_audit_test`) + 1 Python Lua unittest (`src/test/python/test_dispatch_presence.py` thiếu `liblua5.4` trên Windows).
- **Giữ nguyên thay đổi production**: 3 file (`StompAuthInterceptor.kt`, `AdminRideService.kt`, `RideDispatchRecoveryTask.kt`, `+19/-4`), không sửa thêm code production, không commit/push/deploy.
