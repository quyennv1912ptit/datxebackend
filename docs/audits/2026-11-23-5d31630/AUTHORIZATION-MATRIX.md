# PtitGo Backend Authorization Coverage Matrix (192 HTTP Routes + STOMP + Ops)

- **Run ID**: `2026-11-23-5d31630` | **Commit**: `5d316306e7fe07514e295b6b5fcb5ff9725c257a`
- **Filter Chain**: `FirebaseSecurityConfig.kt:34-84` (Stateless, `@EnableMethodSecurity`, `FirebaseAuthFilter` before `UsernamePasswordAuthenticationFilter`, `anyRequest().authenticated()`).
- **Legend**: `F` = `FirebaseSecurityConfig` requestMatcher, `C` = `@PreAuthorize`, `S` = Service ownership/role check, `R` = Repository/SQL owner filter (`WHERE ..._id = actor.id`).

---

## 1. Public & Operational Entrypoints

| Endpoint | Method | AuthN | Required Role | Ownership | Controller Guard | Service Guard | Repo Guard | Test | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| `/api/auth/{customer,driver}/login`, `/register`, `/api/auth/admin/login` (5) | POST | Firebase ID token in body (`checkRevoked=true`) | Role-specific table (`ensureUidAvailableFor`) | Self UID | `permitAll` (`F`) | `AuthService` verifies token + exclusive admin UID | `findByFirebaseUid` | Partial | **PASS** |
| `/actuator/health`, `/actuator/health/**`, `/actuator/info` | GET | None | Public | N/A | `permitAll` (`F`) | N/A | N/A | None | **ISSUE (`F-015`)** — `show-details=never` only in `prod`, not `production` |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | GET | None | Public | N/A | `permitAll` (`F`) | N/A | N/A | None | **ISSUE (`F-015`)** — enabled in default `production` profile |
| `/ws-location`, `/ws-location/**` (HTTP handshake) | GET | STOMP `CONNECT` Bearer token | `CUSTOMER`, `DRIVER` (`ADMIN` rejected) | `StompPrincipal` (`customer:{id}` / `driver:{id}`) | `permitAll` (`F`) + `StompAuthInterceptor` | `FirebaseAuthTokenVerifier` (no `checkRevoked`) | `findAuthenticatedUser` | None | **BROKEN (`F-014`, `F-016`)** — `SEND` destination unrestricted + no revocation/session recheck |

---

## 2. WebSocket STOMP Commands & Destinations

| STOMP Frame / Destination | AuthN | Required Role | Ownership | Interceptor Guard | Handler Guard | Test | Verdict |
|---|---|---|---|---|---|---|---|
| `CONNECT` | Bearer token (`Authorization` header) | `CUSTOMER`, `DRIVER` | Self `StompPrincipal` | `StompAuthInterceptor#authenticate` | `FirebaseAuthTokenVerifier` (no `checkRevoked`) | None | **ISSUE (`F-016`)** |
| `SUBSCRIBE /user/queue/**` | Session `StompPrincipal` | `CUSTOMER`, `DRIVER` | Spring `UserDestinationMessageHandler` binds to own `principal.name` | `requireAuthenticated` + `startsWith("/user/queue/")` | Broker per-user rewrite | None | **PASS** (cross-user subscribe blocked; open session persists after ban → `F-016`) |
| `SEND /app/location` | Session `StompPrincipal` | `DRIVER` | Self `driver.id` | `requireAuthenticated` | `CoordinateController` requires `Recipient.Driver` | None | **PASS** (for `/app/location`) |
| `SEND /user/{victim}/queue/**` or `/topic/**` or `/queue/**` | Session `StompPrincipal` | **NONE (any authenticated user!)** | **NONE** | **`StompAuthInterceptor.kt:38` does NOT restrict `SEND` destination!** | Bypasses `@MessageMapping` straight to `UserDestinationMessageHandler` / SimpleBroker | None | **BROKEN (`F-014` P1)** |

---

## 3. Authenticated HTTP Endpoints (187 Protected + 5 Public Auth = 192 Routes)

| Module / Route Group (Count) | Methods | AuthN | Required Role | Resource Ownership | Controller Guard | Service Guard | Repo Guard | Test | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **`auth` self**: `/api/auth/me`, `/logout`, `/account` (3) | GET/POST/DELETE | Bearer | Any authenticated | Self `actor.id` | `anyRequest().authenticated()` (`F`) | Uses `@CurrentUser actor` | Scoped to `actor.id` | `DriverOnlineVehicleTypeTests` | **PASS** (`F-016` revocation window) |
| **`admin`**: `/api/admin/{customers,dashboard,drivers,ratings,rides}/**` (20) | GET/POST/PUT | Bearer | `ADMIN` | Admin-wide | `hasRole('ADMIN')` (`F`+`C`) | `requireAdmin(actor)` | Full admin queries | `AdminRideCapabilitiesHttpTests`; PG skipped | **PASS** |
| **`chat`**: `/api/chat/rides/{rideId}/{messages,read}` (3) | GET/POST/PUT | Bearer | `CUSTOMER`, `DRIVER` | Ride participant (`customerId` or `driverId`) | `hasAnyRole` (`F`+`C`) | `ConversationService#requireParticipant` | Scoped to `(rideId, driverId)` | `ChatServiceTests` | **PASS** |
| **`customer`**: `/api/customer/{profile,places}/**` (6) | GET/POST/PUT | Bearer | `CUSTOMER` | Self `actor.id` | `hasRole('CUSTOMER')` (`F`+`C`) | Uses `actor.id` | `WHERE customer_id = actor.id` | None | **PASS** |
| **`driver` profile & onboarding**: `/api/driver/{verification,profile/**}` (4) | GET/POST/PUT | Bearer | `DRIVER` | Self `actor.id` | `hasRole('DRIVER')` (`F`+`C`) | Uses `actor.id` | `WHERE driver_id = actor.id` | `DriverBankProfileTests` | **PASS** |
| **`driver/verification` capabilities**: `/api/driver/capabilities/**` (2) & `/api/admin/vehicle-catalog/applications/{id}/capabilities` (1) | GET/PUT | Bearer | `DRIVER` / `ADMIN` | Self `actor.id` / Admin | `hasRole` (`F`+`C`) | Scoped to `actor` | Scoped to `driver_id` | `DriverCapabilitiesTest` | **PASS** |
| **`driver/verification` workflow (driver)**: `/ekyc/driver-verification/v1/{me,applications/**}` & `/api/driver/verification/v1/**` (26) | GET/POST/PUT | Bearer | `DRIVER` (`me`: `DRIVER`/`ADMIN`) | `row.ownerUid == actor.firebaseUid` (`:1838`) | `hasRole` (`F`+`C`) | `ownerUid == actor.firebaseUid`; `resolveSafe` blocks path traversal; `Cache-Control: no-store, private` | `WHERE owner_uid = ?` | `DriverVerificationWorkflowTest` | **PASS** |
| **`driver/verification` workflow (admin)**: `/ekyc/driver-verification/v1/admin/**` & `/api/admin/verification/v1/**` (12) | GET/POST | Bearer | `ADMIN` | Admin review (`review=true`) | `hasRole('ADMIN')` (`F`+`C`) | `review = true` | Admin queries | `DriverVerificationWorkflowTest` | **PASS** |
| **`ekyc` extract**: `/api/driver/ekyc/documents/{slot}/extract` (1, conditional) | POST | Bearer | `DRIVER` | Self (stateless OCR proxy) | `hasRole('DRIVER')` (`F`) | Rate-limited per `firebaseUid` | N/A | `EkycClientTest` | **PASS** |
| **`matching`**: `/api/driver/offers/{pending,{rideId}/accept,{rideId}/reject}` (3) | GET/POST | Bearer | `DRIVER` | Offer's `driverId == actor.id` | `hasRole('DRIVER')` (`F`+`C`) | `offer.driverId == driverId` + Lua + SQL | `WHERE driver_id = ?` | `SequentialDispatchTests` | **PASS** |
| **`notification`**: `/api/notifications/**` (4), `/api/device-tokens/**` (2) | GET/POST/PUT/DELETE | Bearer | `CUSTOMER`, `DRIVER` | Own notifications / tokens | `hasAnyRole` (`F`) | `NotificationService` & `DeviceTokenService` reject `ADMIN` | `WHERE recipient_*_id = actor.id` / `ownerId = actor.id` | `NotificationHistoryMappingTests` | **PASS** |
| **`payment`**: `/api/driver/rides/{id}/payment/cash/confirm` (1), `/api/customer/rides/{id}/payment/method` (1), `/api/driver/wallet/**` (3), `/api/wallet/**` (3), `/api/driver/withdrawals/**` (3), `/api/admin/withdrawals/**` (2), mock top-up (3, dev/test/demo) | GET/POST/PUT | Bearer | Role-scoped per path | Ride `driverId`/`customerId`; own wallets & withdrawals | `hasRole`/`hasAnyRole` (`F`+`C`) | `ride.driverId == actor.id`, `ride.customerId == actor.id`, `request.driverId == driverId` | Scoped to `actor.id` | `CustomerWalletEndpointsTests`, `WalletPostgresTests` (skipped) | **PASS** |
| **`pricing`**: `/api/admin/pricing-rules/**` (5) | GET/POST/PUT | Bearer | `ADMIN` | Admin-wide | `hasRole('ADMIN')` (`F`+`C`) | Admin service | Admin table | `PricingRideQuoteTests` | **PASS** |
| **`rating`**: `/api/{customer,driver}/rides/{id}/{rating*,*-reviews}` & `/ratings/received` & `/driver/profile/reviews` (9) | GET/POST | Bearer | `CUSTOMER`, `DRIVER` | Ride `customerId`/`driverId` (`RideRatingService.kt:36,63,105,121`) | `hasRole`/`hasAnyRole` (`F`+`C`) | Verifies ride ownership + direction | `WHERE ratee_id = ?` / `ride_id = ?` | `CustomerReviewsTests` | **PASS** |
| **`ride` core**: `/api/customer/rides/**` (8), `/api/driver/rides/**` (9), handover (4), corrections (4) | GET/POST | Bearer | `CUSTOMER` / `DRIVER` | `RideQueryService.kt:72-79`, `RideStateMachine.kt:373-406`, `HandoverCorrectionService.kt:81` | `hasRole` (`F`; dropoff confirm also `C`) | Checks `ride.customerId == actor.id` / `ride.driverId == actor.id` | `RideSpecifications.forUser(actor.id, actor.role)` | `RideCancellationHttpTests`, `HandoverCorrectionTests` | **PASS** |
| **`routing`**: `/api/location/{geocode,directions,places/**}` (6) | GET | Bearer | `CUSTOMER`, `DRIVER` | Stateless map proxy | `hasAnyRole` (`F`) | Rate-limited per actor | Cache only | `PlaceSearchTests`, `VehicleRoutingTests` | **PASS** |
| **`support` legacy**: `/api/support/tickets/**` (3), `/api/admin/support/tickets/**` (3) | GET/POST/PATCH | Bearer | `CUSTOMER`/`DRIVER` (`create`), any auth (`list`/`detail` scoped), `ADMIN` (`/api/admin/**` + `PATCH`) | `ticket.ownerId == actor.id && ticket.ownerRole == actor.role.name` (`SupportService.kt:65-70,81`) | `/api/admin/**` `F` + `PATCH`/`POST` `C` | Enforces owner filter for non-ADMIN (`404` on foreign ID) | `WHERE owner_id=? AND owner_role=?` | `SupportPersistenceTests` (skipped) | **PASS** |
| **`support/ticket` threaded**: `/api/tickets/**` (5), `/api/admin/tickets/**` (4) | GET/POST | Bearer | `CUSTOMER`/`DRIVER` vs `ADMIN` | Delegates ownership to `SupportService#detail` (`TicketService.kt:50,56,63`) | `hasAnyRole` / `hasRole('ADMIN')` (`F`+`C`) | `support.detail(actor, id)` | Scoped | `TicketServiceTests` | **PASS** |
| **`tracking`**: `/api/driver/{location,availability}` (2), `/api/location/{nearby-drivers,customer/nearby-drivers}` (2) | GET/POST/PUT | Bearer | `DRIVER` / `ADMIN` / `CUSTOMER` | Self `actor.id` / quantized markers for customer | `hasRole` (`F`+`C`) | Rate-limited customer nearby (rounded 3 decimals, no driver IDs) | Redis | `DriverOnlineVehicleTypeTests` | **PASS** |
| **`uploads`**: `/api/uploads/signature` (1) | GET | Bearer | `CUSTOMER`, `DRIVER`, `ADMIN` | `CHAT`/`HANDOVER`: `conversationService.requireParticipant`; `SUPPORT`/`DEFAULT`: `CloudinaryFolders.forActor(actor)` | `hasAnyRole` (`F`) | `UploadSignatureService.kt:19-49` | N/A | None | **PASS** |
| **`vehicle`**: `/api/customer/vehicles/**` (7), `/api/vehicle-catalog` (1), `/api/admin/vehicle-catalog/license-preview` (1) | GET/POST/PUT/DELETE | Bearer | `CUSTOMER` / authenticated / `ADMIN` | `VehicleService` passes `actor.id` on every CRUD & image call | `hasRole` (`F`+`C`) / `authenticated` (`F`) | `requireCustomer(actor)` + `lockOwnedVehicle(vehicleId, actor.id)` | `WHERE id=? AND customer_id=?` | `GarageBookingRequirementTest` | **PASS** |
| **`voucher`**: `/api/admin/vouchers/**` (7), `/api/customer/vouchers/**` (3) | GET/POST/PUT/PATCH | Bearer | `ADMIN` / `CUSTOMER` | `VoucherService` checks `requireCustomer(actor)` + `owner_customer_id = actor.id` | `hasRole` (`F` + admin `C`) | `requireCustomer` / `requireAdmin` | `WHERE owner_customer_id = ?` | `VoucherServiceTests` | **PASS** |

---

**Total Coverage**: **192 / 192 HTTP routes** + **STOMP `/ws-location` (`CONNECT`/`SUBSCRIBE`/`SEND`)** + **Actuator/Swagger** traced across `Filter → Controller → Service → Repository`.
