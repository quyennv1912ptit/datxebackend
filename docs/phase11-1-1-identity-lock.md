# Phase 11.1.1 — Khóa danh tính & Xác minh lại giấy tờ (Identity Lock & Re-verification)

## 1. Mục tiêu kiến trúc

Mỗi tài khoản tài xế sau khi được phê duyệt (`APPROVED`) lần đầu sẽ được **gắn cố định với một danh tính người thật duy nhất** tại PostgreSQL trong hệ thống Spring Boot. Tài xế vẫn được cập nhật giấy tờ khi CCCD/GPLX hết hạn, cấp lại hoặc nâng hạng bằng, nhưng **tuyệt đối không thể biến tài khoản đã xác minh của người này thành tài khoản lái xe của người khác**.

PostgreSQL (`datxe_postgres`) và backend Spring Boot là **nguồn chân lý duy nhất (Single Source of Truth)** cho danh tính đã khóa, trạng thái xét duyệt, lịch sử phiên bản và quyền nhận chuyến (`DriverVerificationEligibility`).

---

## 2. Cấu trúc dữ liệu & Migration (`V33__identity_lock_and_reverification.sql`)

Bảng `driver_verification_applications` bổ sung các cột:
- `approved_revision bigint`: Revision của quyết định `APPROVED` gần nhất đang làm mốc hiệu lực.
- `approved_submission_revision bigint`: Revision bản nộp (`driver_verification_submissions`) của lần duyệt đó (tách biệt hoàn toàn khỏi bản nháp/cập nhật mới).
- `locked_cccd_id varchar(12)`: Số định danh CCCD 12 số đã khóa cho tài khoản.
- `locked_full_name varchar(150)`: Họ tên xác minh đã khóa.
- `locked_dob date`: Ngày sinh xác minh đã khóa.
- `verified_gplx_number varchar(30)`: Số GPLX đã duyệt gần nhất.
- `identity_hold boolean`: Cờ tạm giữ khi phát hiện mâu thuẫn danh tính nghiêm trọng hoặc trùng giấy tờ giữa các tài khoản.
- `identity_hold_reason text`: Lý do tạm giữ hiển thị cho cả tài xế và admin.

Migration đồng thời backfill hồ sơ `APPROVED` và snapshot ban đầu cho các tài xế đã được duyệt trước đó (`LEGACY_BACKFILL`) để giao diện tài xế hiển thị ngay trạng thái **Đã xác minh** với số CCCD ẩn dạng `********1234`.

---

## 3. Ma trận xử lý theo từng tình huống nghiệp vụ

| Tình huống | Cơ chế xử lý Backend (`DriverVerificationWorkflowService` & `DriverVerificationWarnings`) | Quyền nhận chuyến trong lúc chờ duyệt |
|---|---|---|
| **Chụp lại CCCD cùng số định danh** | Cho phép tạo bản cập nhật (`POST .../reverify`) và tải ảnh mới. | Giữ nguyên quyền `APPROVED` cũ còn hạn. |
| **CCCD có ngày hết hạn mới** | Cho phép cập nhật `cccd_expiry_date`; phát sinh `CCCD_RENEWAL_REQUIRES_REVIEW`; yêu cầu admin duyệt lại. | Giữ nguyên quyền cũ nếu CCCD đã duyệt trước đó chưa hết hạn. |
| **CCCD có số định danh khác** | **Chặn cập nhật thông thường** tại `PUT .../confirmation` với `409 IDENTITY_LOCKED` và cảnh báo `LOCKED_CCCD_ID_CHANGED` (`BLOCKING`). Chỉ admin mới có quyền ngoại lệ (`allowIdentityOverride=true` kèm lý do bắt buộc và audit `ADMIN_IDENTITY_OVERRIDE`). | Không đổi quyền cũ; yêu cầu giả mạo bị từ chối ở API. |
| **GPLX cấp lại cùng người** | Cho phép cập nhật số/ảnh GPLX mới nếu họ tên & ngày sinh phù hợp với danh tính đã khóa; gửi admin xét duyệt. | Giữ nguyên quyền cũ còn hạn; cập nhật `verified_gplx_number` sau khi admin duyệt. |
| **GPLX có số mới hoặc nâng hạng (VD: A1 -> B)** | Phát sinh `GPLX_UPDATED_REQUIRES_REVIEW`; yêu cầu admin xác minh lại. | Nhóm xe cũ còn hạn vẫn chạy bình thường; nhóm xe mới (`B`) **chưa kích hoạt** cho đến khi admin duyệt. Khi duyệt, `reconcilePreferences` giữ nguyên các nhóm xe cũ đang bật. |
| **GPLX mang tên hoặc ngày sinh người khác** | Phát sinh `NAME_MISMATCH` / `DOB_MISMATCH` / `GPLX_HOLDER_MISMATCH` (`CRITICAL`, `blocksActivation = true`). Khi nộp sẽ bật `identity_hold = true`. | **Tạm khóa quyền nhận chuyến**, đưa tài xế về `OFFLINE`, và chặn nút `Phê duyệt` ở cả backend (`409 VERIFICATION_ACTIVATION_BLOCKED`) và WebAdmin. |
| **Selfie mới của cùng người** | Cho phép tải ảnh mới; **không** dùng SHA-256 của ảnh để kết luận khác người (SHA-256 chỉ dùng để bỏ qua upload trùng byte của cùng một file). | Giữ nguyên quyền cũ khi chờ duyệt. |
| **Selfie có dấu hiệu người khác (`FACE_POSSIBLE_MISMATCH`)** | Khi engine đối chiếu khuôn mặt được bật và báo `POSSIBLE_MISMATCH`, gắn cảnh báo `CRITICAL` + `identity_hold = true` khi nộp để chuyển admin kiểm tra; không coi low similarity hay lỗi OCR đơn lẻ là bằng chứng gian lận tuyệt đối. | Tạm giữ để admin đối chiếu trực quan hai bản hồ sơ. |
| **Giấy tờ đã được tài khoản khác xác minh** | Tra cứu trùng `cccd_id` và `gplx_number` trên các tài khoản khác (`driver_verification_applications` và `driver_documents`); gắn `DUPLICATE_CCCD_ACROSS_ACCOUNTS` / `DUPLICATE_GPLX_ACROSS_ACCOUNTS` (`blocksActivation = true`). | **Chặn phê duyệt trùng** (`409 VERIFICATION_ACTIVATION_BLOCKED`), chuyển hàng đợi admin xử lý. |
| **GPLX hết hạn** | `LicenseEntitlement.validity(today)` trả `EXPIRED` -> `ApprovedCapability.valid(today) == false` và `DriverVerificationEligibility` khóa ngay các nhóm xe gắn với GPLX đó; CCCD hết hạn khóa toàn bộ quyền lái. | Khóa ngay tại thời điểm đánh giá quyền (`mayDrive`, `mayGoOnline`). |
| **Admin từ chối (`REJECTED`) bản cập nhật thông thường (ảnh mờ, thiếu nét)** | Nếu `approvedRevision != null` và `!identityHold`, giữ nguyên `driver_verification_projection` (`APPROVED`) và `drivers.verification_status = 'APPROVED'` nếu giấy tờ cũ còn hạn. | Không làm mất oan quyền lái hợp lệ hiện tại của tài xế. |

---

## 4. Thay đổi giao diện

1. **Ứng dụng Tài xế (`F:\datxe_rn`) — [VerificationScreen.tsx](file:///F:/datxe_rn/apps/driver/src/features/verification/VerificationScreen.tsx)**:
   - Hiển thị **Màn hình Trạng thái Đã xác minh (chỉ đọc)** với thông tin: **Đã xác minh**, **Căn cước công dân** (`Số định danh: ********1234`), **Giấy phép lái xe** (`Hạng ... · Không thời hạn / Còn thời hạn / Đã hết hạn`), **Ảnh chân dung** (`Đã được admin kiểm tra`), và ghi chú *"Thông tin định danh đã xác minh không thể tự ý thay đổi."*
   - Hành động: **Cập nhật giấy tờ** (`POST .../reverify`), **Xem lịch sử xác minh**, **Chọn loại xe nhận lái hộ**.
   - Trong luồng cập nhật sau xác minh (`identityLocked === true`), khóa các trường định danh cố định đã xác minh (`cccd_id`, `cccd_name`, `cccd_dob`) và cung cấp tùy chọn **Hủy cập nhật · Quay lại hồ sơ đã xác minh** (`POST .../cancel-reverify`).
2. **WebAdmin (`F:\goAdmin`) — [VerificationReview.tsx](file:///F:/goAdmin/src/pages/VerificationReview.tsx)**:
   - Hiển thị khối **Danh tính đã khóa (CCCD)**, cảnh báo tạm giữ (`identityHold`), bảng so sánh song song cột **Đã duyệt trước đó** và **Tài xế xác nhận**, phần xem lại ảnh bản đã duyệt trước đó, và khóa tùy chọn **Phê duyệt** khi còn cảnh báo `blocksActivation`.

---

## 5. Kiểm thử tự động

- **Backend (`F:\datxebackend`)**: `DriverVerificationIdentityLockTest.kt` (unit + MVC + end-to-end Flyway `V1..V33` trên PostgreSQL), `DriverVerificationEligibilityTest.kt`, `DriverVerificationWorkflowTest.kt`.
- **Driver App (`F:\datxe_rn`)**: `tests/driver-verification.test.cjs`, `tests/material-design.test.cjs`, và TypeScript `typecheck`.
- **WebAdmin (`F:\goAdmin`)**: `src/test/verification-review.test.tsx` (Vitest) và `pnpm typecheck`.
