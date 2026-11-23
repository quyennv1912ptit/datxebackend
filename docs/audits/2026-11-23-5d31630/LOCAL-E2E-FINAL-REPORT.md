# Báo cáo hoàn tất kiểm thử End-to-End cục bộ PtitGo (Final)

Ngày 23/11/2026 · Môi trường Windows cục bộ · Đối chiếu [LOCAL-E2E-REPORT.md](LOCAL-E2E-REPORT.md) và [RELEASE-BLOCKERS.md](RELEASE-BLOCKERS.md)

---

## 1. Tổng quan & Biện pháp cô lập dữ liệu

- **Dịch vụ của người dùng đang chạy:** Backend `:8080` (CORS `http://localhost:5173` đã được mở và xác minh HTTP `200`), goAdmin `:5173` (HTTP `200`), PostgreSQL `datxe` (Flyway V46: 46/46, 0 lỗi), thiết bị Android thật qua ADB (`10AE3H0A4C000LH`, Android 16, `1080×2400`).
- **Cô lập dữ liệu ghi/xóa:** Để **không làm bẩn hoặc reset database `datxe`** (`1` customer, `4` drivers, `15` rides, `0` tickets), toàn bộ 36 kịch bản E2E có thao tác ghi/xóa được chạy trên instance Backend cô lập tạm (`127.0.0.1:8089`, đã tắt sạch sau khi chạy) nối vào database riêng `ptitgo_e2e_local` (Flyway V46) và Redis `db2`. Sau khi kết thúc, database `datxe` và Redis `db0` được xác nhận nguyên vẹn 100%.
- **Xác thực tài khoản:** Phiên môi trường chưa có các biến `PTITGO_TEST_*`. Đã sử dụng tài khoản tổng hợp mới tạo trên Firebase Auth (`app-dat-xe-fa31e`) cho 3 vai trò Admin/Customer/Driver (và 1 tài khoản dùng riêng cho kịch bản xóa), đăng nhập thật qua Firebase Identity Toolkit để lấy ID token do Firebase ký (không bypass `FirebaseAuthFilter`), và **đã xóa toàn bộ tài khoản tổng hợp khỏi Firebase Auth** sau mỗi lượt chạy. Không ghi mật khẩu/token/OTP vào log hay báo cáo.

---

## 2. Xác minh lại kết quả 32/33 trước đó & Kịch bản thứ 33

Trong lượt chạy ở [LOCAL-E2E-REPORT.md](LOCAL-E2E-REPORT.md), kịch bản duy nhất chưa PASS (`32/33`) là:
- **Kịch bản thứ 33:** `Support Ticket, Pagination & clientMessageId Deduplication`.
- **Nguyên nhân chính xác:** Kịch bản test truyền chuỗi thường (`e2e-tk-msg-<timestamp>`) cho trường `clientMessageId`, trong khi `SendTicketMessageRequest.clientMessageId` yêu cầu kiểu `UUID?`, dẫn đến Backend trả `400 Bad Request` đúng hợp đồng validation (không phải lỗi backend). Đồng thời API phân trang tin nhắn dùng tham số `size` và `beforeId` (trả `ApiResponse<List<TicketMessageDto>>`), không phải `limit`.
- **Kết quả xác minh lại trong đợt này:** Đã gửi `clientMessageId` chuẩn `UUID` 2 lần liên tiếp và kiểm tra phân trang bằng `size=2` + `beforeId`: **PASS 100%** (`tid=5`, gửi lặp trả cùng `id=15==15`, trang 1 trả `[16, 17]`, trang 2 với `beforeId=16` trả `[14, 15]`, không trùng lặp).

---

## 3. Tổng hợp số lượng kịch bản (40 hạng mục)

| Nhóm kiểm thử | Tổng | PASS | FAIL | BLOCKED | NOT VERIFIED |
|---|---:|---:|---:|---:|---:|
| API / Auth / RBAC / WebSocket STOMP / DB / Redis / eKYC (trực tiếp) | 36 | **36** | 0 | 0 | 0 |
| Giao diện thực tế (goAdmin trình duyệt & Android thiết bị thật) | 4 | **2** | 0 | **2** | 0 |
| **Tổng cộng** | **40** | **38** | **0** | **2** | **0** |

---

## 4. Bằng chứng chi tiết từng kịch bản

### A. 36 kịch bản End-to-End trực tiếp (36/36 PASS)

| # | Nhóm | Kịch bản | Trạng thái | Bằng chứng thực thi |
|---:|---|---|---|---|
| 01 | Hạ tầng | Backend `:8080` của người dùng Health & CORS `:5173` (chỉ đọc) | **PASS** | `GET /actuator/health` HTTP `200`; `OPTIONS /api/auth/admin/login` từ `http://localhost:5173` trả `200` + `ACAO=http://localhost:5173`; origin lạ bị chặn `403` |
| 02 | Auth | Chặn truy cập chưa đăng nhập | **PASS** | `/api/auth/me` và `/api/admin/rides` trả HTTP `401` |
| 03 | Auth | Đăng nhập Admin thật qua Firebase & `/api/auth/me` | **PASS** | `POST /api/auth/admin/login` HTTP `200`, `/api/auth/me` trả `role=ADMIN` |
| 04 | Auth | Đăng ký/đăng nhập Customer qua Firebase & `/api/auth/me` | **PASS** | `POST /api/auth/customer/register` HTTP `200` (`customerId=14`), `role=CUSTOMER` |
| 05 | Auth | Đăng ký/đăng nhập Driver qua Firebase & `/api/auth/me` | **PASS** | `POST /api/auth/driver/register` HTTP `200` (`driverId=12`), `role=DRIVER` |
| 06 | Auth | Cách ly phân quyền chéo vai trò (RBAC) | **PASS** | Customer gọi `/api/admin/dashboard`, `/api/driver/profile` và Driver gọi `/api/admin/dashboard` đều bị chặn `403/403/403` |
| 07 | WebSocket | STOMP Handshake có xác thực (`/ws-location`) | **PASS** | Customer & Driver đều nhận khung STOMP `CONNECTED` và `DISCONNECT` sạch |
| 08 | Ride E2E | Customer tạo xe tổng hợp (`CAR`, `AUTO`) | **PASS** | `POST /api/customer/vehicles` HTTP `200/201` (`vehicleId=10`) |
| 09 | Wallet | Tài xế nạp ví thử sandbox (`500,000 VND`) | **PASS** | `POST /api/driver/wallet/top-up` HTTP `200` |
| 10 | Ride E2E | Tài xế bật trạng thái `ONLINE` (`CAR`) | **PASS** | `PUT /api/driver/availability` HTTP `200`, `status=ONLINE` |
| 11 | Ride E2E | Tài xế cập nhật GPS ban đầu | **PASS** | `POST /api/driver/location` HTTP `200`, `data=true` |
| 12 | Ride E2E | Customer xem giá ước tính (`CASH`) | **PASS** | `POST /api/customer/rides/estimate` → `estimatedFare=48860`, `distanceKm=2.886` |
| 13 | Ride E2E | Customer đặt chuyến & kiểm tra idempotency replay | **PASS** | Gửi 2 lần cùng `Idempotency-Key` trả cùng `rideId=14` |
| 14 | Ride E2E | Driver nhận offer điều phối (Redis/Outbox) | **PASS** | `GET /api/driver/offers/pending` nhận đúng `offerRideId=14` |
| 15 | Ride E2E | Driver chấp nhận chuyến | **PASS** | `POST /api/driver/offers/14/accept` HTTP `200` |
| 16 | Ride E2E | Customer nhìn thấy tài xế được gán | **PASS** | `GET /api/customer/rides/active` → `status=DRIVER_ASSIGNED` |
| 17 | Ride E2E | Driver di chuyển đón & báo đến điểm đón | **PASS** | `start-arriving` HTTP `200`, `arrived` HTTP `200` |
| 18 | Chat | Chat trong chuyến & lịch sử hội thoại Admin | **PASS** | Gửi tin HTTP `201`, đọc lịch sử HTTP `200`, `admin/rides/14/chat/conversations` HTTP `200` |
| 19 | Ride E2E | Bàn giao nhận xe & Customer xác nhận | **PASS** | `handover/pickup` HTTP `201` (kiểm tra thư mục Cloudinary `datxe/handover/14`), `pickup/confirm` HTTP `200` |
| 20 | Ride E2E | Bắt đầu chuyến & cập nhật GPS đồng bộ Redis | **PASS** | `start` HTTP `200`, `location` HTTP `200`, Redis `db2` ghi nhận `15` keys |
| 21 | WebSocket | **Nhận sự kiện GPS realtime qua STOMP WebSocket** | **PASS** | Customer subscribe `/user/queue/rides/14/location` nhận trực tiếp khung `MESSAGE` chứa tọa độ `21.0305` khi tài xế gửi GPS |
| 22 | Ride E2E | Đến điểm trả, bàn giao trả xe & hoàn thành chuyến | **PASS** | `arrive-destination` `200`, `handover/dropoff` `201`, `dropoff/confirm` `200`, `complete` `200` |
| 23 | Ride E2E | Xác nhận thanh toán `CASH` & đối soát PostgreSQL | **PASS** | `cash/confirm` `200`, PG `rides.status=COMPLETED`, `payments=1` |
| 24 | Ride E2E | Đánh giá 2 chiều (Customer ↔ Driver) | **PASS** | Cả hai chiều trả HTTP `200` |
| 25 | Admin | Admin xem chi tiết chuyến hoàn tất & dashboard | **PASS** | `GET /api/admin/rides/14` → `status=COMPLETED`, dashboard HTTP `200` |
| 26 | Wallet | Customer nạp ví sandbox (`300,000 VND`), xem số dư & sao kê | **PASS** | `top-up`, `overview`, `transactions` đều HTTP `200` |
| 27 | Voucher | Cấu hình ngân sách, tạo voucher & Customer xem danh sách | **PASS** | `PUT /budget` `200`, `POST /vouchers` `200`, `GET /customer/vouchers` `200` |
| 28 | Scheduled / Voucher | **Chuyến đặt lịch + Claim voucher + Giữ & Hoàn ngân sách khi hủy (`F-010`)** | **PASS** | Claim campaign (`voucherId=19`, HTTP `200`) → đặt chuyến hẹn (`id=16`, HTTP `201`) → hủy (`200`); `promo_budget.held_amount`: **`0 → 10000 → 0`** |
| 29 | Wallet | **Thanh toán chuyến bằng ví sandbox (`WALLET`)** | **PASS** | Hoàn tất chuyến thứ 2 (`rideId=15`, HTTP `200`), PG `payments.method=WALLET`, số dư ví Customer giảm chính xác **`300000 → 251000`** |
| 30 | Wallet | Rút tiền với ngân hàng giả lập & Admin duyệt (`F-007`) | **PASS** | `wdId=3`, PG `bank_name_snapshot=Vietcombank`, `review=APPROVE` HTTP `200` |
| 31 | Support | **Support Ticket + UUID `clientMessageId` Dedup + Phân trang `beforeId`/`size` (`F-040`/`F-041`)** | **PASS** | `tid=5`, gửi lặp cùng UUID trả cùng **`id=15==15`**; trang 1 (`size=2`) trả `[16, 17]`, trang 2 (`beforeId=16`) trả `[14, 15]`, hoàn toàn tách biệt (`disjoint=True`) |
| 32 | eKYC | Quy trình hồ sơ eKYC & kiểm tra kết nối Modal staging | **PASS** | `POST /applications` `200`, `GET /me` `200`; HTTPS Modal staging phản hồi `HTTP 401` (đang sống và yêu cầu xác thực Modal proxy, không gửi giấy tờ thật) |
| 33 | Ratings | Ẩn bình luận đánh giá (`F-046`) | **PASS** | `PUT /api/admin/ratings/12/visibility` (`hidden=true`) HTTP `200` |
| 34 | Notifications | Danh sách thông báo, đếm chưa đọc & đánh dấu đã đọc | **PASS** | `unread_count=20`, `read-all` HTTP `200` |
| 35 | Admin | Các API quản trị Drivers / Customers / Rides / Pricing | **PASS** | Đều trả HTTP `200/200/200/200` |
| 36 | Account | Khóa/mở khóa & xóa tài khoản thử | **PASS** | `INACTIVE` → `ACTIVE` → `DELETE /api/auth/account` đều HTTP `200` |

### B. 4 hạng mục giao diện thực tế (2 PASS · 2 BLOCKED)

| # | Nền tảng | Nội dung kiểm tra | Trạng thái | Bằng chứng / Nguyên nhân |
|---:|---|---|---|---|
| 37 | goAdmin (`:5173`) | Trang đăng nhập, Light/Dark mode, responsive `1280×720` & `375×812` | **PASS** | Kiểm tra trực tiếp trên trình duyệt: HTTP `200`, `0` lỗi console, không tràn ngang; quan sát nhỏ ở placeholder `Nhập mật khẩu` hơi sát đáy |
| 38 | Android thật (`10AE3H0A4C000LH`) | Mở `com.datxe.customer` & `com.datxe.driver` qua Metro + `adb reverse`, kiểm tra Light/Dark & `font_scale 1.3` | **PASS** | Cả hai app hiển thị chuẩn, không cắt chữ, `0` lỗi crash trong `logcat`, đã khôi phục cài đặt thiết bị |
| 39 | goAdmin (`:5173`) | Luồng UI sau đăng nhập (quản lý chuyến, tài xế, ticket trên trình duyệt) | **BLOCKED** | Thiếu thông tin đăng nhập cố định `PTITGO_TEST_ADMIN_*` trong môi trường |
| 40 | Android thật (`10AE3H0A4C000LH`) | Thao tác đặt/nhận chuyến xuyên suốt trên UI điện thoại thật | **BLOCKED** | Thiếu tài khoản OTP cố định `PTITGO_TEST_CUSTOMER_*` / `PTITGO_TEST_DRIVER_*` và không ghi đè phiên người dùng thật trên thiết bị |

---

## 5. Lỗi đã sửa

- **Mã nguồn ứng dụng (Backend / RN / goAdmin / eKYC):** **0 lỗi tích hợp mới** cần sửa code.
- **Cấu hình môi trường cục bộ:** Đã xác định lỗi đăng nhập goAdmin cục bộ do thiếu `http://localhost:5173` trong `APP_CORS_ALLOWED_ORIGINS` của `F:\datxebackend\.env` (đã khắc phục và xác nhận CORS HTTP `200`).
- **Kịch bản kiểm thử:** Đã sửa payload `clientMessageId` (sang `UUID`), tham số phân trang ticket (`size` + `beforeId`) và quy trình claim `voucherId` để xác minh chuẩn xác 36/36 kịch bản API/WS/DB/Redis.

---

## 6. Điều kiện còn thiếu & Đánh giá chuyển Staging

**Kết luận chuyển Staging: CHƯA ĐỦ ĐIỀU KIỆN (`NOT READY FOR STAGING`).**

Mặc dù toàn bộ 36/36 kịch bản E2E cục bộ (bao gồm REST, STOMP realtime, PostgreSQL V46, Redis, thanh toán `CASH`/`WALLET`, giữ/hoàn voucher, rút tiền snapshot và phân trang/dedup ticket) đều **PASS**, các điều kiện vận hành bắt buộc trước staging tại [RELEASE-BLOCKERS.md](RELEASE-BLOCKERS.md) vẫn chưa hoàn tất:

1. **Tài khoản & môi trường staging riêng:** Chưa bàn giao URL HTTPS staging, Firebase project staging cô lập và bộ tài khoản thử cố định (`PTITGO_TEST_*`) để hoàn tất kiểm thử UI có đăng nhập (hạng mục `#39`, `#40`).
2. **Ingress & `F-026`:** Chưa xác minh topology edge → origin, giới hạn firewall cổng `8080`, `APP_TRUSTED_EDGE_PROXIES` và `APP_CORS_ALLOWED_ORIGINS` của staging.
3. **eKYC staging live & Storage bền:** Chưa diễn tập luồng tải ảnh tổng hợp qua Modal staging với `DATXE_VERIFICATION_STORAGE_DIR` mount bền và xoay khóa mã hóa/HMAC.
4. **Diễn tập dữ liệu & Rollback:** Chưa chạy tiền kiểm V43, đối soát khoản rút `PENDING` cũ, tắt `WALLET_MOCK_TOPUP_ENABLED=false` và diễn tập `pg_dump`/`pg_restore` + rollback trên bản sao staging.
5. **Quyết định sản phẩm còn mở:** `F-018`/`OBS-12` (lưu giữ eKYC khi xóa tài khoản), `F-030` (giới hạn đặt lịch trước), `OBS-01` (thiếu số dư ví), `OBS-11` (quyền tài xế bị khóa/tạm ngưng), `OBS-14` (hiệu lực quote khi đổi bảng giá).
