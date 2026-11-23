# Bàn giao phát hành PtitGo

## Candidate theo repository

| Repository | Baseline Git | Phạm vi candidate |
|---|---|---|
| Backend | `integration/backend-release` / `5d31630` | Diff đang có của `src/main`, `src/test`, migration V43–V46 và bộ tài liệu release được review. So sánh thêm 14 commit đã đi trước upstream được cấu hình. |
| React Native | `integration/mobile-release` / `fe1f5d8` | Tám path thay đổi về ticket customer/driver, type/API/UI dùng chung và test. |
| goAdmin | `integration/admin-release` / `a6915a6` | `src/lib/api.ts`, `src/pages/Tickets.tsx`, `src/types.ts`, `src/test/admin-integration.test.tsx`. |
| eKYC | `main` / `a8466e1` | Worktree sạch. Ghim đúng image/provider release đã đối chiếu ở staging. |

Chọn path khi tạo thay đổi phát hành. Các thư mục tooling cá nhân, `skills-lock.json`, ảnh chụp cục bộ, cache, build output, dữ liệu capture và cấu hình thật nằm ngoài candidate. Dùng `git status --short`, `git diff HEAD --` và `git diff --check` trên từng repository khi chốt danh sách. Kiểm tra V43–V46 xuất hiện trong candidate vì hiện là file untracked.

## Thứ tự thực hiện

1. Release owner duyệt báo cáo, chốt danh sách path, SHA backend/RN/goAdmin và digest eKYC. Dùng CI phía nhóm để build lại từ candidate đã chọn, chạy kiểm thử và lưu hash/image.
2. Hạ tầng cấp môi trường staging riêng và quyền theo [điều kiện còn thiếu](RELEASE-BLOCKERS.md). Xác nhận peer ingress, firewall/bind cổng origin, Firebase project, secret references, DB/Redis riêng và lưu trữ eKYC đã mã hóa.
3. DBA tạo và phục hồi bộ backup staging cô lập. Chạy tiền kiểm chỉ đọc, ghi rõ tên môi trường đã che, mốc Flyway, giờ kiểm tra và số lượng bất thường. Dừng nếu có dữ liệu cần đối soát.
4. Khi được duyệt deployment staging, áp dụng candidate backend, kiểm tra migration và health/readiness, sau đó phát hành goAdmin/RN staging. Chạy smoke tự động và ma trận nghiệp vụ từ [checklist staging](STAGING-READINESS-CHECKLIST.md).
5. Diễn tập rollback image/DB/capture trên bản sao staging, đối soát ride, ví, ticket và eKYC. Chốt song song các quyết định trước production.
6. Sau staging đạt, người có quyền thực hiện [production preflight](PRODUCTION-PREFLIGHT.md). Chốt maintenance window, dừng/drain writer theo kế hoạch và xác nhận lại V43 ngay trước DDL. Đối soát yêu cầu rút V44 trước khi phát hành quản trị.
7. Phát hành backend với writer đã kiểm soát rồi đồng bộ goAdmin/RN. Chạy kiểm tra sau triển khai, theo dõi chỉ số ứng dụng và so khớp đối soát trước khi mở lưu lượng bình thường.

## Tiêu chí dừng và rollback

- Dừng trước cutover nếu backup chưa chứng minh phục hồi, checksum Flyway lệch, V43 có nhóm trùng, rút tiền cũ chưa đối soát, hoặc traffic tới origin đi qua đường ngoài sơ đồ.
- Sau cutover, tạm ngừng luồng ghi nếu readiness lỗi kéo dài, các migration chưa đủ, ví/withdrawal không khớp, STOMP/provider lỗi ảnh hưởng nghiệp vụ, hoặc eKYC không đọc được capture. Ghi mốc thời gian, image/digest, mốc migration, trạng thái hàng đợi và số giao dịch cần đối soát. Tránh ghi token, PII hay nội dung ticket.
- Rollback ứng dụng chỉ dùng image, cấu hình, secret references và mount đã xác minh chạy được với schema đã nâng. V44 đổi default, V45 thêm metadata retry và V46 có trigger bất biến. Backend cũ có thể đọc/ghi với hành vi khác, dù app khởi động được. Khi trả về backend cũ, duyệt rút tiền và luồng retry ticket cần tạm kiểm soát cho tới khi đối soát hoàn tất.
- Khi cần rollback schema, DBA phục hồi backup đã diễn tập trên đích cô lập trước. Recovery point có thể mất giao dịch sau mốc backup. Đối soát hoặc replay theo runbook đã duyệt, giữ đồng bộ PostgreSQL, capture eKYC và bộ khóa. Không dùng lệnh sửa Flyway history hay hạ schema tự động trên đích thật.
- Ticket giữ mảng tin nhắn. Backend mới giới hạn trang gần nhất 100 tin trên app cũ. Rollback backend cũ có thể nhận field bổ sung nhưng dedup theo `clientMessageId` chỉ có ở bản mới. Khi cần tải lịch sử cũ, phát hành client đủ tính năng trước khi triển khai rộng.

Tham chiếu [quy trình release/rollback hiện hành](../../release-and-rollback.md) và [báo cáo đợt chuẩn bị](RELEASE-PREPARATION-REPORT.md).
