# Checklist xác minh staging PtitGo

**Staging chưa được xác minh.** Các bước dưới đây cần một môi trường staging đã bàn giao và tài khoản tổng hợp theo vai trò. Script smoke đã chạy với fixture HTTP/STOMP trên loopback. Kết quả fixture xác nhận cách script xử lý phản hồi và trả mã thoát. Xem bằng chứng hồi quy backend riêng trong [báo cáo hoàn tất backend](BACKEND-AUTONOMOUS-COMPLETION-REPORT.md).

## Chuẩn bị môi trường

- [ ] Ghi SHA và image digest của backend, eKYC service, admin web, cùng build ID của hai ứng dụng RN. Ghi SHA và cấu hình của bản rollback đã biết hoạt động. Chốt thứ tự phát hành backend và client có hỗ trợ phân trang ticket. Kiểm tra khả năng quay lại client cũ trong khoảng chuyển tiếp.
- [ ] Dùng PostgreSQL 17, Redis 7 và volume eKYC riêng trên staging. Dùng project Firebase, thiết bị push, định tuyến Goong, storage và eKYC provider cô lập. Chỉ seed dữ liệu, CCCD/GPLX, ảnh và tài khoản ngân hàng tổng hợp. Ghi rõ provider nào là sandbox hoặc test double trong bằng chứng.
- [ ] Kiểm tra `SPRING_PROFILES_ACTIVE=production` hoặc `prod` cho staging dùng chung. Dùng secret manager để xác nhận `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_URL`, `FIREBASE_CREDENTIALS_LOCATION`, `CLOUDINARY_URL` và đường dẫn storage. Kiểm tra mount bền `DATXE_VERIFICATION_HOST_DIR`, mount trong container `DATXE_VERIFICATION_STORAGE_DIR` và quyền đọc/ghi của tài khoản chạy dịch vụ. Xác minh `DATXE_VERIFICATION_ENCRYPTION_KEY` được cấp đúng phiên bản và ứng dụng có thể đọc bản sao capture sau khi recreate.
- [ ] Giới hạn truy cập cổng origin backend `8080` qua tunnel. Kiểm tra bind và firewall hiệu lực của máy staging vì cấu hình Compose mặc định publish cổng trên mọi interface của host. Ghi địa chỉ bind, quy tắc firewall và IP/CIDR hẹp của peer thật mà backend quan sát. Chủ ingress xác nhận `APP_TRUSTED_EDGE_PROXIES` chỉ khớp các peer đã bàn giao và ingress quản lý `CF-Connecting-IP`.
- [ ] Đối chiếu `APP_CORS_ALLOWED_ORIGINS` với origin HTTPS thật của admin web. Dùng lưu lượng checkout/login bình thường của các máy thử để đối chiếu bucket rate limit qua số đo đã che IP. Ghi cấu hình hiệu lực của profile, OpenAPI, health detail và mock top-up.
- [ ] Đối chiếu `DATXE_EKYC_ENABLED`, `DATXE_EKYC_ORIGIN`, `DATXE_EKYC_API_KEY`, nhóm `DATXE_EKYC_MODAL_*` và `DATXE_VERIFICATION_SYNC_*` theo topology đã duyệt. Đặt `WALLET_MOCK_TOPUP_ENABLED=false` cho lượt chạy với cấu hình gần production. Dùng seed được duyệt để tạo số dư và credit thử nghiệm.
- [ ] Chạy tiền kiểm V43 chỉ đọc trên database mục tiêu đã được phép trước khi chạy Flyway. Ghi tổng nhóm trùng và tổng dòng liên quan theo từng định danh. Với V43, đối chiếu `locked_cccd_id` và `UPPER(TRIM(verified_gplx_number))` trong `APPROVED`, `SUBMITTED`, `UNDER_REVIEW`, `NEEDS_MORE_INFO`. Bất kỳ nhóm trùng nào đều dừng gate migration để chủ dữ liệu xử lý. Bằng chứng chỉ ghi số đếm, giữ định danh trong hệ thống được cấp quyền.
- [ ] Trên bản sao staging trước nâng cấp, xác nhận được mốc Flyway hiện hành. Sau khi chạy qua V46, lưu danh sách version, checksum, kết quả `Flyway.validate()` và đối soát số bản ghi trước/sau cho các bảng nghiệp vụ. Kiểm tra snapshot ngân hàng lịch sử còn biểu diễn là chưa biết khi thiếu nguồn và chuyến cũ giữ nguyên giá. Trên instance rỗng riêng, kiểm tra fresh migration V1 đến V46.

## Smoke HTTP và STOMP

Chạy [staging-smoke.ps1](staging-smoke.ps1) bằng PowerShell 7.4+. Sử dụng tên máy chủ staging lấy từ hồ sơ bàn giao và ID token Firebase mới của ba tài khoản tổng hợp. Truyền các giá trị qua môi trường tiến trình hoặc secret manager.

| Biến | Giá trị |
|---|---|
| `PTITGO_STAGING_SMOKE_URL` | Origin API HTTPS của staging, chỉ gồm scheme, host và port tùy chọn. HTTP loopback dành cho fixture cục bộ. |
| `PTITGO_STAGING_SMOKE_HOST` | Tên máy chủ đã xác nhận trong hồ sơ staging. Script yêu cầu khớp khi URL nằm ngoài loopback. |
| `PTITGO_STAGING_SMOKE_CUSTOMER_TOKEN` | ID token Firebase của customer staging. |
| `PTITGO_STAGING_SMOKE_DRIVER_TOKEN` | ID token Firebase của driver staging. |
| `PTITGO_STAGING_SMOKE_ADMIN_TOKEN` | ID token Firebase của admin staging. |
| `PTITGO_STAGING_SMOKE_WEB_ORIGIN` | Origin admin web staging có trong CORS allowlist. Bắt buộc cho bằng chứng CORS của gate. |

```powershell
pwsh -NoProfile -File ./docs/audits/2026-11-23-5d31630/staging-smoke.ps1
```

- [ ] Health và readiness đều trả HTTP 200 với `status=UP`. Dùng `-HealthOnly` khi chỉ cần xác nhận liveness/readiness sau khởi động, rồi chạy lệnh đầy đủ trước khi xác nhận gate.
- [ ] `/api/auth/me` trả đúng vai trò cho từng token. Customer và driver đọc được chuyến đang hoạt động, admin đọc được bảng giá. Gọi từ origin web cấu hình phải nhận chính xác `Access-Control-Allow-Origin` tương ứng.
- [ ] Native WebSocket ở `/ws-location` nhận STOMP `CONNECTED` cho customer và driver. Script gửi `DISCONNECT` rồi đóng socket. Dùng luồng thủ công bên dưới để kiểm tra subscription và nhận event thật.
- [ ] Lưu dòng `PASS` và mã thoát của lượt chạy staging. Ghi `SKIP` cho hạng mục bỏ qua và `BLOCKED` kèm nguyên nhân nếu thiếu token, origin, provider hoặc thiết bị. Script báo lỗi nếu HTTP chuyển hướng, HTTP hoặc JSON sai contract, vai trò sai, CORS thiếu hoặc STOMP handshake lỗi. Giữ báo cáo bằng chứng sạch token, payload hồ sơ và dữ liệu nhạy cảm.

### Kiểm tra đã thực hiện với fixture cục bộ

PowerShell 7.6.9 chạy với server giả lập trên `127.0.0.1`. Lượt đầy đủ đạt 8 phép kiểm HTTP, 1 kiểm CORS và 2 handshake STOMP với phản hồi WebSocket phân mảnh. Lượt `-HealthOnly` đạt 2 phép kiểm và báo `SKIP` cho các mục còn lại. Fixture HTTP 401 xác nhận mã thoát khác 0 và token giả được che. Fixture HTTP 302 xác nhận script dừng tại 302.

## Luồng ghi trên sandbox staging

Chạy từng luồng bằng client bản phát hành và dữ liệu tổng hợp đã seed. Ghi thời điểm UTC, fixture ID nội bộ, kết quả trên UI, trạng thái backend và phép đối soát. Sử dụng tài khoản và nhà cung cấp staging đã được duyệt trước khi thực hiện các thao tác ghi.

| Luồng | Điều kiện đạt |
|---|---|
| Customer, driver và điều phối | Customer tạo chuyến tức thời, thử gửi lại cùng idempotency key và nhận cùng ride. Driver bật nhận chuyến, nhận offer qua WS hoặc đọc pending bằng REST, nhận chuyến và thử lại đúng offer ID. Kiểm tra chặng đến đón, handover, hoàn tất, hủy theo trạng thái và driver được trả về trạng thái phù hợp. Xác nhận ledger của offer khớp trạng thái chuyến. |
| Chuyến đặt lịch | Tạo chuyến chờ bằng customer tổng hợp. Xác nhận kích hoạt trong dispatch lead time và hết hạn theo expiry. Kiểm tra trường hợp tài khoản `INACTIVE` trước kích hoạt, rồi kích hoạt lại khi tài khoản được mở trước cutoff. Ghi rõ chính sách còn thiếu nếu cần xử lý tài khoản chuyển `INACTIVE` sau `START_SEARCH`. |
| Thanh toán sandbox | Hoàn tất hai chuyến riêng dùng `CASH` và `WALLET`. Đối chiếu giá gốc, giảm giá, tiền khách trả, thu nhập và ledger hai vai trò. Kiểm tra retry hoàn tất giữ một bút toán nghiệp vụ. Ghi kết quả hành vi thiếu số dư theo quyết định sản phẩm. Mọi diễn tập dùng số dư sandbox. |
| Voucher và ngân sách | Tạo voucher tổng hợp còn hạn khi đón, đặt chuyến và đo mức hold. Hủy và xác minh hold được trả một lần. Hoàn tất một chuyến khác để xác minh hold chuyển spent một lần. Voucher hết hạn trước giờ đón trả lỗi theo API hiện hành và giữ nguyên usage/budget. |
| Khoản rút | Tạo yêu cầu với tài khoản ngân hàng tổng hợp hợp lệ, đổi hồ sơ ngân hàng sau đó, kiểm tra snapshot hiển thị khi admin duyệt giữ dữ liệu ban đầu. Đối soát số dư, số tiền giữ và bút toán theo approve/reject. Ghi nhận khoản cũ thiếu snapshot để chủ tài chính đối chiếu theo chứng từ. |
| Admin và hỗ trợ | Kiểm tra cấu hình bảng giá và fare snapshot trong báo cáo nội bộ. Admin được phân quyền nhận và trả lời ticket. Lấy trang kế tiếp bằng cursor, mở lịch sử và gửi lại cùng retry key để xác nhận một tin nhắn. Kiểm tra app cũ hiển thị cửa sổ tương thích đã công bố và app mới tải đủ lịch sử khi phân trang. |
| eKYC E2E | Driver tạo hồ sơ, tải ảnh tổng hợp, hoàn tất trích xuất/đối chiếu qua provider staging, gửi review và nhận kết quả xử lý. Kiểm tra projection, quyền đọc capture và khả năng nhận chuyến theo loại xe. Xác minh lỗi provider được ánh xạ thành envelope đã duyệt. Kiểm tra mã hóa lưu trữ, snapshot theo revision và trường hợp xoay khóa HMAC trong sandbox theo cấu hình tương thích. |
| Push và WS | Hai thiết bị test theo vai trò đăng ký nhận push bằng Firebase staging. Nhận offer, thay đổi trạng thái chuyến và thông báo đánh giá theo quyền. Kiểm tra ứng dụng khi foreground, background, mất kết nối và kết nối lại. Đối chiếu sự kiện với trạng thái đã commit và kiểm tra dữ liệu hiển thị được ẩn đúng. |
| Tương thích client | Chạy cả customer RN, driver RN, goAdmin và eKYC service theo các SHA/build ID đã ghi. Kiểm tra HTTP, enum, mã lỗi, bearer và role header, CORS preflight của trình duyệt, các event WS, phân trang ticket và hành vi retry. Ghi rõ phiên bản client cần đồng bộ khi lên môi trường tiếp theo. |

## Sao lưu, phục hồi và rollback

- [ ] Chủ staging ghi phương án rollback, cửa sổ tạm dừng ghi, RPO/RTO và người chịu trách nhiệm trước khi nâng cấp. Lưu image digest, cấu hình ingress, tên Compose project, thông tin migration và phiên bản khóa mã hóa trong hồ sơ release.
- [ ] Tạm dừng ghi và sao lưu PostgreSQL cùng kho capture ở một mốc nhất quán. Dùng archive `pg_dump -Fc` với cấu hình kết nối staging được cấp, sao lưu volume capture đã mã hóa và kiểm tra checksum. Lưu khóa phục hồi trong secret manager riêng. Giữ Redis theo chiến lược đã duyệt. Xác nhận cách khôi phục dispatch, locks và outbox từ PostgreSQL nếu Redis phải khởi tạo lại.
- [ ] Khôi phục archive bằng `pg_restore --exit-on-error` vào PostgreSQL staging mới, cô lập khỏi instance đang phục vụ. Nối bản sao volume và khóa đúng phiên bản. Chạy Flyway validate, đối chiếu số bản ghi và các tổng ledger. Mở capture tổng hợp đã kiểm tra ở đúng revision, chạy lại smoke và thử reconnect/điều phối sau khi khởi động lại.
- [ ] Giả lập lỗi rollout trên bản staging riêng. Dùng image rollback có schema tương thích và chạy health, HTTP, STOMP, sau đó kiểm tra luồng nghiệp vụ đại diện. Nếu bản cũ thiếu tương thích schema, diễn tập phục hồi vào instance riêng hoặc sửa tiến lên theo phương án đã duyệt. Đo thời gian và chênh lệch dữ liệu thực tế, rồi đối chiếu RPO/RTO. [Runbook phát hành và khôi phục](../../release-and-rollback.md)

## Điều kiện chốt gate

- [ ] Mỗi hạng mục bắt buộc có kết quả `PASS` và đường dẫn bằng chứng, hoặc `FAIL`/`BLOCKED` kèm người phụ trách. Bằng chứng staging đi cùng SHA và cấu hình được triển khai.
- [ ] Migration, ingress, tương thích client, eKYC, thanh toán và diễn tập phục hồi đều có người phụ trách xác nhận. Quyết định sản phẩm còn mở được ghi lại trong [báo cáo hoàn tất backend](BACKEND-AUTONOMOUS-COMPLETION-REPORT.md).
- [ ] Chỉ xác nhận `READY FOR STAGING` sau khi đạt các điều kiện staging đã chốt. Gate production dùng phê duyệt, dữ liệu tiền kiểm và bằng chứng vận hành riêng.
