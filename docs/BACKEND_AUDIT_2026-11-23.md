# Tổng hợp kiểm chứng & lỗi backend PtitGo (2026-11-23)

Phạm vi: chỉ backend `D:\DEV\datxebackend`. Frontend chỉ đọc để đối chiếu contract, không sửa.
Không push, không amend commit đã push `5d31630`; mọi commit mới đều là commit local.

---

## 1. Trạng thái cuối

| Mục | Giá trị |
|---|---|
| Base đã push | `5d31630` (`origin/main`) |
| HEAD hiện tại | `12b244d` |
| Số commit local phía trên origin | **7** |
| Lệnh kiểm chứng | `.\gradlew.bat clean check --no-daemon --console=plain` |
| Kết quả | **BUILD SUCCESSFUL** |
| Test không env DB | `suites=97 tests=368 failures=0 errors=0 skipped=69` |
| Test với env DB (`TEST_DB_URL` + `VERIFICATION_SYNC_TEST_DB`) | `suites=97 tests=368 failures=0 errors=0 skipped=3` |
| Working tree | sạch, `git diff --check` sạch |

### Commit local

| Commit | Nội dung |
|---|---|
| `8744850` | Hardening auth, dispatch, payments, observability (vòng 1) |
| `29c724d` | Đóng regression sau deep-review: auth, dispatch, messaging (vòng 2) |
| `e2c87c7` | Deploy profile guard, ticket replay, redispatch chat (vòng 3) |
| `bd46c8b` | Sửa Flyway replay, phân trang chat, chặn eKYC replay sau xóa UID (vòng 4) |
| `7154703` | Test tombstone độc lập thứ tự chạy (vòng cuối) |
| `5f1e09d` | Tài liệu tổng hợp audit |
| `12b244d` | Sửa guard constraint idempotency theo schema + cập nhật test migration (sau khi chạy DB thật) |

---

## 2. Tổng hợp finding đã sửa

### 2.1 Auth / bảo mật

| ID | Mức | Vấn đề | Cách sửa | Commit |
|---|---|---|---|---|
| A1 | Cao | Header `X-Role` từ client có thể override vai trò của route → customer gọi endpoint driver, `X-Role: ADMIN` ngoài route admin | Filter lấy role từ cấu hình route, bỏ qua hint trên route role-scoped | `8744850` |
| A2 | Cao | Tài xế có cả 2 profile nhưng chưa verify vẫn vào route đặc quyền | Chặn theo trạng thái verification, giữ nguyên onboarding paths | `8744850`, `e2c87c7` |
| A3 | Cao | Firebase token bị thu hồi vẫn dùng được cho HTTP và STOMP CONNECT | Verify `checkRevoked` trên cả 2 kênh, clear security context khi fail | `8744850` |
| A4 | Trung | Bucket rate-limit login/OTP bị spoof qua forwarded header; bucket dùng account hash | Tách bucket theo endpoint, validate IP forwarded, hash account | `8744850`, `29c724d` |
| A5 | Cao | Admin bị khóa khỏi endpoint song vai trò `/verification/me` khi không gửi `X-Role` | Resolve theo route/permitted profiles, alias đặt trước `/api/driver/**` | `29c724d`, `e2c87c7` |
| A6 | Cao | Sau khi xóa tài khoản, UID Firebase cũ có thể đăng ký/đăng nhập lại thành driver | Bảng tombstone `deleted_firebase_uids` (V45), chặn register/login bằng UID đã xóa | `bd46c8b` |
| A7 | Cao | Xóa tài khoản không dọn eKYC/file ảnh; xóa file lỗi làm kẹt cleanup task | Purge row eKYC theo owner UID, enqueue mọi capture file, task chỉ complete khi xóa ảnh thành công | `8744850`, `29c724d` |
| A8 | Trung | Driver đã APPROVED đổi họ tên/CCCD qua profile endpoint thông thường | Chặn đổi identity sau approve | `8744850` |
| A9 | Trung | Avatar legacy nằm ngoài folder owner bị từ chối khi đổi avatar | Chỉ validate cấu trúc key với legacy key, nhưng vẫn kiểm tra quyền với ảnh mới | `e2c87c7` |

### 2.2 Dispatch / tracking / chat

| ID | Mức | Vấn đề | Cách sửa | Commit |
|---|---|---|---|---|
| D1 | Cao | GPS sai số lớn, fix cũ, ngoài khu vực vẫn vào index; Redis `ONLINE` lỗi thời vẫn dispatch | Kiểm tra accuracy + service area + freshness + DB status là nguồn truth, re-check accuracy trước khi re-add GEO | `8744850`, `29c724d` |
| D2 | Trung | Lỗi đọc accuracy từ Redis bị hiểu là "legacy sample" → evict nhầm driver | Phân biệt null thật vs lỗi HGET; lỗi thì skip round, giữ index entry | `29c724d`, `e2c87c7` |
| D3 | Cao | `NO_DRIVER_FOUND` không detach driver đã hủy; retry `SEARCHING` không tăng generation/refresh expiry | Detach + audit redispatch, tăng generation và expiry khi retry | `8744850` |
| D4 | Trung | Redis down làm mọi lần OCR eKYC trả 500 | Fallback quota local theo JVM, vẫn ghi metric suy giảm | `29c724d` |
| D5 | Cao | Upload ảnh chat: path traversal, query fragment, sai folder, sai owner vẫn được nhận | Validate prefix cụ thể theo conversation + sender, chặn traversal/foreign cloud | `8744850` |
| D6 | Cao | Sau redispatch: tài xế cũ ghi vào thread preserved mà customer không đọc; customer/read-count/push bị lệch thread | Ride-wide timeline cho customer, former driver chỉ đọc thread của mình, push đúng driver hiện tại | `8744850`, `e2c87c7`, `bd46c8b` |
| D7 | Cao | `markRead` dùng id từ ride/thread khác làm receipt | Clamp bằng `max(id)` của chính thread; max `<= 0` coi là unknown, không publish receipt giả | `e2c87c7`, `bd46c8b` |
| D8 | Cao | Visibility lọc **sau** paging theo từng thread → trang có thể rỗng dù vẫn còn tin nhắn nhìn được | Đẩy predicate visibility vào JPQL (`latestVisible/beforeVisible/afterVisible`), lọc trước khi phân trang | `bd46c8b` |

### 2.3 Thanh toán / voucher / cấu giá

| ID | Mức | Vấn đề | Cách sửa | Commit |
|---|---|---|---|---|
| P1 | Cao | Mock top-up admin không idempotent, sai owner/wallet, khác key vẫn credit | Dùng request key + owner type do server xác định, lưu receipt, conflict khi đổi wallet/key | `8744850` |
| P2 | Cao | Hold/release voucher consume hoàn toàn discount thay vì forecast đã reserve, release sai điều kiện | Clamp theo reserve thực tế, release đúng consumed amount, không refund sau spend | `8744850` |
| P3 | Trung | Campaign published notify sai bảng customer; lỗi 1 người chặn cả batch | Query đúng bảng, isolate lỗi từng recipient, tiếp tục gửi người còn lại | `8744850`, `29c724d` |
| P4 | Trung | Config waiting rate > 0 vẫn được lưu dù không có telemetry | Từ chối ở cả DTO create/update trước khi đụng repository | `8744850` |

### 2.4 Support ticket

| ID | Mức | Vấn đề | Cách sửa | Commit |
|---|---|---|---|---|
| T1 | Cao | Double-submit ticket message tạo nhiều dòng timeline | V43 unique `(ticket_id, client_request_id)`, default UUID cho writer cũ | `8744850` |
| T2 | Trung | Cùng key nhưng đổi requested status bị trả về message cũ, mất intent mới | So sánh intent, trả 409 nếu khác | `29c724d` |
| T3 | Cao | Replay sau khi ticket đã bị message sau đẩy trạng thái → 409 sai | Ghi `requested_status` riêng; V44 nullable, user reply ghi `NULL`, admin ghi intent | `e2c87c7`, `bd46c8b` |
| T4 | Cao | V43 check `pg_constraint` theo **tên constraint toàn cục**, nên khi chạy migration ở nhiều schema (test isolation / replay) constraint `UNIQUE(ticket_id, client_request_id)` bị bỏ qua ở schema hiện tại → mọi `ON CONFLICT` của ticket fail | Thêm V46, guard theo `c.conrelid = 'support_ticket_messages'::regclass` (schema hiện tại) | `12b244d` |

### 2.5 Khác

| ID | Mức | Vấn đề | Cách sửa | Commit |
|---|---|---|---|---|
| O1 | Trung | `totalRatings` không khớp `averageRating` khi ẩn rating; driver bị đánh giá bởi người không được assign; customer rating bị push cho driver | Tính total trên mọi rating, visibleTotal chỉ cho pagination, validate quan hệ ride, chặn push chiều customer→driver | `8744850`, `29c724d` |
| O2 | Trung | Customer bị soft-delete/banned vẫn sửa profile & avatar | Chặn ghi khi tài khoản không active | `8744850` |
| O3 | Trung | FCM fail im lặng, copy không localization, lỗi after-commit làm fail cả request | Đếm metric thất bại, copy tiếng Việt, isolate failure sau commit | `8744850` |
| O4 | Trung | Swagger bật ở prod, backend bind mọi interface, Flyway baseline quá dễ | Tắt Swagger prod, bind `127.0.0.1:8080`, baseline-on-migrate=false | `8744850` |
| O5 | Cao | Deploy script cho phép profile dev, JVM options từ env có thể override profile production | Guard raw + resolved (`docker compose config`), block `JAVA_OPTS`/`_JAVA_OPTIONS`/`JAVA_TOOL_OPTIONS`/`JDK_JAVA_OPTIONS`/`SPRING_CONFIG_*`, Dockerfile gh cờ JVM cố định | `8744850`, `29c724d`, `e2c87c7` |
| O6 | Cao | eKYC decision trễ sau khi xóa tài khoản recreate projection APPROVED | V45 tombstone + lọc sự kiện theo live driver/application/cursor vẫn tiến | `bd46c8b` |

### 2.6 Chất lượng test

| ID | Mức | Vấn đề | Cách sửa | Commit |
|---|---|---|---|---|
| C1 | Trung | Mockito matcher leak do Kotlin non-null params | Dùng literal value, thêm regression test | `8744850` |
| C2 | Cao | Test tombstone V45 ghi cứng stream random + commit tombstone/cursor → fail theo thứ tự JUnit | Wrap transaction `setRollbackOnly()` + tái dùng `stream_id` hiện có | `7154703` |
| C3 | Thấp | Assertion `never()` trong test chat không bao giờ có thể trigger; bỏ `validateMockitoUsage()` | Xóa assertion vô nghĩa, khôi phục `Mockito.validateMockitoUsage()` | `7154703` |
| C4 | Trung | Test migration vẫn expect tip `42` / 1 migration; test integration expect `totalRatings = 0` dù rating ẩn đã được tính | Cập nhật expectation lên V46 / 5 migration / `totalRatings = 1` | `12b244d` |

---

## 3. Chi tiết các finding vòng cuối (dễ tái phát nhất)

### F1 — Visibility lọc sau paging (`ChatMessageRepository`, `ChatService`)
Khi redispatch, tin nhắn có thể nằm ở thread cũ. Code cũ gọi repository theo từng thread với `PageRequest`, rồi mới lọc bỏ tin của driver cũ → trang trả về ít hơn `size` dù DB còn tin customer. Đã chuyển thành 3 query JPQL lọc trong `WHERE`:

- `latestVisible(cids, ownCid, me, pageable)`
- `beforeVisible(..., beforeId, pageable)`
- `afterVisible(..., afterId, pageable)`

`me = ""` nghĩa là thấy tất cả; driver hiện tại truyền `"DRIVER"` nhưng thread của mình được exempt qua `ownCid`; tài xế cũ chỉ nhận `listOf(ownConversation.id)`.

### F2 — `requested_status` & checksum Flyway
- `e2c87c7` từng sửa **trực tiếp V43** để thêm `requested_status NOT NULL DEFAULT 'WAITING_FOR_USER'` + backfill.
- `bd46c8b` **khôi phục V43 về byte gốc** (`42d2ae1591dc1568d87b0ef42def0d071e443589`, bằng `8744850`/`29c724d`) và chuyển cột sang `V44` dạng `nullable`, không default.
- Lý do: DB đã apply V43 bản gốc thì checksum khớp; writer user ghi `NULL` → replay permissive cho row legacy.
- **Caveat**: DB nào từng chạy Flyway trên commit local trung gian `e2c87c7` (blob V43 `4ef6818559e1f64e330148fe1548709ae6b6b5e6`) thì phải `flyway repair` trước khi deploy bản này.

### F3 — Tombstone UID driver
- V45 tạo `deleted_firebase_uids(firebase_uid PK)`.
- `AccountDeletionService` ghi tombstone bằng UID gốc (`ON CONFLICT DO NOTHING`).
- `AuthService` chặn register/login driver bằng `SELECT EXISTS(...)`.
- `VerificationProjectionRepository.applyPage()` bỏ event eKYC trễ nếu UID bị tombstone, hoặc không có driver/application tồn tại; cursor vẫn tiến để feed không stall.

### F4 — Deploy guard
- Guard **raw**: đọc env file, normalize key (upper, `.`/`-`/`[]` → `_`), block JVM/config keys, chặn mock top-up truthy, chỉ cho profile `prod|production`.
- Guard **resolved**: đọc `docker compose config --format json`, áp cùng normalization → chặn cả giá trị sau interpolation/env_file merge.
- `Dockerfile` dùng cờ JVM cố định, không còn expansion `$JAVA_OPTS`.
- Audit cuối không tìm thấy bypass lẫn false-rejection mới; không log secret.

### F5 — Unique constraint chỉ tồn tại ở một schema (phát hiện khi chạy Postgres thật)
- V43 dùng `IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = '...')`: tên constraint **không** bị giới hạn bởi schema, nên nếu suite integration tạo constraint đó trước trong schema riêng, `SupportPersistenceTests` (mỗi test một schema) bị V43 bỏ qua tạo constraint.
- Hệ quả: `SupportService.create()` gọi `INSERT ... ON CONFLICT (ticket_id, client_request_id)` → `ERROR: there is no unique or exclusion constraint matching the ON CONFLICT specification` (10 test fail).
- V46 sửa guard bằng `c.conrelid = 'support_ticket_messages'::regclass`, tức scope theo `search_path` hiện tại; no-op trên DB đã có constraint. Không sửa V43 để giữ nguyên checksum.

---

## 4. Kiểm chứng

### 4.1 Đã chạy được ở local

```powershell
.\gradlew.bat clean check --no-daemon --console=plain
# BUILD SUCCESSFUL
# suites=97 tests=368 failures=0 errors=0 skipped=69
```

Chạy với Postgres 17 thật (container local, env theo `.github/workflows/ci.yml`), DB được tạo mới:

```powershell
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/datxe_test'
$env:TEST_DB_USER = 'sync_test'; $env:TEST_DB_PASSWORD = 'datxe_ci_only'
$env:VERIFICATION_E2E_PG_URL = $env:TEST_DB_URL
$env:VERIFICATION_E2E_PG_USER = $env:TEST_DB_USER
$env:VERIFICATION_E2E_PG_PASSWORD = $env:TEST_DB_PASSWORD
$env:VERIFICATION_SYNC_TEST_DB = $env:TEST_DB_URL
$env:VERIFICATION_SYNC_TEST_PASSWORD = $env:TEST_DB_PASSWORD
.\gradlew.bat cleanTest test --no-daemon --console=plain --rerun-tasks
# BUILD SUCCESSFUL
# suites=97 tests=368 failures=0 errors=0 skipped=3
```

Lần đầu chạy DB thật phát hiện 12 fail: 10 do F5 (constraint sai schema) + 2 do expectation migration lỗi thời (V42). Sau V46 và cập nhật test → **0 fail**. Còn 3 skip là suite cần dịch vụ ngoài/network (không phải DB).

- `git status --short` → sạch; `git diff --check` → sạch.
- Parse 6 query JPA bằng parser Hibernate `7.4.13.Final`: `OK` hết (3 query visibility mới, `findMaxIdInConversation`, `markRead`, `countUnreadFor`).
- Audit DB độc lập parse 3 query visibility bằng `SessionFactory` thật → sạch.
- SQL `applyPage`, tombstone `INSERT ... ON CONFLICT DO NOTHING`, Auth `SELECT EXISTS`, `find()` có `NOT EXISTS` → hợp lệ PostgreSQL 17 / Spring JDBC 7.0.10 / PG JDBC 42.7.13.
- Ma trận guard raw/resolved 11/11 case đạt; `docker compose config --format json` với env mẫu → exit `0` cả 2 chiều.

### 4.2 Đã kiểm chứng xong phần từng bị skip

Ban đầu Docker daemon lỗi (`npipe:////./pipe/dockerDesktopLinuxEngine`) nên 69 test DB-gated bị skip. Sau khi daemon chạy lại, đã bật container Postgres 17 local và chạy toàn bộ suite với env như CI: **368 tests, 0 fail, 0 error, 3 skip**. Các suite đã được validate thật gồm `DatxebackendApplicationTests`, `VerificationProjectionPostgresTest` (có test tombstone V45), `LegacyAdminMessageDedupePostgresTests`, `IntegrationReleaseRegressionPostgresTests`, `SupportPersistenceTests`, `DispatchPersistenceTests`, nhóm Verification concurrency/identity/face, Wallet, Admin, v.v.

Một lần lặp lại trên cùng DB sau đó fail do **môi trường**: ổ C hết dung lượng (0.03 GB) và Docker/Postgres mất kết nối (`Connection refused`, `FileSystemException` ghi temp). Đây không phải lỗi code; cần dọn dung lượng/host ổn định trước khi lặp lại lần nữa.

---

## 5. Residual cố tình không sửa / cần lưu ý

1. **Checksum V43** trên DB từng apply `e2c87c7` → cần `flyway repair` trước deploy. Xem mục 3/F2. V46 không thay thế được bước này.
2. 3 test vẫn skip khi có env DB (suite cần dịch vụ/network ngoài Postgres). Khi không set env, 69 test DB-gated skip như cũ. Lần lặp thứ 2 trên cùng host bị chặn bởi ổ C đầy + Docker mất kết nối.
3. Chat push vẫn **at-most-once**, chưa có outbox.
4. Admin không gửi push khi user reply ticket.
5. Forwarded-header trust / IP bucket có thể spoof trong một số cấu hình proxy.
6. Scope gate `verificationStatus` vẫn là residual đã biết.
7. Quota eKYC nhân theo replica khi Redis down (mỗi JVM một bucket local).
8. Poison-key retry vô hạn (chưa có dead-letter/backoff vĩnh viễn).
9. Flyway baseline chưa tự động hóa trong quy trình deploy.
10. Backend bind `127.0.0.1:8080` (chủ đích; cần reverse proxy).
11. `application-prod.properties` vẫn tồn tại trong repo (profile production được pin qua compose).
12. Fallback password test `datxe123` vẫn còn trong cấu hình test.
13. Guard chặn toàn bộ `SPRING_CONFIG_*` (stricter hơn mức tối thiểu, chấp nhận).
14. Config import `optional:file:./.env[.properties]` tồn tại nhưng không có đường deploy nào mount file đó vào image; giữ `.env` ra khỏi release.

### Client-only (đã xác nhận, không thuộc phạm vi sửa backend)
- Composer tài xế cũ vẫn cho gõ sau redispatch.
- Ticket mobile chưa gửi `clientRequestId`.
- `totalRatings` giờ tính cả rating ẩn (đã thống nhất với `averageRating`).

---

## 6. Kết luận

Mọi finding có đường đi cụ thể đều đã sửa và commit local, **bao gồm cả lỗi mới phát hiện khi chạy Postgres thật (F5)**. Không còn lỗi xác nhận nào chưa xử lý. Hai caveat còn lại đã nêu rõ ở mục 4.2 và 5: checksum V43 trên DB trung gian `e2c87c7`, và môi trường host (ổ C đầy) chưa cho phép lặp lại lần chạy DB thứ hai. Không push.
