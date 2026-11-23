# Báo Cáo Sửa Lỗi Batch 2A — `F-023`, `F-024`, `F-025` (+ `F-016` Outbound STOMP)

- **Run ID**: `2026-11-23-5d31630`
- **Nhánh / Baseline HEAD**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Chế độ**: `FIX` (TDD: RED → minimal FIX → GREEN; giữ nguyên fix `F-001`, `F-014`, `F-016` và 3 file test nền; không xóa dữ liệu eKYC trái `OBS-12`; không commit/push/deploy)

---

## 1. Thay Đổi Tối Thiểu

### File Production Đã Sửa (6 file)
1. [AccountDeletionService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/service/AccountDeletionService.kt#L48-L152):
   - `F-024`: Chuyển `deleteCustomer`/`deleteDriver` sang khóa hàng `findByIdForUpdate`; chặn `deleteDriver` bằng `ConflictException` khi còn `withdrawal_requests.status = 'PENDING'` (giữ nguyên `bankAccountNumber`); chuyển sang `authenticatedUserCache.evictAfterCommit(firebaseUid)`.
   - `F-023`: Kiểm tra vai trò song song còn hoạt động (`deletedAt == null`) trên cùng `firebaseUid`; nếu còn vai trò active thì enqueue outbox với `firebaseUid = ""` để không xóa Firebase user chung.
2. [AccountCleanupOutboxWorker.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/service/AccountCleanupOutboxWorker.kt#L13-L55):
   - `F-023` (defense-in-depth): Kiểm tra lại cả `CustomerRepository` và `DriverRepository` trước khi gọi `firebaseAuth.deleteUser(firebaseUid)`; bỏ qua nếu `firebaseUid` rỗng hoặc vẫn còn vai trò active.
3. [RideService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/ride/service/RideService.kt#L63-L68):
   - `F-024`: Kiểm tra `customer.deletedAt != null || customer.status != UserStatus.ACTIVE` ngay sau `customerRepository.findByIdForUpdate(actor.id)` (ném `ACCOUNT_LOCKED`).
4. [RideStateMachine.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/ride/service/state/RideStateMachine.kt#L235-L236):
   - `F-024`: Kiểm tra `driver.deletedAt != null || driver.status != DriverStatus.ONLINE` ngay sau `drivers.findByIdForUpdate(driverId)`.
5. [AuthService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/service/AuthService.kt#L31-L40) & [AuthService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/service/AuthService.kt#L242-L283):
   - `F-025`: `attachDriverByPhone` đồng bộ `owner_uid` từ `oldUid` sang `newUid` trên `driver_verification_applications` và `driver_verification_projection`; cả `attachCustomerByPhone` và `attachDriverByPhone` dùng `authenticatedUserCache.evictAfterCommit(...)` cho cả `oldUid` và `newUid`.
6. [StompChannel.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/notification/channel/StompChannel.kt#L11-L34):
   - `F-016` (outbound gap): Trước khi đẩy notification qua subscription cũ, kiểm tra `tokenVerifier.isSessionActive(principal, token = null, checkRemoteRevocation = false)` (fail-closed, dùng cache cục bộ 0 lần gọi Firebase) để tài khoản bị khóa/xóa không còn nhận push dù không gửi thêm frame STOMP nào.

### Regression Tests Đã Thêm (1 suite, 6 tests)
- [AccountLifecycleBatch2ATests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/auth/service/AccountLifecycleBatch2ATests.kt): Kiểm chứng dual-role deletion + outbox worker (`F-023`), `createRide` & `tryAssignDriver` từ chối tài khoản đã xóa sau `FOR UPDATE` (`F-024`), chặn `deleteDriver` khi có `PENDING` withdrawal + retry an toàn (`F-024`), `attachDriverByPhone` đồng bộ 2 bảng eKYC + `evictAfterCommit` (`F-025`), và `StompChannel` chặn outbound push tới user bị khóa/xóa (`F-016`).

---

## 2. Kết Quả Kiểm Thử

- **RED**: `AccountLifecycleBatch2ATests` — **5 run, 5 failed**.
- **GREEN Targeted** (`auth.*`, `notification.*`, `tracking.*`, `chat.*`, `RideCreationValidatorTests`, `RideCancellationHttpTests`, `ScheduledRideSchedulerTests`): **`BUILD SUCCESSFUL`** (1m 21s) — **50 PASSED, 0 FAILED, 0 SKIPPED** (gồm 6/6 Batch 2A, 5/5 `F-016`, 4/4 `F-014`).
- **PostgreSQL 2-thread Concurrency & Integration Tests**: **`BLOCKED`** do thiếu 8 biến credentials cho `ptitgo_audit_test` (`TEST_DB_*`, `VERIFICATION_E2E_PG_*`, `VERIFICATION_SYNC_TEST_*`).

---

## 3. Trạng Thái Findings & Next Action

| Finding | Sev | Trạng Thái | Ghi Chú |
|---|---|---|---|
| **`F-023`** | P1 | **`VERIFIED_FIXED`** | Đã chứng minh bằng regression test trên `AccountDeletionService` + `AccountCleanupOutboxWorker`. |
| **`F-024`** | P1 | **`FIXED_PENDING_PG_VERIFICATION`** | Đã khóa `FOR UPDATE` + guard `deletedAt`/`status`/`PENDING` withdrawal (unit test GREEN); **chưa đánh dấu `VERIFIED_FIXED`** vì chưa chạy được test đồng thời 2 luồng trên PostgreSQL `ptitgo_audit_test` (BLOCKED do thiếu credentials). |
| **`F-025`** | P2 | **`VERIFIED_FIXED`** | Đã chứng minh đồng bộ SQL `owner_uid` 2 bảng eKYC + `evictAfterCommit` bằng regression test. |
| **`F-016`** | P2 | **`VERIFIED_FIXED`** | Đã vá thêm lỗ hổng outbound push qua subscription STOMP cũ tại `StompChannel` + regression test. |
