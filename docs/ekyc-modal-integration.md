# Kết nối eKYC Modal từ backend đặt xe

Module tùy chọn này cung cấp đề xuất QR/OCR cho tài xế thông qua API đã xác thực Firebase của backend đặt xe. Hồ sơ xác minh, nộp lại và admin review vẫn sử dụng workflow eKYC riêng.

## Bật trên server

Đặt các biến sau trong secret/config của deployment backend đặt xe. `DATXE_EKYC_ENABLED=false` là mặc định.

```dotenv
DATXE_EKYC_ENABLED=true
DATXE_EKYC_ORIGIN=https://quyennv1912ptit--ekyc-extraction-staging-cccd-api.ap-south.modal.run
DATXE_EKYC_API_KEY=
DATXE_EKYC_MODAL_KEY=
DATXE_EKYC_MODAL_SECRET=
```

Lấy `DATXE_EKYC_API_KEY` từ eKYC staging secret đã provision. Hai biến Modal dùng proxy token dành cho endpoint, khác credential để deploy Modal. Trên máy kiểm thử, các giá trị nằm trong `%LOCALAPPDATA%\EkycModalStaging\ekyc-key.env` và `proxy-token.json`. Không đưa chúng vào source, Expo, Vite hoặc GitHub Actions log. Chỉ bật staging cho ảnh tổng hợp cho tới khi có môi trường xử lý dữ liệu thật được xác minh.

Chạy lại backend theo quy trình deploy hiện có. Module chỉ chấp nhận origin HTTPS `.modal.run` (không có path, port, query hay redirect). Các biến gán khi startup. Tắt module bằng `DATXE_EKYC_ENABLED=false` rồi restart.

## API

`POST /api/driver/ekyc/documents/{slot}/extract`

- Firebase ID token của tài xế trong `Authorization: Bearer …`. Vai trò Driver áp dụng qua security chain hiện hành.
- `slot`: `cccd_front`, `cccd_back`, `gplx_front` hoặc `gplx_back`.
- Body là bytes JPEG/PNG/WebP, giới hạn 8.000.000 byte. Giới hạn được kiểm tra cả với request không có Content-Length.
- Kết quả dùng envelope của backend đặt xe. `data` giữ `slot`, `qrStatus`, `ocrStatus`, `candidates`, warnings và conflict fields từ eKYC. Ảnh và response không được lưu bởi module. Response dùng `Cache-Control: no-store`.
- `EKYC_BUSY` hoặc `EKYC_UNAVAILABLE` trả HTTP 503. `EKYC_TIMEOUT` trả 504. Client vẫn có thể dùng nhập tay. Mặt sau GPLX có thể trả trạng thái OCR chưa hỗ trợ hoặc QR không có dữ liệu phù hợp.
- Timeout server gọi Modal là 75 giây, gồm dư địa dành cho cold start. Đối chiếu timeout của reverse proxy và client khi nối màn hình mới.

## Kiểm thử

```powershell
.\gradlew.bat --offline test bootJar
```

`EkycClientTest` bao phủ forwarding, timeout, giới hạn ảnh, mime và mặt không hợp lệ. `EkycModalSmokeTest` chỉ bật khi `DATXE_EKYC_SMOKE=true` và server-side credential đã được đưa vào env, gửi ảnh được tạo trong test. Không dùng ảnh thật.
