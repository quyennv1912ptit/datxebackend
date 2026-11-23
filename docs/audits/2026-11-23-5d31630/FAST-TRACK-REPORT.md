# PtitGo Backend — Fast-Track Audit Summary (Phase 2.04–2.10 & Phase 3 Readiness)

- **Run ID**: `2026-11-23-5d31630` | **Branch**: `integration/backend-release` @ `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Mode**: `AUDIT + PLAN ONLY` (zero production edits; 3 test-only edits preserved; **51 confirmed findings**: `0 P0`, `9 P1`, `25 P2`, `17 P3`; `F-001` & `F-014` `CONFIRMED (runtime)`).

---

## 1. Phase 2.04–2.10 Fast Review Verdicts

| Task | Scope | Verdict | Key Verified Safeguards & Remaining Gaps |
|---|---|---|---|
| **`2.04`** | Concurrency & Idempotency | **`PARTIAL`** | Outboxes (`AccountCleanupOutboxRepository:48`, `RideDispatchOutboxRepository:50`) use `FOR UPDATE SKIP LOCKED`. Remaining known races: `F-003`, `F-009`, `F-024`, `F-037`, `F-048`, `F-041`. Multi-thread PG stress tests not yet written. |
| **`2.05`** | API Contracts & Legacy Parity | **`PARTIAL`** | `GlobalExceptionHandler` standardizes `ApiResponse` except `F-022` (eKYC flat envelope), `F-008` (legacy wallet mixed ledger), `F-038` (legacy support PATCH bypass), `F-036` (admin chat reassigned blind spot). |
| **`2.06`** | Schedulers, Outboxes & Listeners | **`PARTIAL`** | `SchedulingConfig` uses 4-thread `ThreadPoolTaskScheduler` for 7 jobs; `SKIP LOCKED` verified on both outboxes. Known issues: `F-031` (`ScheduledRideScheduler` dispatches `INACTIVE`), `F-033` (broken `users` SQL), `F-047` (sync push inside `@Transactional`). |
| **`2.07`** | External Integrations | **`PARTIAL`** | `EkycClient` timeouts (`5s/65s/75s`), `GoongDirectionsClient` cache, `CloudinaryAssetService` verified. Known issues: `F-021`/`F-029`/`F-047` (HTTP I/O inside `@Transactional`), `F-019` (ephemeral HMAC/no key rotation), `F-028`/`F-045`. |
| **`2.08`** | Performance & Pool Sizing | **`PARTIAL`** | HikariCP `max=10`, `lock_timeout=3s`. Known bottlenecks: `F-013` (nested `REQUIRES_NEW` pool deadlock), `F-021`/`F-029` (HTTP in TX), `F-034` (unthrottled GPS DB amplification), `F-039`/`F-040` (ticket N+1 & unbounded history). |
| **`2.09`** | Test Gaps | **`PARTIAL`** | 253/256 Gradle tests pass on `ptitgo_audit_test`. Gaps: Python Lua blocked (`liblua5.4`), 0 tests in `uploads.*`, 0 STOMP E2E tests in repo, 0 multi-thread PG tests for `F-024`/`F-037`/`F-048`/`F-010`. |
| **`2.10`** | Build & Deployment | **`PARTIAL`** | `docker-compose.production.yml` runs non-root UID/GID with persistent verification mount, **but** defaults `SPRING_PROFILES_ACTIVE=production` (`F-015`: Swagger exposed), lacks `server.forward-headers-strategy` (`F-026`), and hardcodes localhost CORS (`F-050`). |

> **New Findings**: `0` new P1/P2 findings beyond the 51 confirmed findings (`F-001`..`F-051`).

---

## 2. Phase 3 Cross-Repo Contract Checklist (`NOT VERIFIED` — Outside Active Workspace)

Sibling repos (`f:\datxe_rn`, `f:\goAdmin`, `f:\ekyc-service`) exist on disk outside active workspace `f:\datxebackend` and were **not read** (`NOT VERIFIED`). Verify these contracts before production release:
- **Customer & Driver Apps (`datxe_rn`)**:
  1. Auth: `X-Role` header on dual-role UIDs; handling `401` on revoked tokens (`F-016`) and `409 ACCOUNT_HAS_ACTIVE_RIDES` (`F-023`/`F-024`).
  2. STOMP: Subscribe only `/user/queue/trip` & `/user/queue/rides/{id}/location`; send only `/app/location` (`F-014`).
  3. Booking/Payment: `WALLET → CASH` fallback notification (`F-006`, `OBS-01`); `expectedPayableFare` / price change UX (`OBS-14`); eKYC flat error envelope (`F-022`).
  4. Chat/Tickets/Ratings: `clientMessageId` on ticket replies (`F-041`), `409` on replying to `CLOSED` ticket (`F-037`), pagination on `/tickets/{id}/messages` (`F-040`).
- **Admin Web (`goAdmin`)**:
  1. Retire or update legacy `PATCH /api/admin/support/tickets/{id}` (`F-038`); adopt `driverId`/`conversationId` selector on `/api/admin/rides/{id}/chat` (`F-036`).
  2. Align rating `from`/`to` date pickers to `Asia/Ho_Chi_Minh` (`F-032`); XSS-escape raw chat/ticket/rating text.
- **eKYC Service (`ekyc-service`)**:
  1. Timeout budget alignment (`5s/65s/75s`) once `faceComparison` is moved outside `@Transactional` (`F-021`).
  2. HMAC key persistence & rotation contract (`F-019`).

---

## 3. Release Blockers & Next Action

- **Production Release Readiness**: **`NOT READY`** — blocked by **9 P1 findings** (`F-014`, `F-001`, `F-017`, `F-023`, `F-024`, `F-004`, `F-020`, `F-002`, `F-010`) and **5 open product decisions** (`OBS-01`, `OBS-11`, `OBS-12`, `OBS-13`, `OBS-14`).
- **Next Step**: Execute dependency-ordered `FIX` batches defined in [FIX-PRIORITY.md](file:///f:/datxebackend/docs/audits/2026-11-23-5d31630/FIX-PRIORITY.md), starting with **Batch 1 (`F-014` + `F-001`)** using TDD (`RED` failing regression test → minimal fix → `GREEN`).
