# DatXe Backend

Tài liệu này mô tả các HTTP route, kênh STOMP/WebSocket, FCM và upload Cloudinary đang được triển khai trong project. Danh sách route được đối chiếu với controller hiện có.

## Tổng quan

- HTTP API chạy mặc định tại `http://localhost:8080`; prefix API chính là `/api`.
- Xác thực bằng Firebase ID token: `Authorization: Bearer <Firebase ID token>`.
- Các vai trò dùng trong API: `CUSTOMER`, `DRIVER`, `ADMIN`.
- Response HTTP được bọc trong `ApiResponse`; các danh sách phân trang dùng `PageResponse`.
- Với API tác động lên dữ liệu của một người dùng, danh tính lấy từ principal đã xác thực, không lấy ID người gọi từ request body.

## Chạy local lần đầu

1. Tạo cấu hình local từ mẫu: `cp .env.example .env`.
2. Thay password, Mapbox token và Cloudinary URL trong `.env`; đặt Firebase service account tại `secrets/firebase-service-account.json`.
3. Tạo Firebase user sẽ làm admin, rồi điền UID, email và tên vào `BOOTSTRAP_ADMIN_FIREBASE_UID`, `BOOTSTRAP_ADMIN_EMAIL`, `BOOTSTRAP_ADMIN_FULL_NAME`.
4. Điền giá thật bằng VND vào các biến `BOOTSTRAP_PRICING_*`, sau đó đặt `BOOTSTRAP_PRICING_ENABLED=true`. Không có giá mặc định vì giá mẫu có thể gây thu sai tiền.
5. Chạy `docker compose up --build`.

Flyway tạo schema. Khi ứng dụng khởi động, bootstrap chỉ tạo admin nếu UID chưa tồn tại và chỉ tạo bảng giá cho loại xe chưa có giá active; cấu hình không ghi đè giá đang hoạt động. Các biến admin có thể để trống khi chưa muốn cấp quyền admin. `.env.example` chỉ là mẫu phát triển, không dùng nguyên trạng cho production.

### Quy ước response và dữ liệu

Response thành công có dạng `{"success":true,"data":...}`. Response lỗi có dạng `{"success":false,"error":{"code":"...","message":"...","details":{...}}}`; `details` có thể vắng mặt. Mã lỗi và HTTP status được liệt kê ở cuối README.

Phân trang thường bắt đầu từ `page=0`. `PageResponse` có các trường `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`. Chat dùng `MessagePage` với `messages` và `hasMore`.

- Giá trị tiền trong API là số nguyên VND, kiểu `Long`; không gửi số thực cho số tiền.
- Các trường kiểu `Instant` dùng ISO-8601 UTC (ví dụ `2026-10-23T10:15:30Z`). Trường ngày như ngày sinh dùng `YYYY-MM-DD`.
- Tọa độ object dùng `lat` và `lng`; query Mapbox Directions nhận mỗi điểm theo thứ tự `lng,lat`.
- Enum được trả bằng tên chữ hoa, ví dụ `CAR`/`MOTORCYCLE`, `MANUAL`/`AUTO`.
- `POST /api/customer/rides` hỗ trợ `Idempotency-Key` tùy chọn (1–128 ký tự). Gửi lại cùng key và cùng body trả lại chuyến đã tạo; dùng lại key với body khác trả 409. Chat chống gửi lặp bằng `clientMessageId`.
- Rate limit dùng Redis: 60 lần/phút/IP cho login, 60 lần/phút/người dùng cho mỗi route Mapbox, 30 lần/phút/người dùng gộp cho estimate và tạo chuyến, 20 tin/phút/người dùng cho chat. Bộ đếm và TTL được cập nhật nguyên tử. Khi vượt giới hạn, API trả HTTP 429 với mã `RATE_LIMITED`. Các route khác chưa có rate limit dùng chung.
- Login giới hạn theo `remoteAddr` mà server nhìn thấy; nếu triển khai sau reverse proxy, cần cấu hình trusted client-IP forwarding để tránh dùng chung quota theo IP proxy.

## HTTP API

### Xác thực — `/api/auth`

| Method | Route | Quyền | Chức năng |
|---|---|---|---|
| POST | `/api/auth/customer/login` | Công khai, 60 lần/phút/IP | Xác minh Firebase ID token và đăng nhập hồ sơ khách hàng đã có. Body `AuthLoginRequest` gồm `idToken`, `phoneNumber` tùy chọn; hồ sơ mới trả `ACCOUNT_NOT_FOUND`. |
| POST | `/api/auth/customer/register` | Công khai, 60 lần/phút/IP | Tạo hồ sơ khách hàng từ Firebase ID token đã xác thực. Body `AuthRegisterRequest` gồm `idToken`, `fullName`, `phoneNumber` tùy chọn. |
| POST | `/api/auth/driver/login` | Công khai, 60 lần/phút/IP | Xác minh Firebase ID token và đăng nhập hồ sơ tài xế đã có; hồ sơ mới trả `ACCOUNT_NOT_FOUND`. |
| POST | `/api/auth/driver/register` | Công khai, 60 lần/phút/IP | Tạo hồ sơ tài xế chờ xác minh. Body `AuthRegisterRequest`; Firebase token phải có số điện thoại đã xác minh. |
| POST | `/api/auth/admin/login` | Công khai, 60 lần/phút/IP | Xác minh Firebase ID token cho admin đã tồn tại. |
| GET | `/api/auth/me` | Đã đăng nhập | Lấy hồ sơ của người dùng hiện tại. |
| POST | `/api/auth/logout` | Đã đăng nhập | Gỡ FCM token theo `installationId`. |
| DELETE | `/api/auth/account` | Đã đăng nhập | Ẩn danh hóa hồ sơ (customer đặt `phone_number=NULL` để số gốc có thể đăng ký lại), thay Firebase UID bằng tombstone, xóa tài khoản Firebase/token thiết bị và ảnh Cloudinary đã quản lý sau khi commit; bị từ chối nếu còn chuyến chưa kết thúc. Admin không thể tự xóa. Lịch sử chuyến và giao dịch được giữ lại. |

### Khách hàng — `/api/customer`

Các route trong nhóm này yêu cầu vai trò `CUSTOMER`.

| Method | Route | Chức năng |
|---|---|---|
| GET | `/api/customer/profile` | Xem hồ sơ khách hàng. |
| PUT | `/api/customer/profile` | Cập nhật thông tin hồ sơ, không đổi số điện thoại Firebase. |
| POST | `/api/customer/profile/avatar` | Cập nhật ảnh đại diện từ Cloudinary. |
| POST | `/api/customer/rides/estimate` | Ước tính giá/chuyến đi. |
| POST | `/api/customer/rides` | Tạo chuyến. |
| GET | `/api/customer/rides` | Lịch sử chuyến; query theo `RideListFilter` (hiện có `status`, `page`, `size`). |
| GET | `/api/customer/rides/active` | Chuyến đang hoạt động, gồm số tin chat chưa đọc. |
| GET | `/api/customer/rides/{id}` | Chi tiết chuyến. |
| POST | `/api/customer/rides/{id}/cancel` | Hủy chuyến. |
| POST | `/api/customer/rides/{id}/rating` | Đánh giá tài xế/chuyến đi. |
| POST | `/api/customer/rides/{id}/handover/pickup/confirm` | Xác nhận biên bản bàn giao tại điểm đón. |
| POST | `/api/customer/rides/{id}/handover/dropoff/confirm` | Xác nhận nhận lại xe tại điểm trả. |
| GET | `/api/customer/vehicles` | Danh sách xe. |
| POST | `/api/customer/vehicles` | Thêm xe. |
| PUT | `/api/customer/vehicles/{id}` | Cập nhật xe. |
| DELETE | `/api/customer/vehicles/{id}` | Xóa xe; xe đã gắn chuyến không thể xóa. |
| POST | `/api/customer/vehicles/{id}/images` | Thêm ảnh xe bằng `secureUrl` và `publicId`. |
| DELETE | `/api/customer/vehicles/{id}/images/{imageId}` | Xóa ảnh xe. |
| GET | `/api/customer/vouchers` | Danh sách voucher khả dụng. |

### Tài xế — `/api/driver`

Các route trong nhóm này yêu cầu vai trò `DRIVER`.

| Method | Route | Chức năng |
|---|---|---|
| GET | `/api/driver/profile` | Xem hồ sơ tài xế. |
| PUT | `/api/driver/profile` | Cập nhật hồ sơ tài xế. |
| POST | `/api/driver/documents` | Nộp giấy tờ xác minh. |
| GET | `/api/driver/documents` | Danh sách giấy tờ và trạng thái duyệt. |
| GET | `/api/driver/verification` | Tổng hợp trạng thái xác minh. |
| GET | `/api/driver/skills` | Danh sách loại xe/kỹ năng đã khai báo. |
| PUT | `/api/driver/skills` | Cập nhật danh sách kỹ năng. |
| PUT | `/api/driver/availability` | Cập nhật trạng thái sẵn sàng và loại xe đang phục vụ. |
| GET | `/api/driver/offers/pending` | Khôi phục các lời mời chuyến đang chờ. |
| POST | `/api/driver/offers/{rideId}/accept` | Nhận lời mời chuyến. |
| POST | `/api/driver/offers/{rideId}/reject` | Từ chối lời mời chuyến. |
| GET | `/api/driver/rides` | Lịch sử chuyến; query theo `RideListFilter`. |
| GET | `/api/driver/rides/active` | Chuyến đang hoạt động, gồm số tin chat chưa đọc. |
| GET | `/api/driver/rides/{id}` | Chi tiết chuyến. |
| POST | `/api/driver/rides/{id}/cancel` | Hủy chuyến. |
| POST | `/api/driver/rides/{id}/rating` | Đánh giá khách hàng. |
| POST | `/api/driver/rides/{id}/start-arriving` | Bắt đầu đi đến điểm đón. |
| POST | `/api/driver/rides/{id}/arrived` | Báo đã đến điểm đón. |
| POST | `/api/driver/rides/{id}/start` | Bắt đầu chuyến. |
| POST | `/api/driver/rides/{id}/arrive-destination` | Báo đã đến điểm trả. |
| POST | `/api/driver/rides/{id}/complete` | Hoàn thành chuyến. |
| POST | `/api/driver/rides/{id}/payment/cash/confirm` | Xác nhận đã thu tiền mặt sau khi chuyến hoàn tất; gửi lại xác nhận là idempotent. |
| POST | `/api/driver/rides/{id}/handover/pickup` | Tạo biên bản bàn giao tại điểm đón. |
| POST | `/api/driver/rides/{id}/handover/dropoff` | Tạo biên bản bàn giao tại điểm trả. |
| GET | `/api/driver/wallet` | Xem ví và số dư tài xế. |

### Quản trị — `/api/admin`

Các route trong nhóm này yêu cầu vai trò `ADMIN`.

#### Chuyến đi

| Method | Route | Query/body | Chức năng |
|---|---|---|---|
| GET | `/api/admin/rides` | `status`, `page`, `size` | Danh sách chuyến. |
| GET | `/api/admin/rides/{id}` | — | Chi tiết chuyến. |
| GET | `/api/admin/rides/{id}/chat` | `beforeId`, `afterId`, `size` | Đọc lịch sử chat để xử lý tranh chấp. |
| POST | `/api/admin/rides/{id}/assign` | Body `driver_id` (cũng nhận `driverId`) | Gán tài xế cho chuyến. |
| POST | `/api/admin/rides/{id}/cancel` | Body `CancelRideRequest` | Hủy chuyến. |

#### Tài xế và duyệt hồ sơ

| Method | Route | Query/body | Chức năng |
|---|---|---|---|
| GET | `/api/admin/drivers` | `verificationStatus` hoặc `verification_status`, `online`, `flagged`, `search`, `page`, `size` | Tìm và lọc tài xế. |
| GET | `/api/admin/drivers/{id}` | — | Chi tiết tài xế, giấy tờ, kỹ năng và thống kê. |
| GET | `/api/admin/driver-documents` | `status` (mặc định `PENDING`), `page`, `size` | Hàng chờ duyệt giấy tờ. |
| PUT | `/api/admin/driver-documents/{id}/review` | `ReviewDriverDocumentRequest` | Duyệt/từ chối giấy tờ. |
| PUT | `/api/admin/driver-skills/{id}/verify` | `VerifyDriverSkillRequest` | Duyệt kỹ năng loại xe. |
| PUT | `/api/admin/drivers/{id}/verification-status` | `SetDriverVerificationStatusRequest` | Quyết định trạng thái xác minh. |
| POST | `/api/admin/drivers/{id}/force-offline` | — | Ép tài xế offline. |

#### Khách hàng

| Method | Route | Query/body | Chức năng |
|---|---|---|---|
| GET | `/api/admin/customers` | `q`, `status`, `flagged`, `page`, `size`, `sort` | Tìm khách theo tên/SĐT/email và lọc trạng thái. `sort`: `createdAt`, `totalTrips`, `cancelRate30d`. |
| GET | `/api/admin/customers/{id}` | — | Chi tiết khách, xe và 5 chuyến gần nhất. |
| GET | `/api/admin/customers/{id}/rides` | `status`, `from`, `to`, `page`, `size` | Lịch sử chuyến của khách. |
| GET | `/api/admin/customers/{id}/wallet-transactions` | `page`, `size` | Lịch sử giao dịch ví. |
| PUT | `/api/admin/customers/{id}/status` | `UpdateAdminCustomerStatusRequest` | Khóa/mở tài khoản. |
| PUT | `/api/admin/customers/{id}/flag` | `UpdateAdminCustomerFlagRequest` | Đánh dấu hoặc bỏ đánh dấu khách. |

#### Bảng giá

| Method | Route | Chức năng |
|---|---|---|
| GET | `/api/admin/pricing-rules` | Danh sách quy tắc giá. |
| POST | `/api/admin/pricing-rules` | Tạo quy tắc giá. |
| PUT | `/api/admin/pricing-rules/{id}` | Sửa quy tắc giá. |
| POST | `/api/admin/pricing-rules/{id}/activate` | Bật quy tắc cho loại xe. |
| POST | `/api/admin/pricing-rules/{id}/deactivate?force=false` | Tắt quy tắc; `force=true` cho phép tắt quy tắc active cuối cùng. |

### Thông báo, thiết bị, upload và địa điểm

| Method | Route | Quyền | Query/body | Chức năng |
|---|---|---|---|---|
| GET | `/api/notifications` | Customer/Driver | — | Lịch sử thông báo cá nhân. |
| GET | `/api/notifications/unread-count` | Customer/Driver | — | Số thông báo chưa đọc. |
| PUT | `/api/notifications/{id}/read` | Customer/Driver | — | Đánh dấu một thông báo đã đọc. |
| PUT | `/api/notifications/read-all` | Customer/Driver | — | Đánh dấu tất cả thông báo đã đọc. |
| POST | `/api/device-tokens` | Customer/Driver | `installationId`, `fcmToken`, `platform` (`ANDROID`/`IOS`) | Đăng ký/cập nhật FCM token. |
| DELETE | `/api/device-tokens/{installationId}` | Customer/Driver | — | Gỡ FCM token của thiết bị. |
| GET | `/api/uploads/signature` | Customer/Driver | `purpose` tùy chọn; `rideId` bắt buộc cho chat | Xin chữ ký upload Cloudinary. |
| GET | `/api/location/geocode` | Customer/Driver | `address`, `proximityLng`, `proximityLat` tùy chọn | Đổi địa chỉ thành tọa độ; giới hạn 60 lần/phút/người dùng, cache 24 giờ theo địa chỉ chuẩn hóa và proximity. |
| GET | `/api/location/directions` | Customer/Driver | `origin`, `destination` | Lấy tuyến đường, khoảng cách và thời gian; giới hạn 60 lần/phút/người dùng, cache 5 phút theo tọa độ làm tròn 4 chữ số. |
| GET | `/api/location/nearby-drivers` | Admin | `longitude`, `latitude`, `radius` | Tìm tài xế gần tọa độ. |

### Tài chính và voucher: phạm vi route hiện có

- `GET /api/driver/wallet` là route ví duy nhất hiện có; response gồm `income_balance` và tối đa 100 giao dịch gần nhất.
- Hiện chỉ nhận thanh toán `CASH`. Chuyến hoàn tất tạo payment `PENDING`; tài xế xác nhận đã thu tiền qua route cash ở trên. `WALLET`, `VNPAY` và `MOMO` bị từ chối khi tạo chuyến cho đến khi tích hợp luồng thu/hoàn tiền. Khách chưa có route ví, rút tiền hay lịch sử thanh toán; chưa có callback gateway. Tip trong đánh giá tạm bị từ chối cho đến khi có ledger thu/chi.
- `GET /api/customer/vouchers` là route voucher duy nhất. Voucher được áp dụng nội bộ qua `voucher_code` trong estimate/tạo chuyến; chưa có CRUD voucher cho admin.

### Chat

Chat dùng REST để gửi/đọc/đánh dấu đã đọc, STOMP để nhận tin realtime và FCM làm dự phòng. Không có route tạo hội thoại riêng: hội thoại được upsert khi tài xế được gán cho chuyến.

| Method | Route | Query/body | Chức năng |
|---|---|---|---|
| GET | `/api/chat/rides/{rideId}/messages` | `beforeId`, `afterId`, `size` (mặc định 30, giới hạn 1–100) | Lịch sử chat. Không truyền cursor sẽ trả trang mới nhất; `beforeId` tải tin cũ hơn, `afterId` đồng bộ tin mới hơn. |
| POST | `/api/chat/rides/{rideId}/messages` | `clientMessageId`, `messageType`, `content`, `attachmentUrl`, `attachmentPublicId` | Gửi tin `TEXT` hoặc `IMAGE`; lần gửi mới trả 201, gửi lặp cùng ID trả lại tin cũ với 200. |
| PUT | `/api/chat/rides/{rideId}/read` | `upToMessageId` | Đánh dấu tin từ phía bên kia đã đọc đến ID chỉ định. |

Người gọi phải là khách hoặc tài xế của chuyến; người ngoài nhận 404. Gửi tin chỉ mở trong các trạng thái từ `DRIVER_ASSIGNED` đến `VEHICLE_RETURNED`; hội thoại chưa sẵn sàng hoặc đã đóng trả lỗi 409. Nội dung được giới hạn 1.000 ký tự. Tin ảnh phải dùng asset Cloudinary đã upload vào folder chat của chính chuyến.

Ảnh biên bản bàn giao hiện gửi trong `photos[]` với `angle`, `fileUrl` (cũng nhận `secureUrl`) và `publicId`. App xin chữ ký Cloudinary bằng `purpose=HANDOVER&rideId={id}`; chỉ tài xế của chuyến được ký và API bàn giao kiểm tra URL/public ID đúng folder chuyến đó.

### Health check và OpenAPI

| Method | Route | Quyền | Chức năng |
|---|---|---|---|
| GET | `/actuator/health`, `/actuator/health/**` | Công khai | Health check chỉ công bố trạng thái, không hiển thị chi tiết DB/Redis. |
| GET | `/actuator/info` | Công khai | Thông tin ứng dụng. |
| GET | `/swagger-ui.html`, `/swagger-ui/**` | Công khai | Swagger UI. |
| GET | `/v3/api-docs/**` | Công khai | OpenAPI document. |

Ở profile production (`SPRING_PROFILES_ACTIVE=prod`), Swagger UI và OpenAPI bị tắt. Health endpoint vẫn công khai nhưng chỉ trả trạng thái, không trả chi tiết component.

## STOMP / WebSocket

- Handshake endpoint: `ws://localhost:8080/ws-location`; production dùng `wss://<host>/ws-location`.
- Client gửi command đến prefix `/app`; broker nhận destination `/queue` và `/topic`; user destination có prefix `/user`.
- STOMP `CONNECT` cần native header `Authorization: Bearer <Firebase ID token>`. Có thể gửi `X-Role: CUSTOMER` hoặc `X-Role: DRIVER` để chọn role hint; server tra Firebase UID trong database và chỉ chấp nhận role có tài khoản hợp lệ, đang hoạt động. Admin không đăng nhập STOMP.
- Client chỉ được subscribe destination có tiền tố `/user/queue/`; cả `SUBSCRIBE` và `SEND` đều cần kết nối đã xác thực.
- Principal được định danh có role: `CUSTOMER:{id}` hoặc `DRIVER:{id}`, tránh nhầm người dùng khác role nhưng trùng ID.
- Broker heartbeat: 10 giây gửi/nhận.

| Chiều | Destination | Nội dung |
|---|---|---|
| App tài xế → server | `/app/location` | Gửi vị trí: `{"location":{"lat":10.77,"lng":106.69}}`. ID tài xế được lấy từ Principal, không gửi trong payload. |
| Server → khách | `/user/queue/rides/{rideId}/location` | Vị trí tài xế trong chuyến đang hoạt động. |
| Server → customer/driver | `/user/queue/trip` | Offer, cập nhật chuyến, thông báo tài khoản và sự kiện chat. |

Thông báo dùng envelope:

```json
{
  "type": "RIDE_UPDATED",
  "payload": { "rideId": 123 },
  "messageId": "uuid"
}
```

Các `type` hiện có: `RIDE_OFFER`, `OFFER_EXPIRED`, `OFFER_CANCELLED`, `RIDE_UPDATED`, `HANDOVER_CONFIRMED`, `RIDE_CANCELLED`, `RIDE_COMPLETED`, `RIDE_NO_DRIVER`, `RIDE_ASSIGNED`, `DRIVER_DOCUMENT_REVIEWED`, `DRIVER_VERIFICATION_UPDATED`, `DRIVER_FORCE_OFFLINE`, `CHAT_MESSAGE`, `CHAT_READ`. Cấu trúc `payload` phụ thuộc từng loại sự kiện.

## FCM

Client đăng ký token qua `POST /api/device-tokens`. FCM gửi data message với một data key là `payload`; giá trị là JSON serialize của envelope ở trên. Token Firebase báo `UNREGISTERED` sẽ được gỡ khỏi database.

Chính sách dispatch của thông báo:

| Chính sách | Ý nghĩa |
|---|---|
| `WS_THEN_PUSH` | Gửi STOMP trước; nếu không gửi được thì thử FCM. |
| `PUSH_ALSO` | Thử cả STOMP và FCM. |
| `WS_ONLY` | Chỉ gửi STOMP. |

| Type | Chính sách |
|---|---|
| `RIDE_OFFER`, `RIDE_UPDATED`, `HANDOVER_CONFIRMED`, `RIDE_CANCELLED`, `RIDE_NO_DRIVER`, `RIDE_ASSIGNED`, `DRIVER_DOCUMENT_REVIEWED`, `DRIVER_VERIFICATION_UPDATED`, `DRIVER_FORCE_OFFLINE`, `CHAT_MESSAGE` | `WS_THEN_PUSH` |
| `RIDE_COMPLETED` | `PUSH_ALSO` |
| `OFFER_EXPIRED`, `OFFER_CANCELLED`, `CHAT_READ` | `WS_ONLY` |

Thông báo nghiệp vụ được lưu vào lịch sử `/api/notifications`. Chat không ghi vào bảng thông báo: `CHAT_MESSAGE` được phát sau khi transaction lưu tin commit xong; nếu người nhận chưa có phiên STOMP subscribe thì server gửi FCM với bản rút gọn. `CHAT_READ` được gửi qua STOMP cho phía gửi tin.

## Upload Cloudinary

Ảnh/file được upload trực tiếp từ app lên Cloudinary. Backend không nhận nội dung file; app xin chữ ký tại `GET /api/uploads/signature`, upload theo chữ ký, sau đó gửi `secureUrl` và `publicId` trong API nghiệp vụ.

- Không truyền `purpose` (hoặc dùng `DEFAULT`): folder mặc định theo người gọi.
- `purpose=DRIVER_DOCUMENT`: chỉ tài xế; folder khóa theo tài xế đăng nhập.
- `purpose=CHAT&rideId={id}` (cũng hỗ trợ `CHAT_IMAGE`): chỉ thành viên chuyến; folder khóa theo chuyến và giới hạn định dạng ảnh `jpg`, `jpeg`, `png`, `webp`.
- `purpose=HANDOVER&rideId={id}` (cũng hỗ trợ `HANDOVER_IMAGE`): chỉ tài xế được gán cho chuyến; folder khóa theo chuyến và giới hạn định dạng ảnh.
- API lưu ảnh kiểm tra asset thuộc đúng folder Cloudinary tương ứng. Chữ ký trả về `signature`, `timestamp`, `apiKey`, `cloudName`, `folder` và `allowedFormats` nếu áp dụng.

## Ghi chú tích hợp

- Số điện thoại customer lấy từ Firebase; API cập nhật hồ sơ không cho sửa số này.
- Các route nghiệp vụ lấy customer/driver ID từ người đăng nhập. Với route có `{id}`, backend kiểm tra quyền sở hữu theo nghiệp vụ.
- `GET /api/customer/rides/active` và `GET /api/driver/rides/active` trả `unreadChatCount` để app khôi phục huy hiệu chat.
- `POST /api/auth/logout` nhận `installationId`; app cũng có thể gỡ thiết bị riêng bằng `DELETE /api/device-tokens/{installationId}`.
- Matching và xóa tài khoản dùng outbox PostgreSQL có lease/retry; thao tác Redis, Firebase và Cloudinary được xử lý sau khi giao dịch nghiệp vụ commit.

## Bảng mã lỗi

Lỗi nghiệp vụ trả cùng cấu trúc `ApiResponse`; HTTP status do `ErrorCode` quyết định.

| Code | HTTP | Ý nghĩa |
|---|---:|---|
| `INTERNAL_ERROR` | 500 | Lỗi hệ thống. |
| `VALIDATION_ERROR` | 400 | Body hoặc tham số không qua validation. |
| `BAD_REQUEST` | 400 | Yêu cầu sai định dạng/không hợp lệ. |
| `UNAUTHORIZED` | 401 | Thiếu hoặc sai thông tin xác thực. |
| `FORBIDDEN` | 403 | Không có quyền thao tác. |
| `NOT_FOUND` | 404 | Không tìm thấy dữ liệu. |
| `CONFLICT` | 409 | Xung đột dữ liệu chung. |
| `USER_NOT_FOUND` | 404 | Không tìm thấy người dùng. |
| `ACCOUNT_NOT_FOUND` | 404 | Firebase user chưa có hồ sơ cho vai trò đang đăng nhập; cần đăng ký. |
| `PHONE_ALREADY_EXISTS` | 409 | Số điện thoại đã đăng ký. |
| `INVALID_CREDENTIALS` | 401 | Thông tin đăng nhập không hợp lệ. |
| `TOKEN_EXPIRED`, `TOKEN_INVALID` | 401 | Firebase token hết hạn hoặc sai. |
| `OTP_INVALID`, `OTP_EXPIRED` | 400 | OTP sai hoặc hết hạn. |
| `ACCOUNT_LOCKED`, `DRIVER_NOT_VERIFIED` | 403 | Tài khoản bị khóa hoặc tài xế chưa được duyệt. |
| `DRIVER_DOCUMENT_NOT_FOUND`, `DRIVER_SKILL_NOT_FOUND`, `VEHICLE_NOT_FOUND`, `NOTIFICATION_NOT_FOUND` | 404 | Không tìm thấy tài liệu, kỹ năng, xe hoặc thông báo. |
| `ACCOUNT_ALREADY_EXISTS` | 409 | Firebase UID đã liên kết với tài khoản khác. |
| `ACCOUNT_HAS_ACTIVE_RIDES` | 409 | Tài khoản còn chuyến chưa kết thúc. |
| `PHONE_MISMATCH` | 400 | Số điện thoại không khớp Firebase. |
| `RIDE_NOT_FOUND` | 404 | Không tìm thấy chuyến. |
| `INVALID_RIDE_STATE`, `RIDE_ALREADY_ASSIGNED`, `RIDE_ALREADY_ACTIVE`, `RIDE_CANNOT_CANCEL` | 409 | Trạng thái chuyến không cho phép thao tác. |
| `HANDOVER_REPORT_REQUIRED` | 400 | Thiếu biên bản bàn giao bắt buộc. |
| `PRICING_RULE_NOT_FOUND` | 404 | Chưa có bảng giá áp dụng. |
| `ROUTE_UNAVAILABLE` | 503 | Không lấy được tuyến đường từ Mapbox. |
| `OFFER_NOT_FOUND` | 404 | Không tìm thấy offer. |
| `OFFER_EXPIRED`, `DRIVER_NOT_AVAILABLE` | 409 | Offer hết hạn hoặc tài xế không sẵn sàng. |
| `LOCATION_UNAVAILABLE` | 404 | Chưa có vị trí tài xế. |
| `CHAT_NOT_AVAILABLE`, `CHAT_CLOSED` | 409 | Hội thoại chưa được tạo hoặc đã đóng. |
| `RATE_LIMITED` | 429 | Vượt giới hạn request. |
| `PAYMENT_NOT_FOUND` | 404 | Không tìm thấy payment. |
| `PAYMENT_ALREADY_PAID` | 409 | Payment đã được thanh toán. |
| `INSUFFICIENT_BALANCE`, `INVALID_SIGNATURE`, `VOUCHER_INVALID` | 400 | Số dư không đủ, chữ ký sai hoặc voucher không hợp lệ. |
| `RATING_ALREADY_EXISTS` | 409 | Đã đánh giá chuyến này. |
