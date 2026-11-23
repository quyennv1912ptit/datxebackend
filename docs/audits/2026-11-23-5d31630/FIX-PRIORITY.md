# PtitGo Backend — Dependency-Ordered Fix Priority Plan (51 Confirmed Findings)

- **Baseline**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Total**: **51 findings** (`0 P0`, `9 P1`, `25 P2`, `17 P3`). All fixes must follow TDD (`RED` failing test → minimal fix → `GREEN`) in `FIX` mode.

---

## Batch 1 — Immediate Runtime-Verified P1 Security & Dispatch Fixes (**DONE**)
| Order | IDs | Scope | Dependency | Mandatory Regression Tests |
|---:|---|---|---|---|
| **1.1** | **`F-014`** (`VERIFIED_FIXED`), **`F-016`** (`VERIFIED_FIXED`) | `StompAuthInterceptor`: restrict `SEND` to `/app/location`; `FirebaseAuthFilter` & `FirebaseAuthTokenVerifier`: `checkRevoked=true` + session re-check + `StompChannel` outbound guard | None | `StompAuthInterceptorSecurityTests` (4/4), `AuthRevocationLifecycleTests` (5/5). |
| **1.2** | **`F-001`** (`VERIFIED_FIXED`) | `AdminRideService.kt:338,340`: fix `ZREM available_drivers ARGV[3]` and `GET KEYS[8] == ARGV[2]` + recovery for stale `current_ride` | None | `AdminRideRedisReassignTests` (4/4 on disposable Redis 7). |

## Batch 2 — P1 Account Lifecycle & eKYC Identity Lock (**DONE — `F-018` awaiting `OBS-12`**)
| Order | IDs | Scope | Dependency | Mandatory Regression Tests |
|---:|---|---|---|---|
| **2.1** | **`F-023`** (`VERIFIED_FIXED`), **`F-024`** (`VERIFIED_FIXED`), **`F-025`** (`VERIFIED_FIXED`) | `AccountDeletionService`, `AccountCleanupOutboxWorker`, `RideService`, `RideStateMachine`, `AuthService#attachDriverByPhone` | None | `AccountLifecycleBatch2ATests` (6/6), `AccountDeletionConcurrencyPostgresTests` (2/2 PG 17). |
| **2.2** | **`F-017`** (`VERIFIED_FIXED`), **`F-019`** (`VERIFIED_FIXED`), **`F-018`** (`PRODUCT_DECISION_REQUIRED`) | `DriverVerificationWorkflowService`, `DriverVerificationStorageService`, `V43__identity_lock_unique_indexes.sql` | `OBS-12` for `F-018` | `EkycIdentityLockAndKeyLifecycleTests` (6/6), `V43IdentityLockSafetyGatePostgresTests` (3/3 PG 17). |

## Batch 3 — P1 Driver State & Redis/DB Presence Consistency (**DONE**)
| Order | IDs | Scope | Dependency | Mandatory Regression Tests |
|---:|---|---|---|---|
| **3.1** | **`F-004`** (`VERIFIED_FIXED`), **`F-020`** (`VERIFIED_FIXED`), **`F-044`** (`VERIFIED_FIXED`), **`F-027`** (`VERIFIED_FIXED`) | `AdminDriverService#forceOffline`, `DriverVerificationWorkflowService#syncProjectionAndDriver`, `DriverCapabilityService#update`, `DriverVerificationEligibility#statusAfterRide` | None | `DriverStateConsistencyTests` (4/4). |

## Batch 4 — P1/P2 Voucher, Promo Budget & Payment Integrity (**P1 `F-010`, `F-002` DONE; P2s Open**)
| Order | IDs | Scope | Dependency | Mandatory Regression Tests |
|---:|---|---|---|---|
| **4.1** | **`F-010`** (P1), **`F-002`** (P1), **`F-009`** (P2), **`F-011`** (P2), **`F-012`** (P2), **`F-030`** (P2) | Fix `PromoBudgetService#release` (`forecast` vs `holdAmount`), lock `promo_budget FOR UPDATE` + DB `CHECK` cap, re-consume or zero discount on `adminAssignDriver` from `NO_DRIVER_FOUND`, fix `claimCampaign` milestone collision & `released_amount`, cap `scheduledAt` horizon & validate voucher `validTo >= scheduledAt` | None | PG tests: 5× welcome-voucher book→cancel leaves `issuance_reserved` invariant; `NO_DRIVER_FOUND` → admin assign cannot double-redeem; `setLimit` concurrency test. |
| **4.2** | **`F-005`** (P2), **`F-006`** (P2), **`F-007`** (P2), **`F-033`** (P2) | Re-validate `allowed_payment_method` on switch/fallback, publish `PaymentUpdated` on `WALLET→CASH` fallback, require & snapshot bank info on withdrawals, fix `VoucherCampaignPublishedListener` SQL (`customers` instead of `users`) | `OBS-01` (optional UX sign-off) | PG tests: `CASH`-only voucher blocked on `WALLET` switch; fallback publishes `PaymentUpdated`; campaign publish delivers notifications without HTTP 500. |

## Batch 5 — P2 Pool Exhaustion, Concurrency, Privacy & Moderation
| Order | IDs | Scope | Dependency | Mandatory Regression Tests |
|---:|---|---|---|---|
| **5.1** | **`F-013`** (P2), **`F-021`** (P2), **`F-029`** (P2), **`F-047`** (P2) | Move Goong (`createRide`/`estimateRide`), Modal (`faceComparison`), and FCM (`RideRatingService#rate`) HTTP I/O **outside** `@Transactional`; remove nested `REQUIRES_NEW` inside `listAvailable(readOnly=true)` | None | Tests verifying no DB tx active during external HTTP mocks; `rate()` rollback fires 0 notifications. |
| **5.2** | **`F-035`** (P2), **`F-046`** (P2), **`F-048`** (P2), **`F-037`** (P2), **`F-038`** (P2), **`F-003`** (P2), **`F-031`** (P2), **`F-015`** (P2) | Add `HAVING COUNT(DISTINCT customer_id) >= 5` on `/places/popular`; add `AND is_hidden = false` in `driverReviews`; lock ratee `FOR UPDATE` in `rate()`; lock & guard `CLOSED` in `sendUserMessage`; enforce claim + `TicketReplied` in legacy `SupportService#update`; lock `OfferReleaseService`; skip `INACTIVE` in `ScheduledRideScheduler`; align `application-production.properties` Swagger disabling | None | PG tests for each invariant (k-anonymity, hidden review filter, 2-thread rating avg, `CLOSED` ticket reply `409`, legacy PATCH `409`, prod profile Swagger 404). |

## Batch 6 — P3 Hardening, Performance & Auditability
| Order | IDs | Scope | Dependency |
|---:|---|---|---|
| **6.1** | **`F-026`**, **`F-028`**, **`F-032`**, **`F-034`**, **`F-042`**, **`F-049`**, **`F-050`**, **`F-051`** | Forward-headers config, `TicketService` → `CloudinaryAssetService`, `AdminRatingService` `Asia/Ho_Chi_Minh` TZ, GPS ingest throttle/dedup, `estimateRide` geofence, rating push anonymity, prod CORS cleanup, `timestamptz` migration. | None |
| **6.2** | **`F-008`**, **`F-022`**, **`F-036`**, **`F-039`**, **`F-040`**, **`F-041`**, **`F-043`**, **`F-045`** | Legacy wallet filter, eKYC error envelope, admin reassigned-chat list, ticket N+1 batching, ticket pagination, ticket `client_message_id`, `rides.pricing_rule_id`/breakdown, vehicle archive image cleanup. | `OBS-13`/`OBS-14` for optional scope |

## Cập nhật sau đợt sửa autonomous

Danh mục trạng thái hiện hành nằm trong [FINDINGS.md](FINDINGS.md). Các batch trên là kế hoạch tại baseline `5d31630`. Đợt sửa sau đã hoàn thành 18 finding mới. F-018 và thời hạn đặt trước của F-030 còn quyết định sản phẩm. F-026 cần xác minh cấu hình ingress, F-045 cần quản lý vòng đời tài sản dùng chung, F-051 cần nguồn gốc múi giờ lịch sử. Bằng chứng hồi quy và thứ tự kiểm tra trước phát hành nằm trong [báo cáo hoàn thành](BACKEND-AUTONOMOUS-COMPLETION-REPORT.md).
