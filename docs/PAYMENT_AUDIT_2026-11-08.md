# Rà soát thanh toán ngày 08/11/2026

## Phạm vi

Đã đọc lại luồng CASH/WALLET trên app khách, app tài xế và backend: chọn phương thức/mặc định, kiểm tra số dư khi booking, điều kiện bật online/nhận cuốc, biên bản trả xe, hoàn tất chuyến, quyết toán, xác nhận tiền mặt, nạp giả lập, yêu cầu rút, admin duyệt/từ chối, ledger, số dư và thông báo. Chạy toàn bộ bộ kiểm tra tự động của hai repository, không chỉ các test ví.

## Lỗi tìm thấy và đã sửa trong lượt rà soát

1. **Thu nhập chi tiết bị phụ thuộc 100 giao dịch gần nhất:** chuyển sang lấy ledger của chính chuyến tại backend, chỉ trả driverEarnings cho tài xế sở hữu chuyến. Chuyến CASH hiển thị tiền công sau phí và ghi rõ chiết khấu trừ CREDIT; WALLET ghi rõ đã cộng CASH. Không tự suy đoán số tiền khi chưa có ledger.
2. **Thu nhập hôm nay bỏ sót cuốc tiền mặt:** tổng hợp cả CASH và WALLET đã PAID từ payment + ledger, theo paid_at trong ngày Việt Nam. Tiền mặt chưa xác nhận không được tính đã thu. Nhãn header nêu rõ tính theo ngày thanh toán, khác với chỉ số số chuyến hoàn tất hôm nay.
3. **Khách có thể thấy trạng thái chờ sau khi tài xế đã thu tiền:** hóa đơn chuyến COMPLETED/PENDING được kiểm tra lại mỗi 5 giây đến khi hết PENDING; làm mới khi mount/foreground. Xác nhận tiền mặt làm mới toàn bộ cache driver liên quan, gồm daily summary.
4. **Mã nạp/rút có thể mất khi đóng app:** lưu attempt theo backend URL/role/user/action trong AsyncStorage trước POST. Retry cùng số tiền dùng key cũ qua cả lần khởi động lại; chỉ xóa khi có xác nhận thành công. Dữ liệu attempt hỏng chặn thao tác thay vì phát sinh mã mới âm thầm.
5. **Chi tiết chuyến thiếu phương thức đã chọn trước khi thanh toán:** API trả paymentMethod và app hiển thị Tiền mặt/Ví ứng dụng rõ ràng.
6. **Test PostgreSQL trước đó chưa chạy có lỗi shadowing schema:** sửa cấu hình test và chạy thật trên PostgreSQL 16 độc lập. Bổ sung test khởi động Spring với Flyway/Hibernate thật, mock Firebase/Redis để không gọi dịch vụ thật.

## Kết quả kiểm chứng

- `npm run check`: kiến trúc, typecheck toàn workspace, lint hai app và 30 tệp test đều qua.
- Toàn bộ backend: 118 test, 116 qua, 2 bỏ qua do cần dịch vụ bản đồ ngoài; không có test thất bại.
- PostgreSQL tạm: mọi migration từ V1 đến V22 chạy được; Hibernate validate và Spring context khởi động được.
- 7 test WalletPostgresTests chạy thật: rút đồng thời không âm CASH; từ chối đồng thời chỉ hoàn một lần; rollback số dư/ledger; WALLET debit/credit đúng một lần; CASH trừ phí đúng một lần và xác nhận thu tiền không debit thêm; thiếu ví khách rollback cả trạng thái chuyến/marker/payment; nợ CREDIT âm và BLOCKED được lưu thực tế.
- Unit test thông báo xác nhận chỉ gửi sau commit, không gửi khi rollback; lỗi gửi không làm API duyệt đã commit thất bại. Kiểm tra payload FCM có số tiền/lý do và mở đúng request.

## Giới hạn và việc triển khai còn cần làm

- Các thay đổi/migration chỉ được kiểm tra trên DB tạm; không tự chạy lên database thật của người dùng. Backend triển khai cần Flyway đến V22 và app cần cập nhật bản JS tương ứng.
- Phạm vi hiện tại là CASH/WALLET và nạp giả lập dev/test. Chưa tích hợp cổng nạp/thanh toán ngân hàng thật. Admin chuyển khoản thủ công ngoài hệ thống; request rút hiện chưa lưu snapshot đầy đủ thông tin tài khoản ngân hàng nhận tiền.
- Không có điện thoại/emulator đang kết nối qua ADB, nên chưa chạy end-to-end trên giao diện thiết bị thật hoặc xác nhận push FCM đến điện thoại thật.
- Firebase/Redis được mock trong test khởi động; không coi đây là kiểm chứng kết nối các dịch vụ đó ở môi trường triển khai.
- Booking WALLET kiểm tra số dư nhưng không reserve tiền; thanh toán kiểm tra lại khi hoàn tất. Thiếu tiền ở thời điểm đó sẽ rollback hoàn tất. Nếu cần bảo đảm tiền cho nhiều chuyến đặt lịch, phải mở rộng reservation theo nghiệp vụ riêng.
- Thông báo duyệt/rút dùng AFTER_COMMIT theo hạ tầng hiện có, chưa có durable outbox chống mất thông báo khi tiến trình chết giữa commit và gửi. Trạng thái tiền chính thức vẫn đọc được qua API.

Kết luận: các luồng nghiệp vụ trong phạm vi mô phỏng đã được nối và kiểm thử qua backend, app và PostgreSQL. Chưa thể gọi là luồng thanh toán production đã nghiệm thu end-to-end trên điện thoại/ngân hàng thật.
