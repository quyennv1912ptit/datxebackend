# Checkpoint sửa lỗi và đánh giá phát hành PtitGo

## Baseline và phạm vi

- Nhánh `integration/backend-release`, commit `5d316306e7fe07514e295b6b5fcb5ff9725c257a`.
- Worktree ban đầu có 51 mục trong `git status --porcelain`. Giữ các bản sửa audit trước, test đã thêm, thư mục audit, skills và V43.
- Đối chiếu baseline từ `FINDINGS.md`. 28 `VERIFIED_FIXED`, 22 `OPEN`, 1 `PRODUCT_DECISION_REQUIRED`.
- Dùng PostgreSQL 17 và Redis 7 disposable, cổng loopback lần lượt 62667 và 62666. Mỗi nhóm có database riêng. Dùng fake, mock và test doubles cho các dịch vụ ngoài.
- Xác nhận ba repository lân cận tồn tại. Kiểm tra contract sau khi chốt diff.

## Hàng đợi

| Nhóm | Findings | Trạng thái |
|---|---|---|
| Dispatch, lịch và tài nguyên | F-003, F-030, F-031, F-034, F-042, F-043 | 5 VERIFIED_FIXED, F-030 PRODUCT_DECISION_REQUIRED |
| Tài chính và thời gian | F-007, F-008, F-051 | 2 VERIFIED_FIXED, F-051 BLOCKED |
| Hỗ trợ | F-028, F-039, F-040, F-041 | 4 VERIFIED_FIXED |
| Voucher, eKYC, ngày lọc, chat, ảnh, quyền riêng tư và cấu hình | F-011, F-022, F-026, F-032, F-036, F-045, F-046, F-049, F-050 | 7 VERIFIED_FIXED, F-026/F-045 BLOCKED |
| Chính sách lưu giữ | F-018 | PRODUCT_DECISION_REQUIRED |
| Kiểm thử hồi quy, migration, tương thích và release gate | Sau khi tích hợp | Hoàn tất các kiểm tra local, staging và production chưa đạt |


## Kết quả trước tích hợp

- F-007 và F-008. Đã thấy 5 lỗi RED theo dự kiến; sau sửa 29/29 test trên `WalletPostgresTests`, `WithdrawalServiceTests`, `AdminFiltersPostgresTests` đạt. PostgreSQL lưu snapshot mới, giữ dòng lịch sử khi nâng cấp V43 → V44.
- F-051. V44 chuẩn hóa bốn SQL default cho bản ghi mới. Đổi kiểu lịch sử chờ chứng cứ về múi giờ phiên ghi.
- F-045. Đã kiểm tra nguồn. Avatar, ảnh xe và ảnh hỗ trợ có thể cùng tham chiếu một asset. Giữ dữ liệu cho đến khi có theo dõi tham chiếu và chính sách lưu giữ.
- F-011 và F-032. Test xác nhận RED theo đúng điều kiện. F-032 dùng phiên PostgreSQL UTC để tái hiện lệch ngày. Hai test đạt trong lượt targeted sau sửa.
- Lua. Fixture GPS được cập nhật theo khóa throttle mới; 11/11 PASS với liblua5.4 trong Docker.
- Compose. Cấu hình production render thành công với `.env.example` và các giá trị giả lập. Chưa khởi động deployment thật.


## Trạng thái cuối

- Công việc còn lại gồm F-026, F-045 và F-051 ở trạng thái `BLOCKED`, F-018 và F-030 ở trạng thái `PRODUCT_DECISION_REQUIRED`.
- Full Gradle build lần cuối đạt. 82 suite, 324 test, 321 PASS, 0 FAIL, 3 SKIPPED. DB lần cuối là `ptitgo_final_recheck`, schema `public` có đủ 46 migration. Kết quả client và test security ở [báo cáo hoàn thành](BACKEND-AUTONOMOUS-COMPLETION-REPORT.md).
- Worktree cuối có 96 mục theo `git status --porcelain`, gồm các thay đổi tồn tại trước đợt sửa. HEAD vẫn là `5d31630`. Container disposable do đợt này tạo đã dừng và được Docker dọn.
- Bước thi hành tiếp theo ở release gate là kiểm tra cấu hình trusted ingress, origin thực tế, dữ liệu staging và đối soát yêu cầu rút tiền cũ. Điều kiện production bổ sung tiền kiểm V43 chỉ đọc trên schema đích và các quyết định sản phẩm.

## Chuẩn bị phát hành ngày 23/11/2026

- Giữ backend `5d31630`, 96 mục status rút gọn. Chỉ bổ sung bộ hồ sơ phát hành và 2 script trong `docs/audits/`. Worktree ứng dụng RN/goAdmin vẫn là 8/4 path, eKYC sạch.
- 14 suite backend targeted đạt 80/80 trên container disposable. RN `npm run check` 320/320. GoAdmin lint/typecheck/build đạt, full Vitest 107/107. eKYC local fixture 3/3.
- SQL preflight kiểm chứng ở V42/V46 trên dữ liệu tổng hợp với role SELECT-only. Smoke PS script kiểm chứng từ fixture loopback. Compose render và cú pháp deploy script đạt với thiết lập cục bộ.
- Source merge PASS. Staging và production NOT READY. Việc kế tiếp phụ thuộc bàn giao quyền và cấu hình staging, sau đó chạy smoke, nâng cấp và diễn tập phục hồi. Xem [báo cáo chuẩn bị](RELEASE-PREPARATION-REPORT.md) và [bàn giao](RELEASE-HANDOFF.md).
