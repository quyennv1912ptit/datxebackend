# Báo Cáo Hoàn Tất Sprint Sửa Toàn Bộ P1 (`P1-FIX-SPRINT-REPORT`)

- **Run ID**: `2026-11-23-5d31630`
- **Nhánh / Baseline HEAD**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Kết quả tổng**: **9/9 P1 `VERIFIED_FIXED` (0 P1 mở)**, **4 P2 `VERIFIED_FIXED`** (`F-016`, `F-019`, `F-025`, `F-044`), **1 P3 `VERIFIED_FIXED`** (`F-027`), **1 P2 `PRODUCT_DECISION_REQUIRED`** (`F-018` / `OBS-12`). Không commit/push/deploy; không chạm DB/Redis phát triển.

---

## 1. Findings Đã Sửa & Xác Minh Trong Sprint Này

| ID | Sev | Trạng Thái | Bằng Chứng RED → GREEN |
|---|---|---|---|
| **`F-024`** | P1 | **`VERIFIED_FIXED`** | [AccountDeletionConcurrencyPostgresTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/auth/service/AccountDeletionConcurrencyPostgresTests.kt) (**2/2 PASSED** trên disposable PG 17): 2-tx `FOR UPDATE` + latch tuần tự hóa xóa khách vs đặt chuyến và xóa tài xế vs nhận chuyến; chặn xóa khi có `withdrawal_requests` `PENDING`; retry idempotent. |
| **`F-004`** | P1 | **`VERIFIED_FIXED`** | [DriverStateConsistencyTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/driver/DriverStateConsistencyTests.kt): RED fail → [AdminDriverService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/admin/service/AdminDriverService.kt#L75-L96) chặn `forceOffline` khi `BUSY` (giữ `onlineVehicleType`), không hạ `BLOCKED` về `OFFLINE`, evict `AuthenticatedUserCache` sau commit → GREEN. |
| **`F-020`** | P1 | **`VERIFIED_FIXED`** | `DriverStateConsistencyTests`: [DriverVerificationWorkflowService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationWorkflowService.kt#L1521-L1575) không ép `BUSY`/`BLOCKED` về `OFFLINE` hay xóa `onlineVehicleType` khi đồng bộ `REJECTED`/`REVOKED`/`NEEDS_MORE_INFO` → GREEN. |
| **`F-044`** | P2 | **`VERIFIED_FIXED`** | `DriverStateConsistencyTests`: RED fail → [DriverCapabilityController.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverCapabilityController.kt#L54-L106) chặn tắt capability khi `BUSY`; khi `ONLINE → OFFLINE` gọi `evictAfterCommit` và `locationService.removeDriver` (không còn ghost driver trong Redis) → GREEN. |
| **`F-027`** | P3 | **`VERIFIED_FIXED`** | `DriverStateConsistencyTests`: RED fail → [DriverVerificationEligibility.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationEligibility.kt#L36-L46) `statusAfterRide` kiểm tra `BLOCKED` và `mayGoOnline(driver, onlineType)` đầy đủ → GREEN. |
| **`F-010`** | P1 | **`VERIFIED_FIXED`** | [VoucherBudgetIntegrityPostgresTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/voucher/VoucherBudgetIntegrityPostgresTests.kt): [PromoBudgetService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/voucher/service/PromoBudgetService.kt#L332-L340) khôi phục `forecastAmount` thay vì `holdAmount`; 5 vòng hold→release trên PG 17 giữ nguyên `issuance_reserved = 25000`, `held_amount = 0` → GREEN. |
| **`F-002`** | P1 | **`VERIFIED_FIXED`** | `VoucherBudgetIntegrityPostgresTests`: [RideStateMachine.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/ride/service/state/RideStateMachine.kt#L290-L293) xóa `discountAmount` khi admin revive từ `NO_DRIVER_FOUND`; [PromoBudgetService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/voucher/service/PromoBudgetService.kt#L369-L382) chặn `SPEND` nếu đã có `RELEASE` → GREEN. |

---

## 2. Kết Quả Full Gradle Test Suite (Disposable PG 17 + Disposable Redis 7)

- **Môi trường cô lập**: `ptitgo_sprint_pg` (`postgres:17-alpine`, cổng `5439`) + `ptitgo_sprint_redis` (`redis:7-alpine`, cổng `6399`), đã dừng & xóa sạch sau khi chạy (`docker ps` chỉ còn `datxe_postgres`, `datxe_redis`).
- **Kết quả**: **76 Kotlin suites, 292 tests**:
  - **PASSED**: **289** (287 ở lượt full suite + 2 test version `V42→V43` đã cập nhật và pass lại ngay: `LegacyVerificationRemovalPostgresTest`, `IntegrationReleaseRegressionPostgresTests`).
  - **FAILED**: **0**.
  - **SKIPPED**: **3** (live external APIs: `EkycModalSmokeTest`, `PlaceSearchTests`, `VehicleRoutingTests` — cố ý không gọi provider thật).
  - **BLOCKED**: **1** Python Lua unittest (`src/test/python/test_dispatch_presence.py` thiếu `liblua5.4` trên Windows).

---

## 3. Files Modified Trong Sprint Này

- **Production (6 file)**: `AdminDriverService.kt`, `DriverVerificationWorkflowService.kt`, `DriverCapabilityController.kt`, `DriverVerificationEligibility.kt`, `PromoBudgetService.kt`, `RideStateMachine.kt`.
- **Tests thêm/cập nhật (5 file)**: `AccountDeletionConcurrencyPostgresTests.kt`, `DriverStateConsistencyTests.kt`, `VoucherBudgetIntegrityPostgresTests.kt`, `LegacyVerificationRemovalPostgresTest.kt`, `IntegrationReleaseRegressionPostgresTests.kt`.

---

## 4. Remaining Release Blockers

- **0 P1 blockers còn mở** (9/9 P1 đã `VERIFIED_FIXED`).
- **Quyết định sản phẩm mở**: `OBS-01`, `OBS-11`, `OBS-12` (`F-018`), `OBS-13`, `OBS-14`.
- **Còn mở**: 20 P2 (`F-003`, `F-005`..`F-007`, `F-009`, `F-011`..`F-013`, `F-015`, `F-021`, `F-029`..`F-031`, `F-033`, `F-035`, `F-037`, `F-038`, `F-046`..`F-048`) và 16 P3.
- **Vận hành khi triển khai `V43`**: kiểm tra không có bản ghi `APPROVED`/`SUBMITTED` trùng `locked_cccd_id` hoặc `verified_gplx_number` trước khi áp dụng `V43` lên production DB.
