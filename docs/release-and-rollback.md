# Phát hành backend và khôi phục

Workflow Backend CI chạy Gradle compile, test và build với PostgreSQL 17. Workflow Deploy to Ubuntu Server nhận dispatch thủ công trên `main`. Job trên GitHub-hosted runner kiểm tra workflow `ci.yml` đã thành công cho đúng SHA, sự kiện push và nhánh `main` trước khi cấp việc cho runner production. Deploy dùng nhóm concurrency riêng và GitHub Environment `production`. Chủ repo cần kiểm tra quyền chạy workflow, nhánh được phép triển khai, reviewer của Environment và runner được phép truy cập theo gói GitHub hiện có.

## Chuẩn bị release

1. Dùng runner Linux chuyên dụng, chạy bằng tài khoản non-root có quyền truy cập Docker. Kiểm tra Bash, Python 3, curl và Docker Compose hỗ trợ `config --format json`, `up --wait` và `--wait-timeout`. Compose v5.7.0 đã dùng để kiểm tra cấu hình local. Xác minh phiên bản runner production trước dispatch.
2. Giữ các secret hiện hành `ENV_FILE`, `FIREBASE_JSON`, `CLOUDFLARE_TOKEN`. Cấu hình `DATXE_VERIFICATION_HOST_DIR` trong `ENV_FILE` bằng đường dẫn tuyệt đối trên persistent storage, ngoài checkout, do tài khoản runner sở hữu với mode `0700`. Cấp `DATXE_VERIFICATION_ENCRYPTION_KEY` qua quy trình secret manager của nhóm. Giá trị này phải ổn định để đọc ảnh qua các lần restart.
3. Kiểm tra backend hiện tại có mount đúng nguồn vào `/var/lib/ptitgo/verification` chưa. Script dừng trước khi thay container nếu mount hiện tại khác, kho còn object legacy hoặc không đọc được marker định dạng. Thực hiện kế hoạch chuyển kho bên dưới sau khi chủ dữ liệu phê duyệt.
   Nếu không còn container nào để nhận diện project, xác minh tên project và các volume cũ rồi đặt `COMPOSE_PROJECT_NAME` trong `ENV_FILE` trước khi chạy.
4. Sao lưu PostgreSQL và kho ảnh ở cùng mốc nhất quán. Kiểm tra tính tương thích của migration mới với bản ứng dụng trước đó. Xác nhận quota request cho đường upload ảnh ở ingress và việc eKYC inference chỉ truy cập được từ backend qua mạng nội bộ.
5. Với database đã có schema từ trước nhưng **chưa có** `flyway_schema_history`, Flyway hiện dừng startup (`baseline-on-migrate=false`). Trước lần deploy đầu tiên sau thay đổi này, xác nhận bảng lịch sử tồn tại bằng `SELECT to_regclass('public.flyway_schema_history')`. Nếu trả `NULL`, sau khi sao lưu hãy chạy **một lần** lệnh `docker compose --env-file <release.env> -f docker-compose.yml -f docker-compose.production.yml run --rm -e SPRING_FLYWAY_BASELINE_ON_MIGRATE=true backend` (thay `<release.env>` bằng env file của release, giữ `--project-name` nếu script đã chọn project) để tạo mốc baseline, rồi deploy bình thường. Compose chỉ truyền biến được khai báo, nên đặt biến trong shell trước khi `up` sẽ **không** có tác dụng. Không bật baseline vĩnh viễn trong `ENV_FILE`: khi lịch sử đã tồn tại Flyway vẫn áp migration mới, nhưng bật vĩnh viễn có thể baseline nhầm một schema lạ ở lần sau.

Workflow checkout vào `release-src/` bên dưới workspace. Script tạo thư mục release ngoài workspace, ghi file quyền owner-only, kiểm tra cấu trúc Firebase JSON và báo nếu `ENV_FILE` cũng khai báo token Cloudflare. Tunnel lấy token từ secret riêng. Compose xác định project từ nhãn của container hiện có, kiểm tra cấu hình, build image theo SHA và giữ tag image trước. Script thử quyền đọc Firebase và quyền ghi mount trước khi thay backend. PostgreSQL và Redis được giữ bằng `--no-recreate`. Sau khi thay backend, script chờ healthcheck và xác nhận HTTP `/actuator/health` trước khi cập nhật tunnel.

## Rollback ứng dụng

1. Kiểm tra nguyên nhân lỗi và kết quả Flyway. Nếu schema đã tiến lên và bản ứng dụng trước thiếu tương thích, ưu tiên sửa tiến lên hoặc dùng phương án phục hồi cơ sở dữ liệu đã được phê duyệt. Rollback ứng dụng không tự đảo migration.
2. Trong thư mục release, file `release` lưu tên Compose project, image mới và ID image trước. Image trước còn được gắn tag `ptitgo-backend:rollback-<run>-<attempt>`. Chọn cấu hình release trước đã duyệt, giữ đúng project và mount ảnh. Cấp lại secret tunnel từ nguồn hiện hành để Compose đọc cấu hình.
3. Đặt `DATXE_BACKEND_IMAGE` là image rollback. Dùng cả hai file Compose, env file và Firebase JSON của release đã chọn. Chạy `docker compose up -d --no-deps --no-build --wait --wait-timeout 180 backend` với các tham số project và env tương ứng. Kiểm tra `/actuator/health`, quyền đọc ảnh và luồng smoke trước khi mở lại lưu lượng.

## Kế hoạch chuyển kho ảnh cần phê duyệt

Chỉ thực hiện trong cửa sổ bảo trì. Kiểm kê metadata ảnh theo storage key và revision, xác định mount/directory nguồn và ghi nhận định dạng encrypted hay plaintext. Tạm ngừng thao tác ghi để sao lưu nhất quán PostgreSQL cùng kho ảnh, giữ bản sao khóa trong secret manager riêng. Tạo bản copy staging của kho, kiểm tra hash theo metadata và thử bằng tài khoản test ở đúng vai trò.

Nếu cần chuyển plaintext, tạo bản sao mã hóa bằng định dạng envelope tương thích trong kho mới và xác nhận toàn bộ ảnh có thể giải mã với đúng kích thước và SHA-256 trước khi đổi mount. Giữ kho cũ và container cũ theo thời hạn lưu đã duyệt. Xác nhận rollback và thời gian giữ bản sao trước cutover. Test phục hồi phải gồm đọc ảnh sau khi recreate container và kiểm tra quyền theo owner, reviewer và revision. Workflow hiện tại không thực hiện việc chuyển dữ liệu.

## Kết quả diễn tập cô lập ngày 17/11/2026

Lượt đạt bắt đầu lúc 22:02:18 giờ Việt Nam, hoàn thành trong 56,8 giây. Diễn tập dùng hai PostgreSQL 17 containers trên mạng Docker internal, hai ảnh PNG tổng hợp và khóa AES riêng cho test. Một Java harness gọi các lớp storage và workflow từ JAR backend đã kiểm tra hash với source. Hai kho plaintext và encrypted dùng bind mount test riêng. Database và kho ảnh nguồn vẫn được giữ. Các container test đã dừng.

| Kiểm tra | Kết quả |
| --- | --- |
| Seed | Flyway chạy trên database trống. Ghi metadata và hai snapshot cho revision 7, 8 của một hồ sơ tổng hợp. |
| Chuyển kho | Tạo bản sao envelope DXE1/AES-GCM bằng storage service. Giữ storage key, SHA-256, MIME, byte size, revision và trạng thái retired. SHA-256 sau giải mã khớp cả ảnh nguồn lẫn metadata. |
| Restart và recreate | Đọc lại được ảnh cùng metadata sau khi restart container harness và sau khi tạo container harness mới trỏ tới cùng kho test. |
| Backup và restore | Dùng `pg_dump -Fc`, sao lưu kho encrypted khi không có ghi, chạy `pg_restore --exit-on-error` vào PostgreSQL thứ hai và chạy Flyway validate. Đọc được ảnh phục hồi với đúng khóa. |
| Sự cố storage | Thiếu khóa bị chặn lúc tạo service. Sai khóa hoặc ciphertext bị hỏng bị AES-GCM từ chối. File tạm thất lạc bị từ chối với mã 503 ở service. Hành vi HTTP với sai khóa hoặc ciphertext hỏng còn cần E2E. |
| Quyền đọc | Owner đọc được ảnh hiện hành. Reviewer đọc được hai revision. Owner vào đường reviewer bị 403. Driver khác hoặc revision không tồn tại bị 404. |
| Rollback kho | Đọc lại được kho plaintext nguồn bằng reader legacy trên metadata đã restore, rồi đọc lại được kho encrypted với đúng khóa. Đây là rollback cấu hình kho trong harness. Rollback image hay schema production còn cần diễn tập riêng khi đã duyệt. |

SHA-256 của hai ảnh tổng hợp theo thứ tự revision 7 và 8 là `fe2afc6533574dd46d62f75625444e57da7e1be804cc8a5bda495af0775273ba` và `49572a4e10ab474717f3fd9c9f179833df0311dc1e81c8b1419bb7de1f6ab482`. Archive PostgreSQL test có SHA-256 `e08c27e56da47312781e53c7c2a37197f292d1a05a7acc80bd50d4c1cd073e40`. Các archive thử nghiệm nằm ngoài repo. Khóa test được cấp cho harness riêng và không được lưu trong archive.

Lượt thử đầu dừng ở cách PowerShell truyền tham số Java. Lượt sau dùng đối số được quote và qua đủ sáu phase. Thời gian đo trên hai ảnh không đại diện cho dung lượng production. Trước cutover thật, cần phê duyệt nguồn và đích, đánh giá dung lượng và thời gian ngừng ghi, thiết lập quyền volume trên Linux, xác nhận escrow khóa rồi diễn tập cả đường HTTP có xác thực. Hai capture local hiện có nằm ngoài phạm vi và không được đưa vào bản sao staging.
