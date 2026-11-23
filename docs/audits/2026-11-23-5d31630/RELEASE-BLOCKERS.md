# Điều kiện còn thiếu để phát hành PtitGo

Ngày 23/11/2026. Các mục kỹ thuật dưới đây là điều kiện vận hành của đợt phát hành này. Trạng thái 51 finding tiếp tục theo [FINDINGS.md](FINDINGS.md).

## Bắt buộc trước staging

| Việc | Chủ trì | Bằng chứng cần cung cấp |
|---|---|---|
| Cấp quyền môi trường | Release owner | URL HTTPS staging và goAdmin staging, Firebase project ID dành riêng cho staging, thời gian được kiểm thử, danh sách quyền và workflow được phép ghi, người vận hành rollback. Token qua secret manager. |
| F-026 và ingress | Hạ tầng | Sơ đồ edge → origin, địa chỉ peer quan sát ở Tomcat và CIDR tối thiểu cho `APP_TRUSTED_EDGE_PROXIES`. Bằng chứng chỉ đường ingress đã duyệt tới được origin, bao gồm bind/firewall cổng 8080 và cấu hình header tại mọi proxy. Xác nhận `APP_CORS_ALLOWED_ORIGINS` cho HTTP/WS và origin thực tế của từng client. |
| Dữ liệu, tác dụng phụ và tiền | QA cùng hạ tầng | Dataset tổng hợp và tài khoản customer/driver/admin riêng, database/Redis staging, Firebase/FCM và Cloudinary riêng, eKYC test tenant, payment sandbox nếu có adapter đã triển khai. Khi adapter chưa có, giới hạn môi trường vào `CASH` và `WALLET` nội bộ với số dư giả lập. Quyết định phạm vi xóa tài khoản, ảnh eKYC, gửi push, voucher và đối soát rút tiền trên fixture. |
| Tiêu chí chấp nhận staging | QA, sản phẩm | Xác nhận kịch bản cần nghiệm thu cho quyền tài xế bị hạn chế (OBS-11), xử lý thiếu tiền ví (OBS-01), giới hạn đặt trước (F-030) và quote đổi giá (OBS-14). Nếu quy tắc chưa chốt, đánh dấu kịch bản liên quan chờ quyết định và ghi riêng kết quả quan sát. |
| Migration và phục hồi | DBA, release owner | Backup nhất quán PostgreSQL/eKYC staging, bản sao phục hồi cô lập, hợp đồng image ↔ schema và phiên bản client để diễn tập rollback. |

## Bắt buộc trước production

| Mục | Quyết định hoặc bằng chứng cần chốt |
|---|---|
| F-018 / OBS-12 | Xác định thời hạn lưu CCCD, GPLX, ảnh eKYC, điều kiện tái sử dụng danh tính và quy trình xử lý tài khoản còn số dư. Cần chủ dữ liệu và chủ sản phẩm ký duyệt. |
| OBS-13 | Chốt phạm vi lưu giữ, ẩn danh và audit truy cập chat, ticket, attachment. Cơ chế xóa asset dùng chung phụ thuộc F-045. |
| OBS-01 | Chốt fallback `WALLET → CASH` khi thiếu tiền, cách người dùng được thông báo và cách đối soát thanh toán đã ghi sổ. |
| OBS-11 | Chốt quyền đăng nhập và API của tài xế `BLOCKED`/`SUSPENDED`, kiểm chứng hiệu lực đối với phiên đang mở. |
| F-030 | Chốt số giờ/ngày tối đa đặt trước và hành vi ở biên. Vòng đời voucher theo giờ đón đã có kiểm thử. |
| Cổng dữ liệu | Staging đạt, đối soát V44 và các khoản rút đã chuyển ngoài hệ thống, tiền kiểm V43 có 0 nhóm trùng (hoặc đã xử lý bằng quy trình duyệt), xác nhận backup phục hồi được và kiểm tra sau V46. |
| Cổng triển khai | Mã nguồn, SHA/image và kế hoạch phát hành client cố định. Runtime cấu hình đã được chứng minh, chứng cứ rollback và chỉ số giám sát đã được duyệt, người trực được chỉ định. |

## Có thể hoãn với kiểm soát rủi ro

| Mục | Điều kiện hoãn |
|---|---|
| F-045 | Giữ tài sản dùng chung. Chặn tác vụ dọn chỉ dựa trên một chủ thể tham chiếu. Thiết kế inventory đầy đủ giữa avatar, ảnh xe và bằng chứng hỗ trợ theo OBS-13 trước khi tự động xóa. |
| F-051 | Giữ kiểu và giá trị lịch sử. Ghi nhận UTC cho bốn SQL default mới của V44, đối chiếu timezone từng nguồn/phiên ghi trước mọi backfill hoặc chuyển kiểu. |
| OBS-14 | Có thể giữ cách tính lại hiện tại nếu chủ sản phẩm xác nhận và UI/thông tin hỗ trợ giải thích việc giá có thể đổi. Kiểm chứng giá dùng khi đặt được lưu V46. Nếu yêu cầu giá bảo đảm, chốt tính năng trước khi mở release cho trường hợp đó. |

## Bằng chứng cấu hình cần gửi

Gửi tham chiếu các secret đã cấu hình, project, vai trò, hostname, CIDR và kết quả kiểm tra đã che dữ liệu. Dùng [checklist staging](STAGING-READINESS-CHECKLIST.md) và [tiền kiểm production](PRODUCTION-PREFLIGHT.md) để kiểm chứng tại môi trường được cấp quyền.
