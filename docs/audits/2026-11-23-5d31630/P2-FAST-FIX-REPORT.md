# Báo Cáo Sprint Sửa P2 Tác Động Cao (`P2-FAST-FIX-REPORT`)

- **Run ID**: `2026-11-23-5d31630`
- **Nhánh / Baseline HEAD**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Kết quả**: **14/14 P2 trong phạm vi `VERIFIED_FIXED`** (tổng toàn dự án: **9/9 P1**, **18/25 P2**, **1/17 P3** `VERIFIED_FIXED`, **1 `PRODUCT_DECISION_REQUIRED`** `F-018`). Không commit/push/deploy; không chạm DB/Redis phát triển.

---

## 1. Findings Đã Sửa & Xác Minh (RED → FIX → GREEN)

| Nhóm | ID | Trạng Thái | Tóm Tắt Sửa Tối Thiểu & Bằng Chứng |
|---|---|---|---|
| **A** | **`F-015`** | **`VERIFIED_FIXED`** | Đồng bộ [application-production.properties](file:///f:/datxebackend/src/main/resources/application-production.properties) & [application-prod.properties](file:///f:/datxebackend/src/main/resources/application-prod.properties) (tắt Swagger/OpenAPI, ẩn health details, bật protected storage). |
| **A** | **`F-033`** | **`VERIFIED_FIXED`** | [VoucherCampaignPublishedListener.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/voucher/service/VoucherCampaignPublishedListener.kt#L28-L39): sửa SQL sang `customers WHERE status = 'ACTIVE' AND deleted_at IS NULL` + bọc `try/catch` `AFTER_COMMIT`. |
| **A** | **`F-035`** | **`VERIFIED_FIXED`** | [CustomerPlaceService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/customer/service/CustomerPlaceService.kt#L63-L65): thêm k-anonymity `HAVING COUNT(DISTINCT customer_id) >= 5`. |
| **B** | **`F-005`** | **`VERIFIED_FIXED`** | [PaymentMethodService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/payment/service/PaymentMethodService.kt#L39-L53): kiểm tra lại `vouchers.allowed_payment_method` khi đổi `CASH ↔ WALLET` (PG 17 GREEN; fallback `WALLET → CASH` chờ `OBS-01`). |
| **B** | **`F-006`** | **`VERIFIED_FIXED`** | [PaymentService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/payment/service/PaymentService.kt#L149-L176): phát `PaymentUpdated` khi `WALLET → CASH` fallback lúc hoàn tất chuyến. |
| **B** | **`F-009`** | **`VERIFIED_FIXED`** | [PromoBudgetService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/voucher/service/PromoBudgetService.kt#L30-L47): khóa `promo_budget FOR UPDATE` + điều kiện cập nhật nguyên tử `? >= held + spent + issuance_reserved` (PG 17 GREEN). |
| **B** | **`F-012`** | **`VERIFIED_FIXED`** | [VoucherService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/voucher/service/VoucherService.kt#L90-L128): trừ `released_amount` trong `listClaimableCampaigns` và `claimCampaign` (PG 17 GREEN). |
| **C** | **`F-013`** | **`VERIFIED_FIXED`** | [VoucherService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/voucher/service/VoucherService.kt#L52): bỏ `@Transactional(readOnly = true)` ngoài `listAvailable` tránh deadlock HikariCP với `grantWelcome` (`REQUIRES_NEW`). |
| **C** | **`F-021`** | **`VERIFIED_FIXED`** | [DriverVerificationWorkflowService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/driver/verification/DriverVerificationWorkflowService.kt#L1220-L1228): bỏ `@Transactional` và `forUpdate = true` khỏi `faceComparison` (không giữ DB lock qua Modal HTTP). |
| **C** | **`F-029`** | **`VERIFIED_FIXED`** | [RideEstimateService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/ride/service/RideEstimateService.kt#L29): bỏ `@Transactional` khỏi `estimateRide` (không giữ DB connection qua Goong HTTP; lưu ý `createRide` khi cache miss cần refactor tách facade riêng nếu cần). |
| **C** | **`F-047`** | **`VERIFIED_FIXED`** | [RideRatingService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/rating/service/RideRatingService.kt#L228-L263): đẩy `notifications.send` vào `TransactionSynchronization.afterCommit` (0 push khi rollback). |
| **C** | **`F-048`** | **`VERIFIED_FIXED`** | [RideRatingService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/rating/service/RideRatingService.kt#L204-L210): khóa `SELECT id FROM drivers/customers WHERE id = ? FOR UPDATE` trước khi tính `AVG(stars)`. |
| **D** | **`F-037`** | **`VERIFIED_FIXED`** | [TicketService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/support/ticket/TicketService.kt#L61-L70): `sendUserMessage` khóa `tickets.findForUpdate(id)`, xác minh owner và chặn `CLOSED` bằng `409 ConflictException`. |
| **D** | **`F-038`** | **`VERIFIED_FIXED`** | [SupportService.kt](file:///f:/datxebackend/src/main/kotlin/com/nhom15/datxebackend/support/SupportService.kt#L90-L135): `update` kiểm tra claim `IN_PROGRESS` của admin khác (`409`), bắt buộc `response` khi `CLOSED`/`RESOLVED`, và phát `TicketReplied`. |

---

## 2. Kết Quả Kiểm Thử

- **RED**: 4 suite mới (`P2GroupASecurityPrivacyTests`, `P2GroupBFinancialIntegrityPostgresTests`, `P2GroupCTransactionsTests`, `P2GroupDSupportTests`) — **10/10 failed**.
- **Full Gradle Suite** (disposable PG 17 `5439` + disposable Redis 7 `6399`, đã cleanup sạch):
  - **80 suites, 302 tests**: **299 PASSED** (295 lượt full suite + 4/4 `TicketServiceTests` cập nhật stub `findForUpdate` và PASS lại), **0 FAILED**, **0 ERRORS**, **3 SKIPPED** (live external APIs), **1 Python Lua `BLOCKED`** (`liblua5.4`).

---

## 3. Files Modified & Remaining Blockers

- **Production (12 file)**: `application-production.properties`, `application-prod.properties`, `VoucherCampaignPublishedListener.kt`, `CustomerPlaceService.kt`, `PaymentMethodService.kt`, `PaymentService.kt`, `PromoBudgetService.kt`, `VoucherService.kt`, `DriverVerificationWorkflowService.kt`, `RideEstimateService.kt`, `RideRatingService.kt`, `TicketService.kt`, `SupportService.kt`.
- **Tests (5 file)**: 4 suite mới + cập nhật mock `TicketServiceTests.kt`.
- **Còn mở**: **0 P1**, **6 P2** (`F-003`, `F-007`, `F-011`, `F-030`, `F-031`, `F-046`), **1 `PRODUCT_DECISION_REQUIRED`** (`F-018`/`OBS-12`), **16 P3**, và 5 quyết định sản phẩm (`OBS-01`, `OBS-11`..`OBS-14`).
