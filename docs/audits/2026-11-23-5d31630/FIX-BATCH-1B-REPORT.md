# Báo Cáo Sửa Lỗi Batch 1B — `F-016` (P2)

- **Run ID**: `2026-11-23-5d31630`
- **Nhánh / Baseline HEAD**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Chế độ**: `FIX` (TDD: RED → minimal FIX → GREEN; giữ nguyên fix `F-014`/`F-001` và 3 file test nền; không commit/push/deploy)

---

## 1. Phạm Vi Thay Đổi

### File Production Đã Sửa (Batch 1B — 4 file)
1. [FirebaseAuthFilter.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/auth/security/FirebaseAuthFilter.kt#L31-L52): Chuyển `firebaseAuth.verifyIdToken(idToken, true)` (`checkRevoked = true`) và đảm bảo `SecurityContextHolder.clearContext()` (fail-closed) khi token bị thu hồi, lỗi xác thực hoặc lỗi runtime.
2. [AuthTokenVerifier.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/notification/ws/AuthTokenVerifier.kt#L7-L29): Bổ sung `VerifiedStompIdentity`, `verifyIdentity(...)` và `isSessionActive(...)` có default implementation để giữ tương thích ngược với các verifier test.
3. [FirebaseAuthTokenVerifier.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/notification/ws/FirebaseAuthTokenVerifier.kt#L10-L56): Bật `checkRevoked = true` trên STOMP `CONNECT`; cài đặt `isSessionActive(...)` kiểm tra trạng thái/xóa/role tài khoản qua `AuthenticatedUserProvider` (`AuthenticatedUserCache` TTL 3s, evict ngay khi khóa/xóa) và kiểm tra thu hồi Firebase định kỳ khi `checkRemoteRevocation = true`, fail-closed khi có ngoại lệ.
4. [StompAuthInterceptor.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/notification/ws/StompAuthInterceptor.kt#L19-L115): Lưu `firebaseUid`/`role` trong `StompPrincipal`, quản lý trạng thái phiên theo `sessionId` (dọn sạch khi `DISCONNECT`), tái xác thực session trên `SUBSCRIBE`/`SEND` với chu kỳ gọi Firebase định kỳ `app.stomp.revalidation-interval-ms` (mặc định `30000` ms) — không gọi Firebase cho từng GPS frame; giữ nguyên hoàn toàn chặn spoofing của `F-014`.

### Regression Test Đã Thêm (1 suite, 5 tests)
- [AuthRevocationLifecycleTests.kt](file:///f:/datxebackend/src/test/kotlin/com/nhom15/datxebackend/auth/security/AuthRevocationLifecycleTests.kt): Giả lập `FirebaseAuth` (không gọi Firebase thật), kiểm chứng:
  1. HTTP `FirebaseAuthFilter` chặn token bị thu hồi (`checkRevoked = true`).
  2. STOMP `CONNECT` chặn token bị thu hồi (`checkRevoked = true`).
  3. Phiên STOMP đang mở chặn ngay tài khoản bị khóa/xóa sau khi evict `AuthenticatedUserCache`, đồng thời 5 GPS frames trong chu kỳ không tạo thêm cuộc gọi mạng Firebase.
  4. Phiên STOMP đang mở fail-closed khi role thay đổi hoặc `AuthenticatedUserProvider` lỗi.
  5. Phiên STOMP đang mở tái xác thực Firebase sau hết chu kỳ 30s và chặn token mới bị thu hồi.

---

## 2. Kết Quả Kiểm Thử

- **RED (`AuthRevocationLifecycleTests`)**: **4 run, 4 failed** (HTTP và STOMP đều chấp nhận token bị thu hồi vì thiếu `checkRevoked = true`).
- **GREEN Targeted** (`auth.*`, `notification.*`, `tracking.*`, `chat.*`): **`BUILD SUCCESSFUL`** (1m 20s) — **11 suites, 37 tests: 37 PASSED, 0 FAILED, 0 ERRORS, 0 SKIPPED** (bao gồm `AuthRevocationLifecycleTests`: 5/5 và `StompAuthInterceptorSecurityTests` `F-014`: 4/4).
- **PostgreSQL-gated tests**: **`BLOCKED`** (thiếu 8 biến môi trường credentials cho `ptitgo_audit_test`; không chạy lại full suite vô ích, không đổi role, không đọc `.env`).

---

## 3. Trạng Thái Finding & Rủi Ro Còn Lại

- **`F-016` (P2)**: **`VERIFIED_FIXED`**.
- **Rủi ro còn lại**: Trên cụm nhiều instance, `AuthenticatedUserCache` là cache cục bộ theo JVM (TTL tối đa 3s) và tái kiểm tra thu hồi Firebase trên phiên STOMP đang mở có cửa sổ mặc định 30s (`app.stomp.revalidation-interval-ms`).
