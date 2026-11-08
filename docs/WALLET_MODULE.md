# Module Thanh toán & Ví

Mã nguồn nằm tại `src/main/kotlin/com/nhom15/datxebackend/payment/`:

- `entity/Wallet.kt`: Wallet, WalletTransaction, WithdrawalRequest và enum.
- `repository/WalletRepository.kt`: repositories JPA, khóa ghi ví và yêu cầu rút.
- `service/WalletService.kt`: số dư, kiểm tra đặt/nhận chuyến, quyết toán, giữ/hoàn tiền.
- `service/WithdrawalService.kt`: yêu cầu rút, idempotency và admin review.
- `service/PaymentService.kt`: tích hợp thanh toán trong transaction kết thúc chuyến.
- `controller/WalletOperationsController.kt`: API ví, rút tiền, admin và mock top-up.
- `dto/WalletOperationsDtos.kt`: DTO API; không trả Hibernate entities.
- `exception/InsufficientBalanceException.kt`: lỗi thiếu số dư, dùng envelope lỗi chung.
- Migration `V18__wallet_module.sql`: bảng, constraint, index, chuyển dữ liệu cũ.

## Quy tắc tiền và transaction

Tiền là `Long`/PostgreSQL `bigint`, đơn vị VND, không dùng floating point.
Chiết khấu = `fare / 5` (20%, làm tròn xuống đến đồng). Tiền tài xế = `fare - commission`.
Ví dụ 100.000đ: CASH trừ CREDIT 20.000đ; WALLET trừ CUSTOMER 100.000đ, cộng CASH 80.000đ.

Booking WALLET kiểm tra cước sau voucher. Booking CASH không đọc ví khách. Chỉ CASH và WALLET được chấp nhận; các cổng thật chưa tích hợp.
Booking không giữ tiền: hệ thống kiểm tra lại khi quyết toán. Nếu ví khách không đủ ở thời điểm này, toàn bộ transaction kết thúc chuyến rollback; chuyến chưa được đánh dấu hoàn thành. Nếu muốn bảo đảm thanh toán cho các chuyến đặt lịch đồng thời, cần mở rộng reservation, chưa nằm trong luồng tinh gọn này.

Khi nhận chuyến, CREDIT phải >= `wallet.minimum-credit` (mặc định 50.000đ); cuốc CASH còn cần đủ phí chiết khấu dự kiến. Matching lọc tài xế thiếu mức tối thiểu, acceptance kiểm tra lại bằng khóa trong transaction. Số dư sau trừ phí có thể dưới 50.000đ; tài xế cần nạp trước chuyến tiếp theo.

`RideStateMachine.transition` khóa chuyến trước khi cập nhật. Sự kiện `RideCompleted` được xử lý `BEFORE_COMMIT` trong chính transaction đó. Bản ghi duy nhất `ride_wallet_settlements(ride_id)` chống quyết toán lặp. Nếu bất kỳ update ví/ledger nào thất bại, cả chuyến, marker và payment rollback. Không gọi quyết toán qua endpoint công khai.

Mọi thay đổi số dư nằm trong `@Transactional`, ghi ledger cùng transaction. `WalletRepository.lockWallet` dùng `@Lock(PESSIMISTIC_WRITE)`. Khóa chủ tài khoản và bản ghi ví trước khi kiểm tra/cập nhật số dư. V19 khởi tạo ví khi tạo tài khoản, không tự sửa dữ liệu thiếu ví trong lúc thanh toán. Khóa được DB giải phóng khi commit/rollback. Không tự động retry mutation không có idempotency key.

CREDIT/CASH của driver độc lập. Các cột `drivers.credit_balance`, `drivers.income_balance`, `customers.wallet_balance` được backfill vào bảng `wallets` và cập nhật đồng thời để tương thích API/admin cũ. Sau migration, mọi mutation phải qua WalletService, không sửa trực tiếp các cột legacy.

Ledger dùng wallet_type CREDIT/CASH/CUSTOMER và transaction_type TOP_UP/WITHDRAW/RIDE_PAYMENT/COMMISSION_FEE/REFUND. Các cột transaction_id, user_id, user_type, reference_trip_id là generated aliases từ khóa cũ. Cột `type` vẫn giữ tên legacy DEPOSIT/TRIP_PAYMENT/TRIP_EARNING để app cũ đọc được. Không sửa/xóa ledger; riêng trạng thái khoản giữ tiền WITHDRAW chuyển PENDING sang SUCCESS/FAILED khi review.

Cuốc CASH giữ payment PENDING cho đến khi tài xế xác nhận đã nhận tiền qua API hiện hữu; phí nền tảng đã trừ khi kết thúc. Cuốc WALLET chuyển PAID ngay khi quyết toán. Thu nhập trên header lấy từ ledger thực tế của các khoản đã PAID trong ngày Việt Nam: WALLET dùng TRIP_EARNING, CASH dùng tiền khách trả trừ COMMISSION_FEE. Tính theo paid_at; không tính cuốc tiền mặt chưa xác nhận đã thu.

## API

Bearer token bắt buộc. Các API của khách/tài xế lấy ID từ token để tránh truy cập ví người khác.

| Method | Endpoint | Quyền | Nội dung |
|---|---|---|---|
| GET | `/api/wallet` | CUSTOMER/DRIVER | Số dư các ví của mình |
| GET | `/api/wallet/transactions?page=0` | CUSTOMER/DRIVER | Ledger phân trang, 20 bản ghi/trang |
| POST | `/api/driver/withdrawals` | DRIVER | Tạo yêu cầu rút, header `Idempotency-Key` |
| GET | `/api/driver/withdrawals?page=0` | DRIVER | Yêu cầu rút của mình |
| POST | `/api/admin/withdrawals/{id}/review` | ADMIN | APPROVE hoặc REJECT |
| POST | `/api/mock/wallet/top-up` | ADMIN + dev/test | Nạp giả lập |

Ví dụ tạo yêu cầu rút:

```http
POST /api/driver/withdrawals
Authorization: Bearer <driver-token>
Idempotency-Key: withdrawal-2026-001
Content-Type: application/json

{"amount":50000}
```

Gửi lại cùng key và amount trả về yêu cầu cũ, không giữ tiền lần hai; cùng key khác amount trả 409. Key dài tối đa 100 ký tự.

```http
POST /api/admin/withdrawals/9/review
Authorization: Bearer <admin-token>
Content-Type: application/json

{"action":"REJECT","rejectReason":"Sai số tài khoản ngân hàng"}
```

APPROVE: PENDING → SUCCESS, không trừ lại. REJECT: PENDING → REJECTED, cộng lại CASH và ghi REFUND. Mọi lần review khi không còn PENDING, kể cả cùng action, đều trả 409 INVALID_WITHDRAWAL_ACTION. Khóa request ngăn hai admin hoàn/duyệt đồng thời. Đây là phê duyệt mô phỏng, không gọi ngân hàng.

Bật mock top-up bằng profile `dev` hoặc `test` **và** `wallet.mock-topup-enabled=true` (mặc định tắt):

```http
POST /api/mock/wallet/top-up
Authorization: Bearer <admin-token>
Content-Type: application/json

{"userId":7,"userType":"DRIVER","walletType":"CREDIT","amount":100000}
```

Có thể gửi tên trường `user_id`, `wallet_type`, `user_type`; `userType` được suy ra từ `walletType` nếu không truyền.

Ví khách: `userType=CUSTOMER`, `walletType=CUSTOMER`. Ví nhận công tài xế: `userType=DRIVER`, `walletType=CASH`. Endpoint mock coi mỗi request là một lần nạp mới; không retry tự động.

## Kiểm thử và triển khai

1. Sao lưu DB, chạy Flyway V18 và V19 bằng luồng khởi động ứng dụng hiện có. Không chạy migration trên dữ liệu âm chưa đối soát; constraint sẽ từ chối thay vì âm thầm sửa số dư.
2. Chạy `./gradlew test` trên Java 21 với dependency truy cập được.
3. Unit tests mới: `WalletServiceTests`, `WithdrawalServiceTests`: chia tiền, thiếu số dư, min credit, giữ tiền, overflow, review lặp và refund một lần.
4. `WalletPostgresTests` chạy khi có `TEST_DB_URL`, `TEST_DB_USER`, `TEST_DB_PASSWORD`: kiểm tra hai request rút đồng thời, hai admin từ chối đồng thời và rollback sau khi ghi ledger. Mỗi test tạo schema riêng rồi xóa; dùng database test riêng có quyền CREATE SCHEMA. Ví dụ: `./gradlew test --tests '*WalletPostgresTests'`. Unit mock không chứng minh được khóa/rollback PostgreSQL thật.

Tham chiếu: https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html và https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html.

## Bổ sung kiểm soát lỗi (V19)

- `BaseAppException` → `AppException` → các exception cụ thể trong `payment/exception/WalletExceptions.kt`.
- Thiếu ví trả `WALLET_NOT_FOUND`; không tồn tại tài khoản trả `USER_NOT_FOUND`. Trigger provision ví cùng transaction tạo tài khoản. Migration V18 đã backfill tài khoản có trước đó.
- `INSUFFICIENT_CUSTOMER_BALANCE`, `CREDIT_WALLET_BELOW_MINIMUM`, `INSUFFICIENT_CREDIT_BALANCE`, `INSUFFICIENT_CASH_BALANCE` phân biệt từng nguyên nhân.
- Hoàn thành chỉ từ `VEHICLE_RETURNED` có biên bản trả xe được xác nhận theo state machine hiện tại, không bỏ qua bước bàn giao bằng cách hoàn thành trực tiếp từ `IN_PROGRESS`. Trạng thái sai trả `INVALID_RIDE_STATE` qua `InvalidTripStateException`.
- Cuốc CASH thiếu chiết khấu: vẫn trừ CREDIT thành âm và ghi ledger, driver chuyển `BLOCKED`. Không throw sau khi ghi nợ vì sẽ rollback. CASH và CUSTOMER vẫn không được âm. BLOCKED không thể bật online, nhận cuốc hoặc được matching chọn. Nạp bù CREDIT có thể chia nhiều lần; đủ mức duy trì thì trở lại OFFLINE, tài xế tự bật online.
- Rút từ CREDIT hoặc ví sai chủ: `INVALID_WALLET_TYPE`. Số tiền rút <= 0: `INVALID_AMOUNT`. Không áp dụng quy tắc bội số: mọi số nguyên đồng dương hợp lệ.
- Top-up 1..50.000.000 VND mỗi lần, ngoài khoảng trả `INVALID_TOP_UP_AMOUNT`. JSON số thập phân bị từ chối, không tự cắt phần lẻ.
- Chỉ PENDING mới được review; mã không tồn tại trả `WITHDRAWAL_REQUEST_NOT_FOUND`, lặp action hoặc action sai trả `INVALID_WITHDRAWAL_ACTION`.
- Pool PostgreSQL cấu hình `lock_timeout=3s` cho kết nối. Pessimistic/lock timeout trả HTTP 503 `WALLET_BUSY` kèm `Retry-After: 3`; không nuốt exception trong transaction và không tự retry các mutation.
- Quyết toán chọn phương thức từ dữ liệu chuyến, không nhận cờ CASH/WALLET từ client. API xác nhận CASH cho chuyến WALLET trả `PAYMENT_METHOD_MISMATCH`.

Phản hồi lỗi có `timestamp` theo giờ Việt Nam, `status`, `error_code`, `message`; vẫn giữ `success:false` và `error:{code,message}` để tương thích mobile hiện tại. Ví dụ:

```json
{
  "success": false,
  "timestamp": "2026-11-08T09:53:00+07:00",
  "status": 409,
  "error_code": "INSUFFICIENT_CUSTOMER_BALANCE",
  "message": "Số dư ví khách hàng không đủ",
  "error": { "code": "INSUFFICIENT_CUSTOMER_BALANCE", "message": "Số dư ví khách hàng không đủ" }
}
```

## Chặn nhận chuyến trên app tài xế

`GET /api/driver/wallet/credit-status` trả creditBalance, minimumCredit, shortfall, canReceive, blocked và mockTopUpEnabled. Hạn mức lấy từ server. App kiểm tra khi khởi động, vào màn hình, trở lại foreground và mỗi 30 giây. Chưa tải được ví/lỗi mạng thì không bật nhận chuyến; chuyến đang chạy vẫn tiếp tục. Trước khi bật online/nhận cuốc, app gọi lại API và backend cũng kiểm tra dưới khóa. Thông báo cuốc hoặc deep link không vượt qua màn hình kiểm tra này.

Trang chủ có cảnh báo, số dư, khoản cần nạp và nút Nạp tiền. Trang Ví tài xế hiển thị riêng CREDIT và thu nhập. Màn Nạp ví tín dụng có xác nhận số tiền, không tự bật online sau nạp.

Trong profile dev/test và `wallet.mock-topup-enabled=true`, tài xế có thể gọi `POST /api/driver/wallet/top-up` với `{ "amount": 50000 }` và header `Idempotency-Key`. Server lấy chủ ví từ token, chỉ cộng CREDIT; không nhận userId để cộng ví người khác. V20 lưu receipt chống cộng lặp khi retry. Đây là tiền giả lập, không phải cổng thanh toán thật. Khi tính năng tắt, app hiển thị nút liên hệ hỗ trợ và kiểm tra lại số dư.

## Kết nối đầy đủ với hai app (V21)

- Khách: số dư và nút nạp ở Trang chủ/Hồ sơ; `/wallet` có số dư, lịch sử toàn bộ phân trang; `/wallet/top-up` xác nhận nạp. Phương thức mặc định hỗ trợ CASH/WALLET; mỗi chuyến có thể chọn lại trên màn Xác nhận. Ví thiếu tiền/lỗi kiểm tra số dư sẽ chặn đặt bằng WALLET; CASH vẫn dùng được. Trước khi gửi booking, app tải lại báo giá và số dư, backend kiểm tra lần cuối trong transaction.
- Tài xế: `/profile/wallet` hiển thị riêng CREDIT/CASH từ API tổng quan; lịch sử lọc theo ví trên server và tải thêm đến trang cuối. `/profile/withdraw` tạo lệnh và xem toàn bộ lịch sử yêu cầu, PENDING/SUCCESS/REJECTED, lý do và thời điểm xử lý; tự làm mới mỗi 30 giây. Khi từ chối có giao dịch REFUND riêng.
- `GET /api/wallet/overview`: balances, mockTopUpEnabled, maxTopUpAmount. Không quảng bá khả năng mock trong production.
- `GET /api/wallet/transactions?page=0&walletType=CREDIT`: ví tùy chọn. Response ổn định: content, number, size, totalElements, totalPages, last. Không giới hạn tổng lịch sử ở 100 bản ghi. App loại trùng ID giữa các trang khi có giao dịch mới và làm mới khi quay lại màn hình.
- `POST /api/customer/wallet/top-up`: body `{ "amount": 50000 }`, header Idempotency-Key. Tương tự tài xế, chủ ví lấy từ token; chỉ khả dụng dev/test + bật mock. V21 lưu receipts riêng theo khách để retry không cộng hai lần.
- App giữ key theo tài khoản và số tiền khi request không rõ kết quả. Lệnh rút đã giữ tiền nhưng mất response có thể retry bằng key cũ mà không bị chặn nhầm bởi số dư khả dụng đã giảm. Thành công mới xóa attempt và làm mới số dư/ledger/yêu cầu.
- Các API duyệt lệnh, mock nạp theo userId của ADMIN không được gọi từ app khách/tài xế. Không thêm thẻ ngân hàng hoặc cổng thanh toán chưa tồn tại. Khi mock tắt, màn nạp có nút liên hệ hỗ trợ, không tự cộng số dư.

UI dùng component chung MD3 cho thẻ số dư, trạng thái tải/lỗi/rỗng, lịch sử và form tiền. Dialog xác nhận có số tiền cụ thể; nút bị khóa khi đang gửi. Màu từ theme sáng/tối, số tiền dùng chữ số tabular, nhãn trạng thái bằng chữ và vùng chạm tối thiểu 48dp cho hành động/preset tiền.

## Admin duyệt/từ chối rút tiền (V22)

- APPROVE khóa request và chỉ chấp nhận PENDING. Đổi request SUCCESS và ledger giữ tiền SUCCESS, lưu reviewedBy/reviewedAt, không debit CASH thêm lần nào.
- REJECT bắt buộc rejectReason (hoặc reject_reason) từ 1 đến 500 ký tự sau trim. Khóa ví CASH, cộng lại khoản giữ, tạo REFUND gắn withdrawalId, đổi ledger cũ FAILED và request REJECTED. Tất cả trong một transaction. Kiểm tra ledger đúng chủ ví, số tiền và trạng thái trước khi hoàn.
- Đã xử lý rồi (dù lặp lại cùng action) trả InvalidWithdrawalActionException. Không hoàn thêm hoặc gửi thêm thông báo.
- WithdrawalReviewed chỉ gửi tới NotificationPort sau commit. Kênh PUSH_ALSO gọi cả WebSocket và FCM; FCM có nội dung số tiền/kết quả/lý do. Lịch sử thông báo có transaction riêng REQUIRES_NEW. Lỗi gửi hoặc lỗi lưu thông báo không đảo ngược việc duyệt/hoàn tiền và không trả lỗi giao dịch đã commit.
- Đây là thông báo best-effort theo hạ tầng hiện có: cần cấu hình Firebase/token hợp lệ; chưa có durable notification outbox cho trường hợp tiến trình chết giữa commit và gửi. App vẫn đọc trạng thái/lý do chính thức qua API.
- `GET /api/driver/withdrawals/{id}` chỉ trả request của tài xế hiện tại. Nhấn thông báo mở `/profile/withdraw?requestId=...`, tải đúng request kể cả đã nằm ngoài trang lịch sử đầu tiên.
- V22 chuyển request FAILED cũ thành REJECTED; ledger FAILED vẫn giữ nguyên. Phía app đọc được cả FAILED cũ và REJECTED mới.

## Kiểm chứng bổ sung ngày 08/11/2026

Xem `PAYMENT_AUDIT_2026-11-08.md` cho kết quả kiểm thử PostgreSQL thật, các lỗi đã sửa và giới hạn nghiệm thu. Chi tiết chuyến có driverEarnings từ ledger của chính chuyến, không phụ thuộc danh sách 100 giao dịch; khách thấy paymentMethod và hóa đơn PENDING tiếp tục cập nhật đến khi đã thu tiền. Mã nạp/rút được lưu trên thiết bị để retry sau khi đóng app.

## Đổi phương thức khi khoản thanh toán còn PENDING (V23)

`PUT /api/customer/rides/{rideId}/payment/method`, token CUSTOMER, header Idempotency-Key:

```json
{"method":"WALLET","expectedMethod":"CASH"}
```

Chỉ chủ chuyến COMPLETED có payment PENDING được đổi. Chỉ CASH/WALLET; expectedMethod phải khớp dữ liệu mới nhất. Đây là thay đổi của riêng chuyến, không đổi mặc định Hồ sơ. Gửi lặp cùng key/nội dung trả lại kết quả, không thu thêm; khác nội dung hoặc đã đổi sang phương thức khác trả conflict.

- CASH → WALLET: đọc phí đã trừ thực tế từ ledger, debit ví khách toàn bộ cước, hoàn phí cũ về CREDIT và ghi REFUND có mô tả, credit CASH tiền công sau phí. Tất cả cùng transaction và payment chuyển WALLET/PAID ngay. Thiếu tiền hoặc dữ liệu ledger không hợp lệ thì rollback toàn bộ.
- WALLET/PENDING chưa phát sinh thu/chi ví → CASH: ghi chiết khấu vào CREDIT và chuyển CASH/PENDING. Không đảo ngược giao dịch WALLET đã PAID hoặc có ledger thanh toán thực tế.
- Hóa đơn PAID/FAILED/REFUNDED không đổi được. WALLET được thu ngay khi hoàn tất theo luồng hiện hành nên thường đã PAID; nút đổi chỉ xuất hiện khi thực sự còn PENDING.
- Đổi phương thức và xác nhận đã thu tiền mặt cùng khóa ride trước, rồi payment FOR UPDATE. Khi hai thao tác chạy đồng thời chỉ một đường thu tiền thắng. Confirm CASH có điều kiện method='CASH'; giao diện cũ không thể xác nhận thu thêm sau đổi ví.
- V23 lưu payment_method_changes để audit và chống lặp. Không xóa các giao dịch cũ: giữ debit chiết khấu CASH và ghi refund riêng.
- PAYMENT_UPDATED phát sau commit tới driver và customer qua WebSocket/FCM, có nội dung rõ "không thu thêm tiền mặt" khi đã thu qua ví. Nhấn thông báo mở hóa đơn đúng vai trò. Lỗi thông báo không rollback tiền; polling và làm mới khi foreground là dự phòng.
- App khách có dialog xác nhận, số dư, nút nạp và thông báo sẽ thu ngay khi đổi sang Ví. Key được lưu trên thiết bị để retry sau lỗi mạng/đóng app. Thành công cập nhật hóa đơn, ví và lịch sử. App tài xế đóng xác nhận cũ/bỏ nút thu CASH khi hóa đơn đổi sang ví, làm mới thu nhập và ledger.

Test PostgreSQL thực kiểm tra: đổi và retry không thu lặp; không hoàn CREDIT một phần khi ví khách thiếu; chặn PAID/sai chủ; đổi WALLET chưa thu sang CASH; cuộc đua đổi phương thức với xác nhận CASH chỉ có một kết quả thu tiền.

### Hồ sơ ngân hàng và giao diện duyệt rút tiền

- Driver app: `GET /api/driver/profile` đọc `bank_name`, `bank_account_number`.
- Form cập nhật hồ sơ lưu nguyên tử qua `PUT /api/driver/profile/identity` với
  `{fullName, email, bankName, bankAccountNumber}`. Các trường ngân hàng là chuỗi,
  tối đa 255 ký tự; giữ nguyên số 0 đầu tài khoản. Không gửi trường ngân hàng thì
  giữ dữ liệu cũ; gửi chuỗi rỗng để xóa. Endpoint này không thay đổi hồ sơ bằng lái.
- Driver app hiển thị tài khoản nhận tiền trên màn hình rút tiền, yêu cầu điền đủ
  tên ngân hàng và số tài khoản trước khi tạo yêu cầu mới.
- Web Admin (goAdmin): trang `/withdrawals` gọi `GET /api/admin/withdrawals`
  với `page`, `size`, `status` (bỏ status để xem tất cả); phân trang dùng `PageResponse`.
- Duyệt/từ chối gọi `POST /api/admin/withdrawals/{id}/review` với
  `{action:"APPROVE"}` hoặc `{action:"REJECT",rejectReason:"..."}` (1–500 ký tự).
  Chỉ hiển thị thao tác với PENDING. Admin phải xác nhận đã chuyển khoản ngoài
  thành công trước khi duyệt. Duyệt không trừ ví lần nữa; từ chối hoàn tiền theo
  service hiện có. Giao diện chặn duyệt nếu thiếu tài khoản ngân hàng.
- Danh sách tự làm mới mỗi 15 giây và sau thao tác. Khi máy chủ báo xung đột hoặc
  lỗi kết nối, danh sách được tải lại để kiểm tra trạng thái trước khi thử lại.
- Cần migration `V24__driver_bank_name.sql` của backend mới khi triển khai.
