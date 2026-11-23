# Báo cáo sửa lỗi và đánh giá phát hành backend PtitGo

Ngày 23/11/2026 · `integration/backend-release` · baseline `5d316306e7fe07514e295b6b5fcb5ff9725c257a`

## Kết quả theo finding

Kết quả dưới đây bao gồm bằng chứng mới của đợt sửa này và 28 finding đã được xác minh ở đợt trước. Xem [danh mục finding](FINDINGS.md) và [checkpoint](AUTONOMOUS-FIX-CHECKPOINT.md) để đối chiếu. Không có thao tác commit, push, deploy hoặc kết nối production.

| Finding | Trạng thái | Thay đổi và bằng chứng |
|---|---|---|
| F-003 | VERIFIED_FIXED | Đồng bộ accept/reject/release qua khóa hàng ride PostgreSQL và đối chiếu offer token trong Lua. Kiểm thử cạnh tranh với hai giao dịch, Redis 7 và replay idempotency. Kiểm thử bổ sung xác nhận phục hồi khi Redis đã giữ lượt nhận và PostgreSQL rollback, với token cũ và retry. |
| F-007 | VERIFIED_FIXED | Kiểm tra tên ngân hàng và số tài khoản dưới khóa tài xế, lưu snapshot khi tạo yêu cầu và dùng snapshot khi duyệt hoặc tìm kiếm. Kiểm thử thay đổi thông tin ngân hàng, xử lý yêu cầu cũ, chống rút vượt số dư và hoàn tiền đúng một lần. |
| F-011 | VERIFIED_FIXED | Giới hạn truy vấn nhận diện reward theo milestone vào voucher gốc không gắn campaign. PostgreSQL xác nhận nhận campaign trước vẫn nhận đúng một welcome reward. |
| F-030 | PRODUCT_DECISION_REQUIRED | Voucher phải còn hạn vào giờ đón khi báo giá và khi reserve. Kiểm thử biên thời điểm trên PostgreSQL. Cần chốt giới hạn thời gian đặt trước. |
| F-031 | VERIFIED_FIXED | Scheduler kiểm tra `ACTIVE` và `deletedAt` dưới khóa khách trước khi kích hoạt. Kiểm thử khách bị khóa/xóa, lần retry và thời hạn chuyến hẹn. Trường hợp chuyển sang INACTIVE sau START_SEARCH vẫn theo chính sách hiện hành. |
| F-046 | VERIFIED_FIXED | Danh sách review khách/tài xế lọc comment đã ẩn, admin vẫn quản lý bản ghi. V28 giữ nguyên chính sách đóng góp điểm vào aggregate. |
| F-008 | VERIFIED_FIXED | Ví tài xế cũ giới hạn giao dịch theo `wallet_type = 'CASH'`, đồng nhất với số dư xuất ra. PostgreSQL kiểm tra cả giao dịch ví khác loại trên cùng tài xế. |
| F-022 | VERIFIED_FIXED | Các lỗi workflow và `AppException` dùng chung envelope. Giữ `errorCode`, bổ sung `error.code` và thông tin lỗi cho RN. |
| F-026 | BLOCKED | Hai profile production cấu hình native forwarding với `CF-Connecting-IP` và danh sách hop tin cậy. Mặc định không nhận IP từ proxy; cần cấu hình `APP_TRUSTED_EDGE_PROXIES` theo ingress thực tế và kiểm tra phân bucket ở staging. |
| F-028 | VERIFIED_FIXED | Luồng tạo và trả lời ticket dùng quy tắc xác thực asset Cloudinary của hệ thống, giữ API URL hiện có. |
| F-032 | VERIFIED_FIXED | Bộ lọc ngày rating dùng mốc đầu ngày tại `Asia/Ho_Chi_Minh` kiểu có offset, cận trên loại trừ ngày kế tiếp. Có RED và GREEN trên PostgreSQL với phiên UTC. |
| F-034 | VERIFIED_FIXED | Đồng gộp cập nhật GPS gần nhau trong cửa sổ 1 giây qua Lua. Kiểm thử Redis 7 giữ dữ liệu mới nhất, loại mẫu cũ và bảo toàn TTL. |
| F-036 | VERIFIED_FIXED | Admin có `GET /api/admin/rides/{id}/chat/conversations` và tham số `driverId` tùy chọn trên API chat để mở thread cũ. Cuộc hội thoại mặc định giữ hành vi hiện tại. |
| F-039 | VERIFIED_FIXED | Tải ảnh ticket legacy theo lô và dùng truy vấn đếm/tóm tắt trong luồng liệt kê. |
| F-040 | VERIFIED_FIXED | API trả tối đa 100 tin nhắn mỗi lượt, giữ mảng và thứ tự thời gian. Bổ sung cursor và trường `hasOlderMessages` trong chi tiết ticket; RN/admin có điều hướng tải tin cũ. |
| F-041 | VERIFIED_FIXED | `clientMessageId` tùy chọn và unique index theo ticket, vai trò, người gửi. Retry trả lại tin nhắn đã lưu và giữ nguyên tác dụng phụ của lần đầu. |
| F-042 | VERIFIED_FIXED | Tính estimate và tạo chuyến dùng chung kiểm tra vùng phục vụ. Test xác nhận mã `OUT_OF_SERVICE_AREA`. |
| F-043 | VERIFIED_FIXED | Lưu pricing rule và fare breakdown cho báo giá được dùng khi tạo chuyến, ghi lịch sử sửa rule. Giá và lịch sử các ride cũ được giữ; quyết định khóa quote vẫn thuộc OBS-14. |
| F-045 | BLOCKED | Ảnh xe, avatar và bằng chứng hỗ trợ có thể dùng cùng Cloudinary asset. Cần theo dõi sở hữu/tham chiếu tài sản và quyết định retention trước khi tự động xóa. Giữ ảnh và tham chiếu hiện có. |
| F-049 | VERIFIED_FIXED | Bỏ `rideId` khỏi thông báo rating ẩn danh mới và che trường này khi đọc lịch sử cũ. |
| F-050 | VERIFIED_FIXED | Hai profile production dùng danh sách origin cụ thể cho HTTP và WS. WebSocket handshake không yêu cầu cookie; STOMP tiếp tục xác thực bằng token. |
| F-051 | BLOCKED | V44 chuẩn hóa bốn SQL default sang UTC cho ghi mới. Cần xác minh múi giờ phiên ghi theo từng giai đoạn trước khi chuyển kiểu thời gian lịch sử. |

F-047 là phát thông báo trước transaction commit. F-048 là lost update của `average_rating` khi đánh giá đồng thời. Mapping thống nhất giữa registry, báo cáo sprint và test hồi quy.

## Migration và dữ liệu

| Migration | Nội dung | Kiểm tra |
|---|---|---|
| V43 | Unique index CCCD/GPLX theo trạng thái workflow | Suite an toàn V43 trên dữ liệu giả lập. Số trùng trong production chưa xác minh. |
| V44 | Snapshot ngân hàng nullable cho dữ liệu cũ, ràng buộc đủ cặp trường, bốn default UTC | Nâng V43 → V44 trong PostgreSQL 17 disposable bảo toàn số tiền và timestamp cũ, từ chối snapshot thiếu trường. |
| V45 | Idempotency và chỉ mục phân trang tin nhắn hỗ trợ | Nâng cấp giữ body, thời điểm và metadata mới `NULL` trên tin cũ. |
| V46 | Snapshot fare, khóa sửa snapshot, lịch sử sửa pricing rule | Nâng V45 → V46 giữ fare cũ và snapshot `NULL`. Snapshot mới lưu đủ thành phần và bất biến. |

### Tiền kiểm trùng CCCD/GPLX cho V43

Truy vấn chỉ xuất số lượng nhóm trùng và số dòng thuộc các nhóm đó. Đã kiểm tra các bộ lọc và quy tắc chuẩn hóa trên PostgreSQL 17 disposable. Đội vận hành cần chạy trên schema đích bằng kết nối chỉ đọc đã được cấp quyền và xác nhận lại sau khi dừng luồng ghi liên quan trước migration. Nếu một trong hai số nhóm khác 0, cần đối soát và xử lý dữ liệu theo quy trình nghiệp vụ trước khi áp dụng V43.

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
WITH cccd AS (
    SELECT count(*) AS affected_rows
    FROM driver_verification_applications
    WHERE locked_cccd_id IS NOT NULL
      AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO')
    GROUP BY locked_cccd_id
    HAVING count(*) > 1
), gplx AS (
    SELECT count(*) AS affected_rows
    FROM driver_verification_applications
    WHERE verified_gplx_number IS NOT NULL AND TRIM(verified_gplx_number) <> ''
      AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO')
    GROUP BY UPPER(TRIM(verified_gplx_number))
    HAVING count(*) > 1
)
SELECT 'CCCD' AS document_type, count(*) AS duplicate_groups, coalesce(sum(affected_rows), 0) AS affected_rows FROM cccd
UNION ALL
SELECT 'GPLX', count(*), coalesce(sum(affected_rows), 0) FROM gplx;
ROLLBACK;
```

## Tệp nguồn thay đổi

- Dispatch, lịch và giá. `OfferReleaseService.kt`, `RideAcceptanceService.kt`, `DispatchScripts.kt`, `ScheduledRideScheduler.kt`, `RideCreationValidator.kt`, `RideEstimateService.kt`, `RideService.kt`, `RideFactory.kt`, `RideStateMachine.kt`, `Ride.kt`, `PricingRuleService.kt`, `VoucherService.kt` và các tệp cấu hình/entity liên quan.
- Tài chính và voucher. `WithdrawalService.kt`, `Wallet.kt`, `AdminWithdrawalSpecifications.kt`, `PaymentService.kt`, `VoucherGrantService.kt`, V44.
- Tracking. `DriverLocationIngestService.kt`, `LocationService.kt`, `RedisKeys.kt` và cấu hình liên quan.
- Hỗ trợ. `SupportService.kt`, `TicketController.kt`, `TicketDtos.kt`, `TicketEntities.kt`, `TicketRepositories.kt`, `TicketService.kt`, `CloudinaryAssetService.kt`, V45.
- Hợp đồng và quyền riêng tư. `DriverVerificationWorkflowController.kt`, `AdminRatingService.kt`, `RideRatingService.kt`, `RatingReceivedNotificationPayload.kt`, `NotificationHistoryService.kt`, `ChatService.kt`, `ConversationRepository.kt`, `AdminRideController.kt`, `CorsProperties.kt`, `FirebaseSecurityConfig.kt`, `NotificationWebSocketConfig.kt`, `application-prod.properties` và `application-production.properties`.
- Client. RN cập nhật API và màn hình ticket customer/driver cùng thành phần chung. goAdmin cập nhật API, type và màn hình ticket. eKYC service được đối chiếu tĩnh.

## Quyết định cần xác nhận

| Mục | Nội dung cần chốt |
|---|---|
| OBS-01 | Quy tắc khi thiếu số dư `WALLET` lúc hoàn thành chuyến và thông báo cho khách/tài xế. |
| OBS-11 | Quyền đăng nhập và quyền API của tài xế `BLOCKED`/`SUSPENDED`. |
| OBS-12 / F-018 | Quyền xóa tài khoản còn số dư, thời gian lưu CCCD/GPLX/ảnh eKYC và chính sách tái sử dụng danh tính. |
| OBS-13 / F-045 | Thời gian lưu chat, ticket và ảnh bằng chứng, quy trình audit khi admin xem, quyền xóa tài sản nhiều tham chiếu. |
| OBS-14 / F-043 | Báo giá có hiệu lực đến khi nào và khi nào được tính lại. Snapshot V46 ghi dữ liệu được dùng khi tạo chuyến. |
| F-030 | Số giờ/ngày tối đa khách được phép đặt trước. Các slot trong RN chỉ phản ánh UI hiện tại. |

## Đối chiếu repository

| Repository | Trạng thái | Bằng chứng |
|---|---|---|
| `F:/datxe_rn` (`fe1f5d8`) customer và driver | PASS trong phạm vi kiểm tra | `npm run check` qua 320 test, typecheck và lint. Đối chiếu route, voucher/schedule, GPS REST/STOMP, ví và rating notification. Phân trang ticket và retry key cần phát hành client mới; client cũ nhận tối đa 100 tin gần nhất. Chưa kiểm tra trên thiết bị hoặc staging. |
| `F:/goAdmin` (`a6915a6`) | PASS trong phạm vi kiểm tra | Typecheck, lint và lượt chạy lại 12/12 test trên hai suite với một worker. Lượt trước đạt 10/12, có voucher timeout và lỗi tìm ticket trong lượt đó. API lịch sử chat cũ dùng thread mặc định; selector mới có sẵn qua API. Giao diện rút tiền dùng các trường nullable hiện tại. |
| `F:/ekyc-service` (`a8466e1`) | STATIC VERIFIED | Đối chiếu route `/ekyc/face/evaluate`, dữ liệu request/response và cơ chế API key. Thay đổi F-022 nằm ở lớp trả lỗi từ backend đến client. Provider runtime và smoke production chưa chạy. |

## Kiểm chứng tích hợp và cổng phát hành

### Build và bộ test backend

`gradlew.bat --offline build` lần cuối trả về `BUILD SUCCESSFUL` sau 2 phút 33 giây. Đã biên dịch Kotlin, tạo boot JAR và chạy 82 suite. Kết quả XML gồm **324 test, 321 PASS, 0 FAIL, 0 ERROR, 3 SKIPPED và 0 BLOCKED ở cấp suite**. Ba test skip theo cấu hình là một smoke Modal và hai smoke Goong. Lượt full đầu tiên có 1 FAIL vì test nâng V42 → V43 chạy cả các migration mới. Sau khi giới hạn target V43 ở test chuyên biệt, nhóm V43 và test nâng cấp đến V46 đã đạt, rồi full build được chạy lại và đạt.

| Kiểm tra | Bằng chứng |
|---|---|
| Authentication, authorization và STOMP | `AuthRevocationLifecycleTests`, `StompAuthInterceptorSecurityTests`, `P2GroupASecurityPrivacyTests`, `FrontendOriginConfigTests`. Kiểm tra hành vi bằng mock/Fake ở biên provider. |
| Xóa tài khoản, khóa danh tính và HMAC | `AccountDeletionConcurrencyPostgresTests`, `AccountLifecycleBatch2ATests`, `EkycIdentityLockAndKeyLifecycleTests`, `V43IdentityLockSafetyGatePostgresTests`. |
| Chuyến xe, lịch, voucher, thanh toán, rút tiền và rating | `DispatchPersistenceTests`, `ScheduledRideBookingPostgresTests`, `VoucherBudgetIntegrityPostgresTests`, `P2GroupBFinancialIntegrityPostgresTests`, `WalletPostgresTests`, `P2GroupCTransactionsTests`. |
| Support, chat và upload | `SupportPersistenceTests`, `TicketServiceTests`, `P2GroupDSupportTests`, `ChatServiceTests` và các suite upload. |
| PostgreSQL và Flyway | PostgreSQL 17.11 disposable. Schema mới có 46 migration thành công, 0 thất bại. Fresh V1 → V46, nâng cấp V33 → V46 với dữ liệu tổng hợp, `Flyway.validate()`, rollback và concurrency theo từng nghiệp vụ. |
| Redis, Lua và phục hồi | Redis 7.4.12 disposable. Test giữ offer, đối soát sau rollback, dọn khóa và cập nhật GPS. Bộ fixture Lua độc lập đạt 11/11. |
| Cấu hình production | Hai profile qua kiểm tra cấu hình. Compose render thành công với `.env.example` và biến giả lập, gồm profile, non-root, mount capture và Firebase chỉ đọc. Native forwarding của phiên bản Spring Boot trong repo dùng `server.forward-headers-strategy` và tin hop theo cấu hình Tomcat. [Tomcat Remote IP Valve](https://tomcat.apache.org/tomcat-11.0-doc/config/valve.html#Remote_IP_Valve) |

Các tập kiểm thử chủ đích và lượt RED được ghi tại [TEST-INVENTORY.md](TEST-INVENTORY.md). Các kiểm tra quyền riêng tư và tương thích tập trung xác nhận hành vi phòng thủ sau sửa. Kết quả runtime chỉ áp dụng cho môi trường disposable và provider giả lập.

### READY FOR SOURCE MERGE

**ĐẠT cho backend.** 46/51 finding đã xác minh, gồm 18 bản sửa mới. Còn 0 `OPEN`, 3 `BLOCKED` và 2 `PRODUCT_DECISION_REQUIRED`. Full build, Flyway fresh/upgrade, Lua, test cạnh tranh chính và các kiểm tra client theo phạm vi đều đạt. Tệp nguồn, test và migration nằm trong worktree hiện tại để review và merge theo quy trình của nhóm.

### READY FOR STAGING

**CHƯA ĐẠT.** Cần xác nhận ingress thực tế và biến `APP_TRUSTED_EDGE_PROXIES`, kiểm tra CORS/WS với origin của app phát hành, cấu hình lưu trữ eKYC và chạy smoke phối hợp backend với RN/goAdmin có phân trang ticket. Cần diễn tập nâng cấp và đối soát khoản rút cũ trên dữ liệu staging dùng được cho kiểm thử.

### READY FOR PRODUCTION

**CHƯA ĐẠT.** Cần gate staging đạt, có kết quả tiền kiểm V43 trên schema đích, chốt các quyết định sản phẩm và xác minh dữ liệu rút tiền lịch sử, vận hành ingress và triển khai phối hợp client. Chưa xác minh số trùng CCCD/GPLX của production hoặc provider live.

### Rủi ro triển khai

- Trước khi nâng V44, đối soát các khoản rút cũ vẫn `PENDING`. Với yêu cầu thiếu snapshot, duyệt bị chặn. Chỉ từ chối và hoàn tiền sau khi xác nhận chưa chuyển thủ công.
- Kiểm tra profile `prod` hoặc `production`, proxy tin cậy có IP/CIDR hẹp, header `CF-Connecting-IP` được proxy quản lý và origin thật của HTTP/WebSocket. Xác minh non-root, quyền thư mục capture, khóa mã hóa và vòng đời HMAC bằng cấu hình staging thực tế.
- Các ghi chú audit trước đây là baseline. Kiểm thử cục bộ chạy trên container disposable; diễn tập cutover, rollback staging, dữ liệu production và các provider live nằm ngoài bằng chứng của báo cáo.
