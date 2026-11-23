# Báo Cáo Sửa Lỗi Batch 2B — eKYC (`F-017`, `F-019`, `F-018`)

- **Run ID**: `2026-11-23-5d31630`
- **Nhánh / Baseline HEAD**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Chế độ**: `FIX` (TDD: RED → minimal FIX → GREEN; giữ nguyên fix Batch 1A, 1B, 2A và 3 file test nền; không commit/push/deploy)

---

## 1. Thay Đổi Tối Thiểu

### File Production & Migration Đã Sửa/Thêm (3 file)
1. [DriverVerificationWorkflowService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationWorkflowService.kt#L98-L120):
   - **`F-019`**: Bỏ fallback `SecureRandom` ephemeral khi thiếu `lookup-hmac-key`; fail-fast (`IllegalArgumentException`) ở chế độ `require-protected-storage=true`; áp dụng domain separation (`ptitgo:verification:lookup-hmac:v1:`) tách biệt khóa HMAC khỏi khóa AES-GCM; không log secret.
   - **`F-017`** ([L1355-L1395](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationWorkflowService.kt#L1355-L1395), [L1967-L2025](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationWorkflowService.kt#L1967-L2025)): Sửa SQL `hasDuplicateCccdAcrossAccounts` và `hasDuplicateGplxAcrossAccounts` sang `AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO')` (loại trừ `DRAFT`, `REJECTED`, `REVOKED`); bỏ fail-open `.getOrDefault(false)` → ném `VerificationWorkflowException(503, "VERIFICATION_UNAVAILABLE")` khi lỗi DB; xóa `cccd_id_hmac`/`qr_cccd_id_hmac` khi `REJECTED`/`REVOKED`; map `DataIntegrityViolationException` sang `409 DUPLICATE_IDENTITY`.
2. [DriverVerificationStorageService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationStorageService.kt#L35-L170):
   - **`F-019`**: Bổ sung `app.verification.previous-encryption-keys` hỗ trợ key rotation an toàn (mã hóa mới bằng khóa chính, giải mã thử khóa chính rồi các khóa cũ, fail-closed `503 VERIFICATION_UNAVAILABLE` nếu không giải mã được; không log khóa).
3. [V43__identity_lock_unique_indexes.sql](file:///f:/datxebackend/src/main/resources/db/migration/V43__identity_lock_unique_indexes.sql):
   - **`F-017`**: Tạo 2 partial `UNIQUE` index (`uq_driver_verification_apps_locked_cccd`, `uq_driver_verification_apps_verified_gplx`) chỉ áp dụng trên trạng thái active/approved (`APPROVED`, `SUBMITTED`, `UNDER_REVIEW`, `NEEDS_MORE_INFO`), bảo vệ dữ liệu `APPROVED` hiện có và chống race condition ở tầng DB.

### Regression Test Đã Thêm (1 suite, 5 tests)
- [EkycIdentityLockAndKeyLifecycleTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/driver/verification/EkycIdentityLockAndKeyLifecycleTests.kt): Kiểm chứng SQL lọc `DRAFT`/`REJECTED`/`REVOKED`, phát hiện trùng CCCD/GPLX giữa 2 tài khoản active, fail-closed khi lỗi DB (`503`), HMAC key deterministic/domain-separated/fail-fast ở protected mode, và key rotation fail-closed trên `DriverVerificationStorageService`.

### `F-018` (Retention/Cleanup)
- Kiểm tra `OBS-12`: Chính sách lưu trữ/xóa tài liệu eKYC sau khi xóa tài khoản **chưa được chốt** → giữ nguyên trạng thái **`PRODUCT_DECISION_REQUIRED`**, không tự xóa tài liệu hay ảnh eKYC thật.

---

## 2. Kết Quả Kiểm Thử

- **RED**: `EkycIdentityLockAndKeyLifecycleTests` — **5 run, 4 failed**.
- **GREEN Targeted** (`driver.verification.*`, `ekyc.*`): **`BUILD SUCCESSFUL`** (1m 25s) — **15 suites, 61 total: 50 PASSED, 0 FAILED, 0 ERRORS, 11 skipped**:
  - **50 PASSED** (gồm 5/5 `EkycIdentityLockAndKeyLifecycleTests`).
  - **10 PG-gated `BLOCKED`** (thiếu credentials `ptitgo_audit_test`; chưa chạy `V43` và concurrency uniqueness trên PostgreSQL thật).
  - **1 Live API `SKIPPED`** (`EkycModalSmokeTest` — `DATXE_EKYC_SMOKE` unset).

---

## 3. Trạng Thái Findings

| Finding | Sev | Trạng Thái | Ghi Chú |
|---|---|---|---|
| **`F-017`** | P1 | **`VERIFIED_FIXED`** | Unit tests (6/6) + Safety Gate `V43` trên PostgreSQL 17 dùng một lần (`V43IdentityLockSafetyGatePostgresTests`: 3/3 PASSED). Lưu ý vận hành: kiểm tra không có bản ghi active trùng trước khi chạy `V43` trên DB thật. |
| **`F-019`** | P2 | **`VERIFIED_FIXED`** | Đã vá thêm tương thích ngược HMAC (`previous-lookup-hmac-keys` + đối chiếu HMAC cũ trước domain-separation) và key rotation ciphertext; 0 khoảng trống chống trùng danh tính. |
| **`F-018`** | P2 | **`PRODUCT_DECISION_REQUIRED`** | Chờ chốt chính sách `OBS-12`; chưa xóa tài liệu eKYC. |

---

## 4. Kết Quả Batch 2B Safety Gate (`VERIFY` Mode)

1. **Migration `V43` trên PostgreSQL 17 dùng một lần** (`ptitgo_v43_gate_pg:5439`, đã dừng & xóa, không chạm DB phát triển):
   - [V43IdentityLockSafetyGatePostgresTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/driver/verification/V43IdentityLockSafetyGatePostgresTests.kt): **3/3 PASSED** — Fresh `V1..V43`, upgrade `V42→V43` với dữ liệu giả (`APPROVED` + `DRAFT`/`REJECTED`/`REVOKED`), partial `UNIQUE` index chặn trùng active & race 2 luồng (1 thành công, 1 `23505`), và fail-fast nếu `V42` đã có sẵn duplicate active.
2. **Tương thích ngược `F-019`**:
   - Phát hiện và vá theo RED→GREEN lỗ hổng đối chiếu HMAC cũ trước domain-separation & sau khi xoay khóa (`app.verification.previous-lookup-hmac-keys`). `EkycIdentityLockAndKeyLifecycleTests`: **6/6 PASSED**.
3. **`F-024` & `ptitgo_audit_test`**: **`BLOCKED`** do thiếu 8 biến credentials PostgreSQL trong shell (không reset role/mật khẩu; `F-024` giữ `FIXED_PENDING_PG_VERIFICATION`).
