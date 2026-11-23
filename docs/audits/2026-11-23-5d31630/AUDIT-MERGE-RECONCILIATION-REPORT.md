# Báo cáo tích hợp chọn lọc & đối soát nhánh PtitGo (`integration/backend-release` ↔ `origin/Merge`)

Ngày 23/11/2026 · Repository: `F:\datxebackend` · Không commit, push, deploy, merge sang `main` hay xóa nhánh `Merge`.

---

## 1. Trạng thái Git & Backup an toàn (Phase 1–2)

### Trạng thái Git xác minh ở Phase 1
- **Current branch (`integration/backend-release`):** `HEAD = 5d316306e7fe07514e295b6b5fcb5ff9725c257a` (đi trước `origin/integration/backend-release` 14 commit, trùng `origin/main`).
- **Nhánh local `Merge`:** `5d316306e7fe07514e295b6b5fcb5ff9725c257a` (tạo nhầm từ `HEAD`, không chứa commit của đồng đội — **giữ nguyên, không xóa**).
- **Nhánh `origin/Merge`:** `3f118d0c5feb8c89b850be7afd0d7949abddb3e9`.
- **`merge-base HEAD origin/Merge`:** `5d316306e7fe07514e295b6b5fcb5ff9725c257a`.
- **Worktree trước reconcile:** `75` file tracked đã sửa, `21` mục untracked (gồm 4 migration `V43`–`V46`).

### Backup trước khi sửa (Phase 2)
- Đã sao lưu toàn bộ **153 file** (tracked đã sửa + untracked hợp lệ thuộc `src/`, `docs/`, `scripts/`, `Dockerfile`, loại trừ hoàn toàn `.env`, `secrets/`, `data/`, `build/`, `.gradle/`) kèm `MANIFEST.txt` chứa kích thước và **SHA-256** từng file.
- Đã kiểm tra backup đọc được và khóa checksum gốc của `V43`–`V46`.

---

## 2. Tổng hợp các lỗi mới xác nhận & Bản sửa tích hợp chọn lọc (Phase 3)

Đã đối chiếu toàn bộ 7 nhóm ưu tiên giữa code hiện tại và `origin/Merge` (cùng 2 lỗi mới từ báo cáo audit #1). **Tất cả 7 lỗi đều có thật** và đã được tích hợp chọn lọc tối thiểu mà **không ghi đè bất kỳ bản sửa `VERIFIED_FIXED` nào** của nhánh hiện tại:

| Mã | Mức | Mô tả lỗi xác nhận | Bản sửa tối thiểu đã áp dụng | Giữ nguyên từ nhánh hiện tại |
|---|---|---|---|---|
| **A1** | Cao | `FirebaseAuthFilter` ưu tiên header `X-Role` từ client trước route role-scoped | Ưu tiên `expectedRole(request)` theo prefix (`/api/admin/`, `/api/customer/`, `/api/driver/`, `/ekyc/driver-verification/v1/`), giữ ngoại lệ song vai trò `/me`, chặn nếu `user.role != routeRole` | Giữ `F-016` (`verifyIdToken(..., checkRevoked = true)`) |
| **A6 / O6** | Cao | Driver đã xóa tài khoản không có tombstone UID vĩnh viễn → token cũ hoặc event eKYC sync trễ có thể tái tạo trạng thái | Thêm migration mới **`V47__deleted_firebase_uid_tombstones.sql`** (`deleted_firebase_uids`), ghi tombstone khi `AccountDeletionService#deleteDriver`, chặn đăng nhập/đăng ký driver trong `AuthService`, lọc bỏ event/query cho UID tombstone trong `VerificationProjectionRepository` (có guard `to_regclass` để tương thích test V42) | Giữ `F-018` (không tự xóa ảnh/hồ sơ eKYC), `F-023` (kiểm tra sibling role), `F-024` (lock hàng + chặn khi có rút tiền `PENDING`) |
| **D7** | Cao | `ChatService#markRead` không giới hạn `upToMessageId` theo `max(id)` của đúng hội thoại → có thể phát read receipt giả | Thêm `ChatMessageRepository#findMaxIdInConversation(cid)`, clamp `effectiveUpTo = minOf(upToMessageId, maxId)`, không phát sự kiện nếu `maxId == null` hoặc `<= 0` | Giữ `F-036` (`getAdminConversations`, selector `driverId`) |
| **O2** | Trung | `CustomerProfileService#update` và `updateAvatar` cho phép khách `BANNED`/soft-delete tiếp tục sửa hồ sơ | Đổi sang `findByIdForUpdate(actor.id)` và từ chối bằng `AppException(ErrorCode.ACCOUNT_LOCKED)` nếu `deletedAt != null || status != UserStatus.ACTIVE` | Giữ `CloudinaryAssetService` validate & xóa ảnh sau commit |
| **#6** | Trung | `DriverVerificationWorkflowService#replaceCapture` ghi file ảnh eKYC ra đĩa trước khi DB commit; nếu bước cập nhật hồ sơ/audit/commit rollback thì để lại file mồ côi | Đăng ký `TransactionSynchronization.afterCompletion` ngay sau `storage.store(bytes)` để gọi `storage.deleteQuietly(objectKey)` nếu transaction không `STATUS_COMMITTED` | Giữ nguyên mã hóa AES `DXE1`, `F-017` và `F-019` |
| **#7 & O5** | Cao | `Dockerfile` mở rộng biến `$JAVA_OPTS`; `scripts/deploy-production.sh` chưa kiểm tra `DATXE_VERIFICATION_LOOKUP_HMAC_KEY` và chưa chặn `JAVA_OPTS`/`SPRING_CONFIG_*`/mock-topup/non-prod profile | Ghim cứng cờ JVM trong `Dockerfile`; bổ sung preflight kiểm tra `DATXE_VERIFICATION_LOOKUP_HMAC_KEY`, profile `prod|production`, tắt `WALLET_MOCK_TOPUP_ENABLED`, và chặn `JAVA_OPTS`/`_JAVA_OPTIONS`/`JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS`/`SPRING_CONFIG_*` trong `scripts/deploy-production.sh` | Giữ nguyên kiểm tra mount `/var/lib/ptitgo/verification`, `DATXE_VERIFICATION_ENCRYPTION_KEY`, quyền `0700` và header `DXE1` |

*(Ghi chú về `D8`: Phân trang lọc visibility sau redispatch ở chat đòi hỏi đổi toàn bộ đường đọc `ChatService#messages` sang mô hình ride-wide của `origin/Merge`; để tránh ghi đè hợp đồng `F-036` và tránh refactor diện rộng ngoài phạm vi tối thiểu, đợt này tích hợp `D7` an toàn trước và ghi nhận `D8` là mục chờ review ghép sâu nếu cần.)*

---

## 3. An toàn Flyway & Migration V47 (Phase 4)

### Xác nhận bất biến V43–V46 (SHA-256 khớp 100% với backup)
- `V43__identity_lock_unique_indexes.sql` — `MATCH=True`
- `V44__withdrawal_bank_snapshot_and_utc_defaults.sql` — `MATCH=True`
- `V45__support_message_paging_and_idempotency.sql` — `MATCH=True`
- `V46__ride_fare_snapshot_and_pricing_audit.sql` — `MATCH=True`

Không sửa `flyway_schema_history`, không dùng `flyway repair`, không đụng vào database phát triển `datxe` (xác nhận giữ nguyên `1` customer, `4` drivers, `15` rides, Flyway rank `46`).

### Migration mới bổ sung
- **`V47__deleted_firebase_uid_tombstones.sql`**: Tạo bảng `deleted_firebase_uids (firebase_uid VARCHAR(128) PRIMARY KEY, deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)`.
- **Kiểm chứng trên PostgreSQL 17 cô lập (`ptitgo_reconcile_test`, `ReconciliationPostgresTests`):**
  - Fresh **`V1 → V47`**: `47` migrations thành công + `Flyway.validate()` **PASS**.
  - Upgrade **`V46 → V47`**: `46` + `1` migration (`target = 47`) + `Flyway.validate()` **PASS**.
  - Kiểm tra khóa chính `ON CONFLICT DO NOTHING` của `deleted_firebase_uids`: **PASS**.

### Cảnh báo không tương thích schema với môi trường đã chạy `origin/Merge`
- Nhánh `origin/Merge` dùng trùng số phiên bản `V43`–`V46` cho các file khác (`V43__ticket_message_idempotency.sql`, `V44__ticket_message_requested_status.sql`, `V45__deleted_firebase_uid_tombstones.sql`, `V46__ticket_message_idempotency_constraint_scope.sql`).
- **Nếu có môi trường/DB nào đã chạy Flyway từ `origin/Merge`:** KHÔNG chạy trực tiếp nhánh này lên DB đó vì sẽ xung đột checksum `V43`–`V46`. Cần tạo DB mới hoặc thực hiện kế hoạch đối soát schema thủ công riêng.

---

## 4. Bằng chứng RED → FIX → GREEN & Full Build (Phase 5)

| Bước kiểm chứng | Kết quả | Chi tiết |
|---|---|---|
| **RED** (`ReconciliationRegressionTests` trước khi sửa) | **4/4 FAILED** | Tái hiện chính xác `A1`, `O2`, `D7`, `O5 & #7` |
| **GREEN** (Targeted: `ReconciliationRegressionTests`, `ReconciliationPostgresTests`, `ChatServiceTests`, `AccountLifecycleBatch2ATests`, `AuthRevocationLifecycleTests`, `EkycIdentityLockAndKeyLifecycleTests`, `V43IdentityLockSafetyGatePostgresTests`, `AccountDeletionConcurrencyPostgresTests`) | **BUILD SUCCESSFUL (0 FAIL)** | Đạt toàn bộ, kể cả fresh `V1→V47` và upgrade `V46→V47` trên PG 17 |
| **Full Gradle Build** (`.\gradlew.bat --offline build` trên PG 17 `ptitgo_reconcile_test` + Redis 7 `db3`) | **320 PASS · 0 FAIL · 0 ERROR · 9 SKIPPED** (trên 84 suites, 329 tests) | Lần chạy full đầu phát hiện `LegacyVerificationRemovalPostgresTest` (chạy target V42 chưa có V47) → đã thêm guard `to_regclass('deleted_firebase_uids')` và PASS; `VerificationProjectionPostgresTest` (hardcode role `sync_test`) xác nhận chạy riêng **2/2 PASS (`BUILD SUCCESSFUL`)** |
| **`git diff --check`** | **PASS (`exit 0`)** | Không có lỗi whitespace / conflict markers |

---

## 5. Danh sách file đã sửa / thêm mới trong đợt reconcile

- **File mới (3):**
  - [V47__deleted_firebase_uid_tombstones.sql](file:///F:/datxebackend/src/main/resources/db/migration/V47__deleted_firebase_uid_tombstones.sql)
  - [ReconciliationRegressionTests.kt](file:///F:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/ReconciliationRegressionTests.kt)
  - [ReconciliationPostgresTests.kt](file:///F:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/ReconciliationPostgresTests.kt)
- **File đã sửa (9):**
  - [FirebaseAuthFilter.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/security/FirebaseAuthFilter.kt) (`A1`)
  - [AccountDeletionService.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/service/AccountDeletionService.kt) (`A6`)
  - [AuthService.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/service/AuthService.kt) (`A6`)
  - [VerificationProjectionRepository.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/VerificationProjectionRepository.kt) (`O6`)
  - [ChatMessageRepository.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/chat/repository/ChatMessageRepository.kt) (`D7`)
  - [ChatService.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/chat/service/ChatService.kt) (`D7`)
  - [ChatServiceTests.kt](file:///F:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/chat/ChatServiceTests.kt) (`D7` stub `findMaxIdInConversation`)
  - [CustomerProfileService.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/customer/service/CustomerProfileService.kt) (`O2`)
  - [DriverVerificationWorkflowService.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationWorkflowService.kt) (`#6`)
  - [Dockerfile](file:///F:/datxebackend/Dockerfile) (`O5`)
  - [deploy-production.sh](file:///F:/datxebackend/scripts/deploy-production.sh) (`O5` & `#7`)

---

## 6. Những việc còn `BLOCKED` / Cần quyết định tiếp

1. **`D8` (Chat visibility paging sau redispatch):** Cần thống nhất giữa đội backend về mô hình timeline ride-wide so với `F-036` hiện hành trước khi đổi toàn bộ query đọc chat.
2. **Các mục staging/production blockers vốn có:** `F-026` (ingress `APP_TRUSTED_EDGE_PROXIES`), `F-045` (inventory xóa asset dùng chung), `F-051` (timezone dữ liệu lịch sử), `F-018`/`OBS-12`, `F-030`, `OBS-01`, `OBS-11`, `OBS-14` (xem [RELEASE-BLOCKERS.md](RELEASE-BLOCKERS.md)).
3. **Commit / PR & dọn nhánh `Merge`:** Chưa thực hiện commit, push, merge hay xóa nhánh `Merge` theo đúng yêu cầu an toàn.

---

## 7. Final Reconciliation Gate (Cập nhật chốt cổng)

### 7.1 Phân tích & Khôi phục 9 test `SKIPPED`
- **6 test bị bỏ qua ngoài ý muốn (đã khôi phục & PASS 100%):**
  - `AdminRideRedisReassignTests` (4 test), `DispatchPersistenceTests` (1 test), `LocationFreshnessTests` (1 test) — trước đó skip do thiếu `TEST_REDIS_PORT` khác `6379`.
  - Đã bật container Redis 7 disposable trên cổng `127.0.0.1:16379` (không đụng Redis dev `6379`), chạy lại toàn bộ và **6/6 PASS**, sau đó xóa sạch container tạm.
- **3 test `SKIPPED` có chủ đích (Intentional external-provider smoke — không chạy trên local):**
  1. `EkycModalSmokeTest :: calls the private Modal deployment with a synthetic image()` (cần `DATXE_EKYC_SMOKE=true`)
  2. `PlaceSearchTests :: live Goong returns nearby places and reverse geocoding()` (cần `RUN_GOONG_LIVE_TEST=true`)
  3. `VehicleRoutingTests :: live Goong returns geometry and duration for both vehicle types()` (cần `RUN_GOONG_LIVE_TEST=true`)

### 7.2 Xác minh `D8` (Chat pagination visibility trước LIMIT/cursor)
- Kiểm chứng trực tiếp tại [ChatService.kt](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/chat/service/ChatService.kt#L103-L114) và [readPage](file:///F:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/chat/service/ChatService.kt#L222-L245): nhánh hiện tại luôn lọc `WHERE m.conversationId = :cid` trực tiếp trong SQL **trước** `Pageable` LIMIT/cursor, **không** thực hiện lọc visibility sau paging. Lỗi `D8` chỉ phát sinh trên nhánh `origin/Merge` do mô hình đọc đa thread ride-wide; nhánh hiện tại **không bị lỗi D8** và bảo toàn đầy đủ `F-036`.

### 7.3 Kiểm chứng eKYC orphan cleanup (Rollback + Process Crash + Idempotency)
- **Transaction rollback:** Đã đăng ký `TransactionSynchronization.afterCompletion` trong `DriverVerificationWorkflowService#replaceCapture` để tự xóa file mới ghi nếu transaction không `STATUS_COMMITTED`.
- **Process crash & Đối soát an toàn:** Đã bổ sung `DriverVerificationStorageService#listKeysOlderThan(cutoff)` và `DriverVerificationWorkflowService#reconcileOrphanCaptures(minAge = 1h)`.
- **Kiểm thử PostgreSQL 17 (`ReconciliationPostgresTests` — PASS):**
  - File mồ côi do crash (cũ hơn grace window, không có trong `driver_verification_captures`) → **bị xóa sạch**.
  - File đã được DB tham chiếu (kể cả cũ hơn grace window) → **tuyệt đối giữ nguyên** (bảo toàn `F-018`).
  - File mới tải đang in-flight (chưa qua grace window) → **tuyệt đối giữ nguyên**.
  - Gọi lại lần 2 (retry) → **0 file bị xóa thêm, idempotent 100%**.

### 7.4 Kiểm chứng V47 tombstone (`deleted_firebase_uids`)
- Kiểm thử PostgreSQL 17 (`ReconciliationPostgresTests` — PASS) xác nhận:
  - UID driver đã xóa ghi vào `deleted_firebase_uids` bị chặn hoàn toàn khi đăng nhập/đăng ký lại `Role.DRIVER` (`ErrorCode.ACCOUNT_LOCKED`) và bị bỏ qua trong `VerificationProjectionRepository`.
  - Vai trò `Role.CUSTOMER` còn hoạt động dùng chung cùng Firebase UID **không bị chặn sai** (`ensureUidAvailableFor(uid, Role.CUSTOMER)` đi qua bình thường).
  - Không thay đổi chính sách lưu giữ eKYC / đăng ký lại còn mở (`F-018` / `OBS-12`).

### 7.5 Kiểm tra Git Candidate & Toàn vẹn môi trường
- **Secrets scan trên candidate diff (`src/`, `scripts/`, `Dockerfile`):** `0` secret thật (chỉ có chuỗi cấu hình trong script và URL giả lập trong unit test).
- **Checksum Flyway `V43`–`V46`:** Khớp **100% SHA-256** so với bản backup trước reconcile.
- **Migration `V47`:** Chỉ tạo bảng `deleted_firebase_uids (firebase_uid PK, deleted_at TIMESTAMPTZ)`.
- **`git diff --check`:** `exit 0`.
- **Untracked files:** Giữ nguyên đầy đủ (`80` tracked modified, `24` untracked gồm `V43–V47`, các test mới và tài liệu audit; không làm mất file nào).
- **Database phát triển `datxe`:** Nguyên vẹn 100% (`1` customer, `4` drivers, `15` rides, Flyway rank `46`).

### 7.6 Kết quả Full Build cuối cùng
- Lệnh: `.\gradlew.bat --offline cleanTest build --console=plain` (với PostgreSQL 17 cô lập `ptitgo_reconcile_test` + Redis 7 disposable `16379`).
- Kết quả: **`BUILD SUCCESSFUL` (`exit 0`)** — **84 suites · 331 tests · 328 PASS · 0 FAIL · 0 ERROR · 3 INTENTIONAL SKIP**.

### 7.7 Kết luận cổng phát hành

| Cổng | Kết luận | Điều kiện / Ghi chú |
|---|---|---|
| **READY FOR COMMIT** | **READY** | Chỉ stage candidate hợp lệ (`src/`, `scripts/`, `Dockerfile`, `docs/audits/2026-11-23-5d31630/`); loại trừ `.env`, `secrets/`, `data/`, `.agents/`, `skills-lock.json`. |
| **READY FOR SOURCE MERGE** | **READY** | Toàn bộ 46 finding đã fix + 7 bản vá reconcile chọn lọc (`A1`, `A6/O6`, `D7`, `O2`, `O5`, `#6`, `#7`) + V47 đều đạt **328/328 test nội bộ PASS (0 FAIL)**. Giữ nguyên chuỗi Flyway `V43–V47` của nhánh này (không merge trực tiếp `V43–V46` trùng số của `origin/Merge`). |
| **READY FOR STAGING / PRODUCTION** | **NOT READY** | Giữ nguyên theo [RELEASE-BLOCKERS.md](RELEASE-BLOCKERS.md) (cần hạ tầng staging, `F-026`, eKYC storage bền, diễn tập backup/restore và chốt các quyết định sản phẩm). |
