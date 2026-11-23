# Báo cáo chuẩn bị phát hành PtitGo

Ngày 23/11/2026. Đối chiếu với [báo cáo sửa lỗi](BACKEND-AUTONOMOUS-COMPLETION-REPORT.md), [checkpoint](AUTONOMOUS-FIX-CHECKPOINT.md) và [danh mục finding](FINDINGS.md). Backend thiếu file `AGENTS.md` ở root. Đã dùng hướng dẫn repository hiện có của RN và goAdmin.

| Cổng | Kết luận | Bằng chứng và điều kiện |
|---|---|---|
| Source merge | PASS | Diff, quét candidate, 80 test hồi quy backend, kiểm tra client và contract đều đạt trong phạm vi source. |
| Staging | NOT READY | Chưa được bàn giao URL, quyền, môi trường riêng, tài khoản thử và cấu hình ingress. Đã chuẩn bị script, checklist và ma trận bằng chứng. |
| Production | NOT READY | Cần staging đạt, số liệu preflight trên đích được duyệt, công việc đối soát/rollback và các quyết định tại [release blockers](RELEASE-BLOCKERS.md). |

Trạng thái finding giữ ở **51 tổng, 46 VERIFIED_FIXED, 0 OPEN, 3 BLOCKED, 2 PRODUCT_DECISION_REQUIRED**. Công việc đợt này thêm tài liệu và công cụ chạy kiểm tra. Các tệp ứng dụng của bốn repository giữ trạng thái trước đợt chuẩn bị.

## Source integration

| Repository / HEAD | Kiểm tra Git |
|---|---|
| Backend `5d31630` | Nhánh `integration/backend-release` đi trước upstream đã cấu hình 14 commit. Xem delta worktree theo nhóm auth/eKYC, dispatch/ride, payment/voucher, hỗ trợ, rating/chat, cấu hình và test. Đối chiếu thêm delta upstream và 14 patch commit. |
| RN `fe1f5d8` | Upstream đồng bộ. Xem tám tệp tracked về ticket/API chung/UI và test ở cả customer/driver. 716 file untracked thuộc tooling, ảnh chụp hoặc metadata cục bộ, nằm ngoài candidate. |
| goAdmin `a6915a6` | Upstream đồng bộ. Xem bốn tệp tracked trong màn hình, type, API ticket và test. |
| eKYC `a8466e1` | `main` và upstream đồng bộ, worktree sạch. Kiểm tra hợp đồng route `/ekyc/face/evaluate`, `X-API-Key`, `quality_only`, `images` và lỗi trả về. |

### Phân loại 96 mục backend

| Loại | Tracked đã sửa | Mục untracked trong status rút gọn |
|---|---:|---:|
| Kotlin ứng dụng | 57 | 1 |
| Cấu hình profile | 2 | 0 |
| Migration | 0 | 4 |
| Test Kotlin và Lua fixture | 16 | 13 |
| Thư mục tài liệu, tooling và lock | 0 | 3 |
| **Tổng** | **75** | **21** |

13 mục test untracked gồm 15 file khi mở rộng thư mục. Tổng cộng 20 file mới trong `src` đã được kiểm tra loại file và whitespace. Tài liệu cũ và mới cùng nằm dưới một mục `docs/audits/` trong status rút gọn. Candidate chỉ lấy file ứng dụng, migration, test và tài liệu đã review. Gitleaks 8.30.1 ở chế độ che giá trị báo 0 kết quả trên delta tracked của bốn repository, patch 14 commit backend và 20 file thêm trong `src`. `git diff --check` đạt trên worktree và delta upstream của bốn repo. Git có cảnh báo chuyển đổi LF/CRLF; các lệnh kiểm tra trả exit 0. `.env`, `secrets/`, dữ liệu capture, `build/`, cache, tooling cá nhân và ảnh chụp vẫn ở ngoài phạm vi candidate.

### Hợp đồng và thứ tự client

- API ticket giữ mảng và thứ tự tăng dần trong từng trang. Trường `hasOlderMessages` trên detail dẫn tới tải lịch sử bằng cursor. RN và goAdmin đối chiếu query, nối trang không làm mất tin mới và gửi `clientMessageId` để retry. Khóa dedup chỉ áp dụng khi backend mới xử lý request có key. Client cũ với backend mới thấy 100 tin gần nhất. Client mới với backend cũ vẫn đọc được dữ liệu cũ, nhưng không suy ra được lịch sử cũ còn hay không từ server và không có dedup backend.
- Trường tài khoản ngân hàng admin vẫn nullable, lấy từ snapshot V44 khi có. Tài xế giữ request/response hiện hành. Khóa duyệt rút cũ thiếu snapshot là điểm thay đổi hành vi cần điều phối vận hành.
- Chi tiết chat admin cũ tiếp tục dùng thread mặc định; selector `driverId` là tùy chọn. Hợp đồng lỗi eKYC giữ `errorCode` và thêm `error.code`. Đối chiếu route, API key và payload giữa backend và eKYC test fixture.
- Không thay đổi enum của lịch, booking, offer, vị trí REST/STOMP hoặc hợp đồng CRUD bảng giá. V46 bổ sung dữ liệu lưu nội bộ. Kiểm tra ngưỡng GPS, nhận chuyến, voucher, lỗi vùng và policy giá trong staging cùng client đã ghim phiên bản.

## Cấu hình phát hành

Render Compose bằng env file tổng hợp và kiểm tra cú pháp script deploy bằng `bash -n` đều đạt. Model render có profile `production`, UID:GID giả lập non-root, mount Firebase chỉ đọc, volume capture không tự tạo và token tunnel tách khỏi backend. Cột trạng thái runtime bên dưới chỉ dành cho hạ tầng thực tế.

| Phạm vi | Đối chiếu trong repository | Runtime và dữ liệu còn cần |
|---|---|---|
| Spring profiles | `prod` và `production` tắt OpenAPI/Swagger, ẩn chi tiết health, bắt buộc protected storage. Mẫu Compose mặc định `production`. | NOT VERIFIED. Xác nhận `SPRING_PROFILES_ACTIVE` hiệu lực và `WALLET_MOCK_TOPUP_ENABLED=false`. Loại `demo`/`dev`/`test` khỏi profile của đích phát hành. |
| Firebase / FCM | `FirebaseConfig` lấy service account từ resource, Compose mount Firebase chỉ đọc. HTTP và STOMP kiểm token/revocation trong targeted tests bằng fake/mock. | NOT VERIFIED. Project ID riêng, Firebase service-account secret reference, `FIREBASE_CREDENTIALS_LOCATION` / `DATXE_FIREBASE_FILE`, quyền Firebase Auth/FCM và token test theo vai trò. |
| HTTP CORS / WS | HTTP và WebSocket dùng cùng danh sách origin chính xác, bỏ wildcard; source cũng có origin goAdmin đã khai báo. WebSocket không dựa vào cookie để xác thực STOMP. | NOT VERIFIED. `APP_CORS_ALLOWED_ORIGINS`, xác nhận cả origin tĩnh đang được phép phù hợp môi trường, test trình duyệt/goAdmin và RN qua edge. |
| Trusted ingress / F-026 | Hai profile chọn Tomcat native, `CF-Connecting-IP`, `APP_TRUSTED_EDGE_PROXIES` mặc định không khớp peer nào. Compose mẫu publish `8080:8080` với `host_ip` không giới hạn. | NOT VERIFIED. Chứng cứ bind/firewall/các route trực tiếp, IP/CIDR của peer Tomcat thực thấy, nguồn header do edge kiểm soát, `APP_TRUSTED_EDGE_PROXIES` và quota theo request hợp lệ từ hai máy thử. Cấu hình tunnel và `CLOUDFLARE_TOKEN` do chủ hạ tầng cấp qua secret manager. [Cloudflare origin](https://developers.cloudflare.com/fundamentals/security/protect-your-origin-server/) · [Tomcat Remote IP Valve](https://tomcat.apache.org/tomcat-11.0-doc/config/valve.html#Remote_IP_Valve) |
| STOMP | CONNECT xác thực token, kiểm lại theo phiên và theo định kỳ khi có hoạt động. SUBSCRIBE/SEND/outbound có kiểm quyền. | NOT VERIFIED. WS upgrade, timeout/heartbeat qua tunnel, Firebase project và `APP_STOMP_REVALIDATION_INTERVAL_MS` hiệu lực, bằng chứng phiên thử bị thu hồi hoặc đổi trạng thái. |
| eKYC storage và provider | Chế độ protected bắt buộc mount và đọc khóa, V43 khóa định danh. Hợp đồng backend → eKYC đã đối chiếu và chạy test fixture. | NOT VERIFIED. `DATXE_VERIFICATION_HOST_DIR`, `DATXE_VERIFICATION_STORAGE_DIR`, `DATXE_DEPLOY_UID`, `DATXE_DEPLOY_GID`, tham chiếu `DATXE_VERIFICATION_ENCRYPTION_KEY` và `DATXE_VERIFICATION_LOOKUP_HMAC_KEY`, nếu xoay khóa thì kiểm `app.verification.previous-encryption-keys` và `app.verification.previous-lookup-hmac-keys`. Xác nhận `DATXE_EKYC_ENABLED`, `DATXE_EKYC_ORIGIN`, `DATXE_EKYC_API_KEY`, `DATXE_EKYC_MODAL_KEY`, `DATXE_EKYC_MODAL_SECRET`, `DATXE_EKYC_FACE_COMPARISON_ENABLED` và kiểm nhóm `DATXE_VERIFICATION_SYNC_ENABLED`, `DATXE_VERIFICATION_SYNC_ORIGIN`, `DATXE_VERIFICATION_SYNC_KEY` nếu bật sync. |
| PostgreSQL / Redis | JPA validate, Flyway tự migrate, 10 Hikari connections/instance mặc định, `lock_timeout=3s`, JDBC UTC. Compose có healthcheck DB/Redis. Dispatch/recovery đã chạy với Redis 7 và PG 17 disposable. | NOT VERIFIED. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, quyền role và schema Flyway, TLS/routing/backup do DBA xác nhận, `REDIS_URL`, DB index/namespace, ACL/TLS và durability. Dùng ACL riêng cho stage để tránh chia khóa giữa hai môi trường. |
| Scheduler / worker | Scheduler pool 4. Có poller outbox/cleanup, recovery chuyến, timeout offer và kích hoạt chuyến hẹn. | NOT VERIFIED. `OUTBOX_POLL_INTERVAL_MS`, `RIDE_DISPATCH_RECOVERY_INTERVAL_MS`, `SCHEDULED_RIDE_POLL_INTERVAL_MS`, `SCHEDULED_RIDE_DISPATCH_LEAD_TIME`, `SCHEDULED_RIDE_EXPIRE_AFTER`, `SCHEDULED_RIDE_IDLE_RESCAN_DELAY`, `MATCHING_OFFER_TTL`, `MATCHING_SEARCH_TIMEOUT`, replica count, queue lag/retry và theo dõi recovery. |
| Health / readiness / observability | Actuator health/info và probe; Docker kiểm `/actuator/health`. Kết quả probe cần được ghép với kiểm tra DB, Redis, broker và provider. | NOT VERIFIED. Điều kiện alert và route health/readiness, nơi xem lỗi Flyway, queue age, DB pool, Redis, FCM, STOMP, eKYC và request ID đã che dữ liệu. Chỉ số/threshold và người trực vận hành do chủ hệ thống chỉ định. |

## Kiểm thử đã chạy

| Hạng mục | Kết quả trong đợt này |
|---|---|
| Backend compile/build + targeted | `gradlew.bat --offline bootJar test` với 14 bộ lọc đạt. **80/80**, 0 fail/skip trên PostgreSQL 17.11 và Redis 7.4.12 disposable. Gồm auth/revocation/STOMP/CORS, eKYC khóa danh tính, V43, Flyway upgrade V33 → V46, dispatch recovery, wallet, voucher, lịch và support. `bootJar` xác nhận artifact up-to-date. |
| Full backend | Baseline đã có 321 PASS, 0 FAIL, 3 SKIPPED. Trong đợt này không đổi source ứng dụng hoặc test ứng dụng. Kết quả mới là targeted 80 case nêu trên. |
| RN | `npm run check` đạt architecture, typecheck, lint và **320/320** test. |
| goAdmin | `pnpm lint`, `pnpm typecheck`, `pnpm build` và toàn bộ `pnpm test` đạt **107/107 trên 13 file**. Vite cảnh báo kích thước chunk UI dùng chung. Build output kiểm tra ở thư mục tạm ngoài Git. |
| eKYC | Pytest local `tests/api/test_face.py` đạt **3/3**. Fixture tổng hợp dùng FastAPI TestClient và worker giả. |
| SQL preflight | PostgreSQL 17.11 disposable, role SELECT-only. Kiểm chứng schema V42 với nhóm trùng tổng hợp, V43 dừng khi trùng, V46 với schema và ràng buộc mới, bốn default UTC, kiểm tra schema sai. [Tiền kiểm](PRODUCTION-PREFLIGHT.md) |
| Smoke HTTP/STOMP | Fixture loopback đạt tám kiểm HTTP, một CORS, hai handshake STOMP; xác nhận nhánh HealthOnly, redaction 401 và dừng ở redirect 302. [Checklist và lệnh chạy](STAGING-READINESS-CHECKLIST.md) |

## Tài liệu và bước tiếp theo

- [Checklist staging](STAGING-READINESS-CHECKLIST.md) · [Script smoke staging](staging-smoke.ps1)
- [Tiền kiểm production](PRODUCTION-PREFLIGHT.md) · [SQL chỉ đọc](production-preflight.sql)
- [Điều kiện còn thiếu và quyết định nghiệp vụ](RELEASE-BLOCKERS.md)
- [Bàn giao, thứ tự phát hành và rollback](RELEASE-HANDOFF.md)

Bước tiếp theo ở phía Release Owner là bàn giao staging được cấp quyền, cấu hình hiệu lực đã che giá trị và chủ sở hữu từng hạng mục trong checklist. Dữ liệu tiền kiểm trên đích, backup/restore thực tế, tích hợp device/push, luồng ghi sandbox và đường ingress thực vẫn ở trạng thái NOT VERIFIED. Không có commit, push, deploy hay kết nối production trong đợt chuẩn bị.
