# Báo cáo kiểm thử End-to-End cục bộ hệ sinh thái PtitGo

Ngày 23/11/2026 · Môi trường Windows cục bộ · Thiết bị Android thật qua ADB (`10AE3H0A4C000LH`, Android 16, `1080×2400`)

> **Tuyên bố phạm vi:** Toàn bộ kiểm thử dưới đây thực hiện trên môi trường cục bộ với database cô lập (`ptitgo_e2e_local`) và Redis namespace cô lập (`db2`). Database phát triển `datxe` (Flyway V42) và Redis `db0` hoàn toàn không bị sửa đổi hoặc xóa dữ liệu. Kết quả local **không** đồng nghĩa với việc hệ thống đã `READY FOR STAGING` hay `READY FOR PRODUCTION`.

---

## 1. Môi trường & Công cụ (Phase 1)

| Thành phần | Phiên bản / Trạng thái | Ghi chú |
|---|---|---|
| Java | OpenJDK `21.0.13.0.2` (Microsoft LTS) | Khớp yêu cầu Java 21 |
| Gradle | `9.8.2` | Dùng `gradlew.bat` |
| Node / npm / pnpm | Node `v26.13.0` · npm `11.20.0` · pnpm `12.21.0` | PowerShell execution policy chặn `.ps1`; chạy ổn định qua `npm.cmd`, `pnpm.cmd`, `npx.cmd` |
| Python | Hệ thống `3.15.2` · eKYC `.venv` `3.11.18` | Chạy `uvicorn` và `pytest` bằng `.venv` |
| Docker | `29.9.3` | `datxe_postgres` (`postgres:17-alpine`) và `datxe_redis` (`redis:7-alpine`) đã sẵn sàng |
| Expo | `58.1.21` | Metro bundler cho Customer (`8081`) và Driver (`8082`) |
| ADB / Thiết bị thật | `10AE3H0A4C000LH` (V2342, Android 16) | Cài sẵn bản debuggable `com.datxe.customer` (`1.0.0`) và `com.datxe.driver` (`1.0.0`); kết nối qua `adb reverse` (`8080`, `8001`, `8081`, `8082`) |
| Flyway V1–V46 | **PASS (46/46, 0 lỗi)** trên `ptitgo_e2e_local` | DB dev `datxe` giữ nguyên mốc V42 (1 customer, 4 drivers, 2 admins, 15 rides). Tiền kiểm chỉ đọc V43 trên `datxe`: **0 nhóm trùng CCCD, 0 nhóm trùng GPLX** |
| Firebase Project | `app-dat-xe-fa31e` | Service account tại `F:\datxebackend\secrets\firebase-service-account.json` (không lộ secret) |

---

## 2. Trạng thái khởi động dịch vụ (Phase 2)

| Dịch vụ | Trạng thái | Địa chỉ / Cổng | Bằng chứng health & kết nối |
|---|---|---|---|
| PostgreSQL 17 (`datxe_postgres`) | **Đã khởi động thành công** | `127.0.0.1:5432` | Healthy; tạo mới DB test `ptitgo_e2e_local` tách biệt hoàn toàn với `datxe` |
| Redis 7 (`datxe_redis`) | **Đã khởi động thành công** | `127.0.0.1:6379` | `PING` → `PONG`; dùng `db2` cô lập hoàn toàn với `db0` (9 keys giữ nguyên) |
| Backend API (`F:\datxebackend`, profile `demo`) | **Đã khởi động thành công** | `http://127.0.0.1:8080` | `/actuator/health`, `/liveness`, `/readiness` trả HTTP `200` (`status=UP`). Lưu ý: `EkycConfig` bắt buộc `DATXE_EKYC_ORIGIN` là HTTPS `*.modal.run` (bảo vệ chống SSRF) nên không trỏ trực tiếp sang `http://127.0.0.1:8001` |
| eKYC Service (`F:\ekyc-service`) | **Đã khởi động thành công** | `http://127.0.0.1:8001` | `GET /health` trả HTTP `200` (`{"status":"ok"}`); dùng SQLite & storage tạm cô lập tại `.local-e2e-tmp` |
| Admin Web (`F:\goAdmin`) | **Đã khởi động thành công** | `http://localhost:5173` | HTTP `200`; CORS preflight tới `http://127.0.0.1:8080/api/auth/me` trả `Access-Control-Allow-Origin: http://localhost:5173` |
| Customer App (`F:\datxe_rn\apps\customer`) | **Đã khởi động thành công** | Metro `http://localhost:8081` + Android thật | Mở qua Expo Dev Client (`drivemate://...`) với `EXPO_PUBLIC_API_URL=http://localhost:8080` qua `adb reverse` |
| Driver App (`F:\datxe_rn\apps\driver`) | **Đã khởi động thành công** | Metro `http://localhost:8082` + Android thật | Mở qua Expo Dev Client (`drivemate-driver://...`) với `EXPO_PUBLIC_API_URL=http://localhost:8080` qua `adb reverse` |

---

## 3. Kết quả xác thực tài khoản (Phase 3)

- **Biến môi trường `PTITGO_TEST_*`:** Không có biến nào trong nhóm `PTITGO_TEST_ADMIN_*`, `PTITGO_TEST_CUSTOMER_*`, `PTITGO_TEST_DRIVER_*` được thiết lập trong phiên môi trường.
- **Tạo tài khoản thử tổng hợp (theo xác nhận của người dùng):** Đã tạo 4 tài khoản tổng hợp trên Firebase Auth (`app-dat-xe-fa31e`) cho 3 vai trò (`Admin`, `Customer`, `Driver`, và `Customer` dùng cho bài test khóa/xóa tài khoản), đăng nhập thật qua Firebase Identity Toolkit (`accounts:signInWithPassword`) để nhận ID token hợp lệ do Firebase ký (không bypass `FirebaseAuthFilter`, không sửa cấu hình bảo mật), và **đã xóa toàn bộ tài khoản tổng hợp khỏi Firebase Auth** ngay sau mỗi lượt chạy.

| Hạng mục xác thực | Trạng thái | Bằng chứng |
|---|---|---|
| Chặn truy cập chưa đăng nhập (HTTP + eKYC + STOMP) | **PASS** | HTTP `401` trên `/api/auth/me`, `/api/admin/pricing`, `/api/rides/active`, `/api/driver/profile`, `/api/support/tickets`, `/ekyc/face/evaluate`; STOMP `/ws-location` trả khung `ERROR`/đóng kết nối |
| Đăng nhập Admin tổng hợp & `/api/auth/me` | **PASS** | `POST /api/auth/admin/login` → HTTP `200`, `/api/auth/me` trả `role=ADMIN` |
| Đăng ký & đăng nhập Customer tổng hợp | **PASS** | `POST /api/auth/customer/register` → HTTP `200`, `/api/auth/me` trả `role=CUSTOMER` |
| Đăng ký & đăng nhập Driver tổng hợp | **PASS** | `POST /api/auth/driver/register` → HTTP `200`, `/api/auth/me` trả `role=DRIVER` |
| Cách ly phân quyền chéo vai trò (RBAC) | **PASS** | Customer gọi `/api/admin/dashboard`, `/api/driver/profile` và Driver gọi `/api/admin/dashboard` đều bị từ chối HTTP `403` |
| Kết nối STOMP WebSocket có xác thực (`/ws-location`) | **PASS** | Cả Customer (`X-Role: CUSTOMER`) và Driver (`X-Role: DRIVER`) nhận STOMP `CONNECTED` và `DISCONNECT` sạch |
| Đăng nhập qua OTP số điện thoại cố định (`PTITGO_TEST_*`) | **BLOCKED** | Thiếu các biến `PTITGO_TEST_CUSTOMER_PHONE/OTP`, `PTITGO_TEST_DRIVER_PHONE/OTP`, `PTITGO_TEST_ADMIN_EMAIL/PASSWORD` |

---

## 4. Kết quả luồng chuyến đi End-to-End (Phase 4)

Toàn bộ luồng 17 bước chạy trực tiếp trên Backend (`localhost:8080`), PostgreSQL 17 (`ptitgo_e2e_local`), Redis 7 (`db2`) và STOMP WebSocket bằng dữ liệu tổng hợp:

| Bước | Nội dung kiểm tra | Trạng thái | Bằng chứng |
|---|---|---|---|
| 1 | Khách tạo xe tổng hợp (`CAR`, `AUTO`) | **PASS** | `POST /api/customer/vehicles` → HTTP `200/201` (`vehicleId=7`) |
| 2 | Tài xế được duyệt năng lực `car-auto`, nạp ví thử & bật `ONLINE` | **PASS** | Trước khi có `driver_verification_projection` hợp lệ, backend chặn đúng `403 DRIVER_NOT_VERIFIED`; sau khi đủ điều kiện, `PUT /api/driver/availability` → `status=ONLINE`, `onlineVehicleType=CAR` |
| 3 | Tài xế gửi tọa độ GPS ban đầu trong vùng phục vụ Hà Nội | **PASS** | `POST /api/driver/location` → HTTP `200` (`data=true`) |
| 4 | Khách xem giá ước tính | **PASS** | `POST /api/customer/rides/estimate` → `estimatedFare=48860`, `distanceKm=2.886` |
| 5 | Khách đặt chuyến & kiểm tra idempotency replay | **PASS** | `POST /api/customer/rides` (2 lần cùng `Idempotency-Key`) trả cùng `rideId=8` |
| 6 | Tài xế nhận offer điều phối qua outbox/Redis | **PASS** | `GET /api/driver/offers/pending` nhận đúng `offerRideId=8`, `offerId=9cd2cd21-...` |
| 7 | Tài xế chấp nhận chuyến | **PASS** | `POST /api/driver/offers/8/accept` → HTTP `200` (`Nhận chuyến thành công`) |
| 8 | Khách nhìn thấy tài xế được gán | **PASS** | `GET /api/customer/rides/active` → `status=DRIVER_ASSIGNED`, `driverPresent=true` |
| 9 | Tài xế di chuyển đến & báo đã tới điểm đón | **PASS** | `start-arriving` HTTP `200`, `arrived` HTTP `200` |
| 10 | Bàn giao xe lúc nhận (tài xế lập biên bản + khách xác nhận) | **PASS** | `handover/pickup` HTTP `201` (xác thực đúng folder Cloudinary `datxe/handover/{rideId}`), `pickup/confirm` HTTP `200` |
| 11–12 | Bắt đầu chuyến & cập nhật GPS trong chuyến (đồng bộ Redis) | **PASS** | `start` HTTP `200`, `POST /api/driver/location` HTTP `200` (`data=true`), Redis `db2` ghi nhận `32` keys |
| 13 | Đến điểm trả, bàn giao trả xe & hoàn tất chuyến | **PASS** | `arrive-destination` HTTP `200`, `handover/dropoff` HTTP `201`, `dropoff/confirm` HTTP `200`, `complete` HTTP `200` (`VEHICLE_RETURNED → COMPLETED`) |
| 14 | Xác nhận thanh toán tiền mặt & đối soát PostgreSQL | **PASS** | `cash/confirm` HTTP `200`; PostgreSQL xác nhận `rides.status = COMPLETED`, `payments` có `1` bản ghi |
| 15–16 | Khách đánh giá tài xế & tài xế đánh giá khách | **PASS** | `CUSTOMER_TO_DRIVER` HTTP `200`, `DRIVER_TO_CUSTOMER` HTTP `200` |
| 17 | Admin xem chi tiết chuyến hoàn tất & dashboard | **PASS** | `GET /api/admin/rides/8` → `status=COMPLETED`, `GET /api/admin/dashboard` HTTP `200` |

---

## 5. Kết quả các nghiệp vụ độc lập (Phase 5)

| Nghiệp vụ | Trạng thái | Bằng chứng |
|---|---|---|
| Đặt xe theo lịch & hủy chuyến | **PASS** | Tạo chuyến `SCHEDULED` (`schedRideId=9`) và hủy qua `POST /api/customer/rides/9/cancel` → HTTP `200` |
| Voucher & ngân sách khuyến mãi | **PASS** | `PUT /api/admin/vouchers/budget` HTTP `200`, `POST /api/admin/vouchers` tạo mã `E2E424966` HTTP `200`, `GET /api/customer/vouchers` HTTP `200` |
| Ví Customer & Driver (nạp thử, số dư, sao kê) | **PASS** | `POST /api/driver/wallet/top-up`, `POST /api/customer/wallet/top-up`, `GET /api/wallet/overview`, `GET /api/wallet/transactions` đều HTTP `200` |
| Rút tiền với ngân hàng giả lập & Admin duyệt | **PASS** | `POST /api/driver/withdrawals` (`wdId=2`) và `POST /api/admin/withdrawals/2/review` → HTTP `200` |
| eKYC Workflow (Backend + Local eKYC service) | **PASS** (phạm vi local) | `POST /api/driver/verification/v1/applications` HTTP `200`; local `POST http://127.0.0.1:8001/ekyc/face/evaluate` với ảnh tổng hợp trả HTTP `200` (`quality.selfie=NO_FACE`). Luồng gọi ngược Backend → Modal live chưa chạy để tránh tác động môi trường ngoài |
| Chat trong chuyến & danh sách hội thoại Admin | **PASS** | `POST /api/chat/rides/8/messages` (HTTP `201`), `GET /api/chat/rides/8/messages` (HTTP `200`), `GET /api/admin/rides/8/chat/conversations` (HTTP `200`) |
| Ticket hỗ trợ (tạo, xem chi tiết, danh sách Admin) | **PASS (một phần)** | Tạo ticket (`category=TRIP`, `ticketId=1`), `GET /api/tickets/1` (HTTP `200`), `GET /api/admin/tickets` (HTTP `200`). Ghi chú: bước test gửi tin nhắn lặp (`clientMessageId`) bị lỗi `400` ở script do truyền chuỗi không phải UUID (`SendTicketMessageRequest.clientMessageId` là `UUID?`); lần thử chạy script riêng sau đó chạm đúng `AuthLoginRateLimiter` (`401`) sau 10 lần đăng ký liên tiếp từ `127.0.0.1` — cơ chế chống spam hoạt động đúng |
| Đánh giá & quyền riêng tư (ẩn bình luận) | **PASS** | `GET /api/admin/ratings` và `PUT /api/admin/ratings/6/visibility` (`hidden=true`) → HTTP `200` |
| Thông báo (danh sách, số chưa đọc, đọc tất cả) | **PASS** | `GET /api/notifications` HTTP `200`, `unread-count` HTTP `200` (`unread_count=12`), `read-all` HTTP `200` |
| Khóa/mở khóa & xóa tài khoản thử | **PASS** | `PUT /api/admin/customers/{id}/status` (`INACTIVE` → `ACTIVE`) và `DELETE /api/auth/account` đều HTTP `200` |
| Admin quản lý tài xế, khách hàng, chuyến & bảng giá | **PASS** | `GET /api/admin/drivers`, `/customers`, `/rides`, `/pricing-rules` đều HTTP `200` |

---

## 6. Kiểm thử giao diện thực tế (Phase 6)

### goAdmin Web (`http://localhost:5173`, kiểm chứng qua trình duyệt thật)
- **PASS:** Tải trang `/` tự chuyển hướng `/login` (`PtitGo · Quản trị`), HTTP `200`, `0` lỗi console, `0` tài nguyên lỗi.
- **PASS:** Chuyển đổi Light (`Sáng`) và Dark (`Tối`) giữ độ tương phản tốt; không tràn ngang tại `1280×720` và `375×812`.
- **Quan sát UI nhỏ (không chặn):** Ô nhập `Mật khẩu` có chiều cao inner `<input>` nhỏ khiến phần nét dưới của chữ placeholder `Nhập mật khẩu` hơi sát đáy.
- **BLOCKED:** Kiểm tra UI các trang quản trị sau đăng nhập trên trình duyệt bị chặn do thiếu thông tin đăng nhập cố định (`PTITGO_TEST_ADMIN_*`).

### Customer & Driver Android trên thiết bị thật (`10AE3H0A4C000LH`, Android 16)
- **PASS (`com.datxe.customer`):** Kết nối thành công tới Metro (`localhost:8081`) và Backend cục bộ (`localhost:8080`) qua `adb reverse`. Giao diện `Hoàn tất đăng ký` hiển thị rõ nét ở Dark Mode, đúng safe area, không cắt chữ, không crash (`0` lỗi `AndroidRuntime`/`ReactNativeJS` trong logcat).
- **PASS (`com.datxe.driver`):** Kết nối thành công tới Metro (`localhost:8082`) và Backend cục bộ (`localhost:8080`) qua `adb reverse`. Giao diện `Đăng ký tài xế` hiển thị chuẩn cả **Dark Mode (`1.0x`)** và **Light Mode (`font_scale 1.3`)**; badge số điện thoại tự xuống 2 dòng sạch sẽ, nút `Tạo tài khoản và tiếp tục` đúng nhận diện Deep Emerald, không cắt chữ, không crash. Đã khôi phục nguyên trạng `font_scale=1.0` và `uimode` của máy thật.
- **Quan sát DevClient (không phải lỗi ứng dụng):** Khi đổi `cmd uimode night` + `font_scale` đột ngột trên thiết bị đang mở Expo SDK 58 Dev Client, LogBox hiển thị cảnh báo `Looks like you have multiple navigation containers...` từ `expo-router/build/ExpoRoot.js`. Đã quét mã nguồn `apps/customer/src` và `apps/driver/src`: có **0** `NavigationContainer` tự tạo; đây là hành vi remount Activity của `expo-dev-client` trong chế độ debug.
- **BLOCKED:** Kiểm tra UI tương tác toàn bộ luồng đặt/nhận chuyến trực tiếp trên màn hình điện thoại thật bị chặn do không thực hiện đăng ký/ghi đè lên phiên tài khoản thật đang lưu trên thiết bị và thiếu `PTITGO_TEST_*` OTP cố định.

---

## 7. Lỗi tích hợp phát hiện & Sửa lỗi (Phase 7)

- **Lỗi tích hợp chặn luồng (blocking):** **0** lỗi mới cần sửa mã nguồn trong Backend, RN, goAdmin hoặc eKYC service. Mọi từ chối HTTP `400`/`401`/`403` gặp trong quá trình viết kịch bản E2E đều xuất phát từ quy tắc bảo mật/validation hoạt động đúng thiết kế (`EkycConfig` HTTPS `.modal.run`, `driver_verification_projection`, `CloudinaryAssetService` folder ownership `datxe/handover/{rideId}`, `UUID` cho `client_message_id`, và `AuthLoginRateLimiter`).
- **Test regression mới cần thêm:** Không phát sinh do không có lỗi mã nguồn cần vá trong đợt này.

---

## 8. Kiểm chứng cuối cùng & Git Diff (Phase 8)

| Repository | Nhánh / HEAD | Kiểm tra đã chạy | Trạng thái Git (`git diff --check`) |
|---|---|---|---|
| `F:\datxebackend` | `integration/backend-release` (`5d31630`) | Runtime E2E 32/33 PASS + Flyway V1–V46 (46/46) + baseline Gradle build 321 PASS / 0 FAIL | `exit 0` · 96 mục thay đổi có sẵn từ đợt trước (không sửa thêm mã nguồn; chỉ thêm báo cáo này) |
| `F:\datxe_rn` | `integration/mobile-release` (`fe1f5d8`) | Kiểm thử thiết bị Android thật (`com.datxe.customer`, `com.datxe.driver`) + kiểm tra không có `NavigationContainer` trùng; baseline `npm run check` 320/320 | `exit 0` · 18 mục có sẵn từ đợt trước (không sửa thêm mã nguồn) |
| `F:\goAdmin` | `integration/admin-release` (`a6915a6`) | Kiểm thử trình duyệt trực tiếp Light/Dark & responsive `1280×720` / `375×812`; baseline lint/typecheck/build/107 test | `exit 0` · 4 mục có sẵn từ đợt trước (không sửa thêm mã nguồn) |
| `F:\ekyc-service` | `main` (`a8466e1`) | Live FastAPI `/health` + `/ekyc/face/evaluate` (HTTP 401 khi thiếu key, HTTP 200 với ảnh tổng hợp) + `pytest tests/api/test_face.py` **3/3 PASS (2.03s)** | `exit 0` · **0** thay đổi (worktree sạch) |

---

## 9. Điều kiện còn thiếu để deploy Staging

Đối chiếu với [RELEASE-BLOCKERS.md](RELEASE-BLOCKERS.md) và [STAGING-READINESS-CHECKLIST.md](STAGING-READINESS-CHECKLIST.md), hệ thống **CHƯA ĐẠT `READY FOR STAGING`** cho tới khi hoàn tất:

1. **Môi trường & Tài khoản thử staging:** Cấp URL HTTPS staging cho Backend/goAdmin, Firebase project staging cô lập, và bộ tài khoản thử ổn định (`PTITGO_TEST_*`) để chạy smoke và kiểm thử UI có đăng nhập đầy đủ trên goAdmin + thiết bị thật.
2. **Ingress, TLS & F-026:** Xác nhận topology edge → origin, giới hạn bind/firewall cổng `8080`, cấu hình `APP_TRUSTED_EDGE_PROXIES` đúng CIDR peer thật và `APP_CORS_ALLOWED_ORIGINS` khớp origin staging.
3. **eKYC & Storage bền vững:** Xác nhận kết nối live Backend → eKYC staging (`*.modal.run`), mount bền `DATXE_VERIFICATION_STORAGE_DIR`, khóa mã hóa/HMAC và diễn tập đọc lại capture sau recreate container.
4. **Diễn tập dữ liệu & Rollback:** Chạy tiền kiểm V43 trên bản sao staging, đối soát khoản rút `PENDING` thiếu snapshot V44, xác nhận `WALLET_MOCK_TOPUP_ENABLED=false` và diễn tập `pg_dump`/`pg_restore` + rollback.
5. **Quyết định sản phẩm mở:** Chốt các mục `OBS-01` (thiếu tiền ví), `OBS-11` (tài xế bị hạn chế), `OBS-14` (hiệu lực quote) và `F-030` (giới hạn đặt lịch trước).
