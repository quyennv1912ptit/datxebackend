# Tiền kiểm dữ liệu trước phát hành PtitGo

Áp dụng từ schema V42 đến V46. Chạy [production-preflight.sql](production-preflight.sql) trước khi nâng cấp và chạy lại sau khi migration hoàn tất, trước khi mở lưu lượng. Dùng cùng database và schema ở hai lượt. Các kết quả trên môi trường đích hiện **chưa xác minh**.

## Cách chạy

1. Dùng profile kết nối PostgreSQL được cấp riêng với quyền `USAGE` trên schema và `SELECT` trên các bảng được kiểm tra. Xác minh máy chủ, database và schema từ thông tin vận hành đã được duyệt. Khi tiền kiểm ở replica, cần xác minh replica đã theo kịp và không dùng kết quả cũ làm căn cứ cutover.
2. Từ thư mục gốc repository, chạy lệnh sau. Thay tên profile và schema bằng các giá trị đã xác minh. Profile kết nối và cơ chế cấp xác thực thuộc cấu hình vận hành ngoài repository.

   ```bash
   PGSERVICE=ptitgo_release_readonly psql -X -v ON_ERROR_STOP=1 \
     -v target_schema=public \
     -f docs/audits/2026-11-23-5d31630/production-preflight.sql
   ```

3. Đọc phần đầu kết quả để xác nhận đúng database, schema và `transaction_read_only = on`. Script dùng một snapshot `REPEATABLE READ READ ONLY`, giới hạn chờ khóa 3 giây và mỗi câu truy vấn 2 phút, kết thúc bằng `ROLLBACK`. Script chỉ xuất số đếm và metadata kỹ thuật. Lưu kết quả ở nơi vận hành có kiểm soát truy cập.
4. Nếu có lỗi quyền, timeout, thiếu bảng hoặc sai schema, ghi nhận **chưa xác minh** và xử lý nguyên nhân trước khi tiếp tục. Mã thoát 0 cho biết các truy vấn chạy xong. Điều kiện đạt nằm trong từng mục dưới đây. Phải đối chiếu dữ liệu dưới cửa sổ ghi được kiểm soát để các số đếm trước và sau có thể so sánh.

## V43 · Trùng CCCD và GPLX

- Kiểm tra cả bốn trạng thái `APPROVED`, `SUBMITTED`, `UNDER_REVIEW`, `NEEDS_MORE_INFO`. CCCD nhóm theo giá trị `locked_cccd_id` nguyên trạng khác `NULL`. GPLX nhóm theo `UPPER(TRIM(verified_gplx_number))`, bỏ `NULL` và giá trị rỗng sau `TRIM`. Đây là cùng predicate và biểu thức với V43.
- Cả hai `duplicate_groups` phải bằng 0. Nếu khác 0, dừng trước V43. Chuyển số đếm cho người có thẩm quyền đối soát eKYC trong môi trường được kiểm soát. Quyết định trạng thái hồ sơ theo quy trình nghiệp vụ và chạy lại tiền kiểm sau khi các luồng ghi danh tính đã dừng. Không chép số giấy tờ, UID hoặc hồ sơ chi tiết vào nhật ký phát hành.
- Sau nâng cấp, kiểm tra đúng một mục Flyway thành công cho V43, hai chỉ mục có `usable = t`, và số trùng vẫn bằng 0. Nếu có mục Flyway lỗi hoặc mất chỉ mục, dừng cutover và kiểm tra triển khai/schema.

## V44 · Snapshot rút tiền

- Đối chiếu `pending` và `pending_without_matching_hold` với sổ cái. Số bất thường về giữ tiền phải bằng 0. Trước V44, toàn bộ yêu cầu cũ chưa có snapshot. Sau V44, `pending_without_snapshot` cho biết lượng yêu cầu cũ còn cần đối soát. `invalid_snapshots` phải bằng 0.
- Nếu yêu cầu cũ chưa có snapshot, đối soát khoản chuyển thủ công và xác nhận trạng thái bên nhận. Với yêu cầu đã chuyển thủ công hoặc chưa rõ trạng thái, dừng xử lý để tài chính đối soát. Với yêu cầu đã xác nhận chưa chuyển, quản trị viên có thể dùng luồng từ chối/hoàn tiền đã được phê duyệt, để tài xế gửi yêu cầu mới. Tránh điền snapshot lịch sử từ hồ sơ ngân hàng hiện tại hoặc tự đổi trạng thái sổ cái.
- Sau V44, kiểm tra ràng buộc snapshot `validated = t`. Trên staging, dùng tài khoản tổng hợp tạo yêu cầu mới, sửa hồ sơ ngân hàng rồi kiểm tra yêu cầu vẫn hiển thị tài khoản đã lưu. Kiểm tra idempotency và nhánh từ chối/hoàn tiền. Thống nhất dừng phiên bản backend cũ trong cutover vì phiên bản cũ vẫn có thể tạo yêu cầu không có snapshot.

## V45 · Phân trang và idempotency ticket

- Lưu số ticket, tin nhắn và ảnh đính kèm trước nâng cấp. Đối chiếu sau nâng cấp dưới cùng cửa sổ ghi. Metadata idempotency có thể `NULL` trên tin nhắn cũ và lượt ghi từ client không gửi khóa.
- Sau V45, ba chỉ mục phải có `usable = t` và `duplicate_message_keys = 0`. Nếu có bản ghi trùng hay thiếu chỉ mục, dừng cutover và đối chiếu với Flyway, lịch sử cập nhật và các log kỹ thuật giới hạn truy cập. Giữ nội dung tin nhắn và bằng chứng trong hệ thống.
- Trên staging, kiểm tra thứ tự trang với các tin cùng `created_at`, rồi gửi lại một thao tác có cùng `clientMessageId` trong cùng ticket, người gửi và vai trò. Xác minh chỉ có một tin nhắn và một thông báo. Kiểm tra request cũ không gửi khóa vẫn được xử lý theo hợp đồng hiện hành.

## V46 · Snapshot giá và lịch sử chỉnh bảng giá

- Lưu số chuyến và bảng giá trước nâng cấp. Snapshot `NULL` của chuyến cũ là trạng thái được hỗ trợ. Đối chiếu trên staging bằng dữ liệu tổng hợp để xác nhận `fare_estimate`, `final_fare` và thanh toán lịch sử được giữ qua nâng cấp.
- Sau V46, `partial_snapshots` và `orphan_pricing_rules` phải bằng 0. Xác nhận chỉ mục audit, foreign key và check constraint đều đạt, trigger `fare_snapshot_trigger_enabled = t`. Khi có bất thường, dừng cutover và so sánh phiên bản/schema với Flyway. Giữ giá và dữ liệu thanh toán gốc.
- Trên staging, tạo chuyến mới rồi chỉnh bảng giá bằng tài khoản test. Xác minh snapshot chuyến vẫn giữ đủ sáu trường và số bản ghi audit thay đổi bảng giá tăng. Chính sách hiệu lực báo giá trước khi tạo chuyến vẫn thuộc `OBS-14`.

## F-051 · Múi giờ dữ liệu lịch sử

- `current_session_timezone` là múi giờ của kết nối tiền kiểm hiện tại. Các dòng metadata cho biết kiểu timestamp và default, còn `populated_rows` cho biết phạm vi phải rà soát. Sau V44, bốn cột có `default_normalizes_to_utc = t` gồm `withdrawal_requests.created_at`, `ride_wallet_settlements.created_at`, `wallet_transactions.created_at` và `wallet_transactions.updated_at`. Ba cột còn lại không có default trong các migration hiện hành.
- Để quyết định chuyển kiểu, tập hợp bằng chứng theo từng giai đoạn và đường ghi gồm cấu hình session/role/server PostgreSQL, múi giờ JVM/JDBC, SQL import/backfill và các sự kiện có thời điểm tuyệt đối đáng tin cậy để đối chiếu trên bản sao. Nếu có giai đoạn không đủ chứng cứ hoặc dữ liệu trộn múi giờ, giữ `F-051 BLOCKED`. Chưa có căn cứ chuyển kiểu timestamp lịch sử hay áp một offset chung.

## F-045 · Ảnh Cloudinary có nhiều tham chiếu

- Script chỉ thống kê asset của xe đã lưu trữ và các tham chiếu thấy được qua public ID trong ảnh xe đang hoạt động, avatar, ảnh ticket, chat và bàn giao. Tin nhắn hỗ trợ chỉ lưu URL được so sánh theo URL trùng chính xác. Kết quả chỉ chứa tổng số và các nhóm có thể giao nhau.
- Khi có tham chiếu chung, giữ ảnh và bản ghi liên kết. Nhóm `no_match_in_checked_locations` cần xem xét thêm vì URL biến thể, thông tin trong snapshot và luồng ghi đồng thời có thể nằm ngoài kết quả. Giữ `F-045 BLOCKED` cho đến khi có quy tắc lưu giữ `OBS-13`, nhận diện tài sản chuẩn và theo dõi tham chiếu. Tiền kiểm này không gọi Cloudinary hoặc đưa ra danh sách tài sản để xóa.

## Sau nâng cấp

Xác nhận đúng một mục Flyway thành công và không có mục lỗi cho từng migration V43–V46. Lưu hai lượt số đếm, kết quả smoke test staging và quyết định xử lý ngoại lệ cùng bằng chứng phát hành. Thực hiện phê duyệt theo [quy trình phát hành và khôi phục](../../release-and-rollback.md).

## Kiểm chứng script với dữ liệu tổng hợp

Đã chạy `production-preflight.sql` bằng role chỉ có `SELECT` trong schema test trên PostgreSQL 17.11 disposable. Áp dụng SQL migration qua `psql` và mô phỏng metadata Flyway cho phép thử này.

| Lượt | Kết quả |
|---|---|
| Trước V43, schema V42 | Báo 2 nhóm CCCD/4 hồ sơ và 1 nhóm GPLX/2 hồ sơ theo fixture. Phân biệt yêu cầu rút tiền thiếu giữ tiền và ảnh xe đã lưu trữ có tham chiếu chung. |
| Thử áp dụng V43 khi còn trùng | Câu lệnh tạo unique index dừng giao dịch với fixture trùng. |
| Sau V46, session `Asia/Ho_Chi_Minh` | Báo 0 nhóm trùng, phát hiện bốn default UTC, xác nhận các chỉ mục và constraint, đếm đúng snapshot lịch sử và snapshot mới. Số đếm ảnh dùng chung giữ nguyên. |
