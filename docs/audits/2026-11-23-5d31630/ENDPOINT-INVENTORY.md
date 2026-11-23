# PtitGo Backend Endpoint, Worker & Integration Inventory

- **Run ID**: `2026-11-23-5d31630`
- **Totals**: **192** HTTP controller route mappings + **1** STOMP inbound mapping (`/app/location` over `/ws-location`) + **2** STOMP user queues + **6** active `@Scheduled` jobs (+ **1** conditional) + **11** Spring event listener methods + **5** external integrations.

> Every entrypoint below is mapped to a Phase 1 (`1.01–1.21`) and/or Phase 2 (`2.01–2.10`) audit task.

---

## 1. HTTP REST Endpoints (192 Routes)

### 1.1 `admin` (20 routes) — Tasks: `1.17`, `1.18`, `1.07`, `1.08`, `1.16`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 1 | GET | `/api/admin/customers` | `hasRole('ADMIN')` | N | `AdminCustomerController#list` | `1.18` |
| 2 | GET | `/api/admin/customers/{id}` | `hasRole('ADMIN')` | N | `AdminCustomerController#detail` | `1.18` |
| 3 | GET | `/api/admin/customers/{id}/rides` | `hasRole('ADMIN')` | N | `AdminCustomerController#rides` | `1.18` |
| 4 | GET | `/api/admin/customers/{id}/wallet-transactions` | `hasRole('ADMIN')` | N | `AdminCustomerController#walletTransactions` | `1.18` |
| 5 | PUT | `/api/admin/customers/{id}/status` | `hasRole('ADMIN')` | Y | `AdminCustomerController#updateStatus` | `1.18` |
| 6 | PUT | `/api/admin/customers/{id}/flag` | `hasRole('ADMIN')` | Y | `AdminCustomerController#updateFlag` | `1.18` |
| 7 | GET | `/api/admin/dashboard` | `hasRole('ADMIN')` | N | `AdminDashboardController#summary` | `1.18` |
| 8 | GET | `/api/admin/drivers` | `hasRole('ADMIN')` | N | `AdminDriverController#listDrivers` | `1.18` |
| 9 | GET | `/api/admin/drivers/{id}` | `hasRole('ADMIN')` | N | `AdminDriverController#driverDetail` | `1.18` |
| 10 | POST | `/api/admin/drivers/{id}/force-offline` | `hasRole('ADMIN')` | N | `AdminDriverController#forceOffline` | `1.18`, `1.07` |
| 11 | GET | `/api/admin/ratings` | `hasRole('ADMIN')` | N | `AdminRatingController#list` | `1.16`, `1.18` |
| 12 | GET | `/api/admin/ratings/{id}` | `hasRole('ADMIN')` | N | `AdminRatingController#detail` | `1.16`, `1.18` |
| 13 | PUT | `/api/admin/ratings/{id}/visibility` | `hasRole('ADMIN')` | Y | `AdminRatingController#updateVisibility` | `1.16`, `1.18` |
| 14 | GET | `/api/admin/rides` | `hasRole('ADMIN')` | Y | `AdminRideController#listRides` | `1.18` |
| 15 | GET | `/api/admin/rides/filter-capabilities` | `hasRole('ADMIN')` | N | `AdminRideController#filterCapabilities` | `1.18` |
| 16 | GET | `/api/admin/rides/{id}` | `hasRole('ADMIN')` | N | `AdminRideController#getRideDetail` | `1.18` |
| 17 | GET | `/api/admin/rides/{id}/chat` | `hasRole('ADMIN')` | N | `AdminRideController#chatHistory` | `1.18`, `1.19` |
| 18 | POST | `/api/admin/rides/{id}/assignable-drivers` | `hasRole('ADMIN')` | N | `AdminRideController#assignableDrivers` | `1.17`, `1.07` |
| 19 | POST | `/api/admin/rides/{id}/assign` | `hasRole('ADMIN')` | Y | `AdminRideController#assignDriver` | `1.17`, `1.07` |
| 20 | POST | `/api/admin/rides/{id}/cancel` | `hasRole('ADMIN')` | Y | `AdminRideController#cancelRide` | `1.17`, `1.08` |

### 1.2 `auth` (8 routes) — Tasks: `1.01`, `1.02`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 21 | POST | `/api/auth/customer/login` | `(filter-only)` | Y | `AuthController#customerLogin` | `1.01` |
| 22 | POST | `/api/auth/customer/register` | `(filter-only)` | Y | `AuthController#customerRegister` | `1.01` |
| 23 | POST | `/api/auth/driver/login` | `(filter-only)` | Y | `AuthController#driverLogin` | `1.01` |
| 24 | POST | `/api/auth/driver/register` | `(filter-only)` | Y | `AuthController#driverRegister` | `1.01` |
| 25 | POST | `/api/auth/admin/login` | `(filter-only)` | Y | `AuthController#adminLogin` | `1.01` |
| 26 | GET | `/api/auth/me` | `(filter-only)` | N | `AuthController#me` | `1.01`, `1.02` |
| 27 | POST | `/api/auth/logout` | `(filter-only)` | Y | `AuthController#logout` | `1.01` |
| 28 | DELETE | `/api/auth/account` | `(filter-only)` | N | `AuthController#deleteAccount` | `1.01`, `1.02` |

### 1.3 `chat` (3 routes) — Task: `1.19`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 29 | GET | `/api/chat/rides/{rideId}/messages` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `ChatController#messages` | `1.19` |
| 30 | POST | `/api/chat/rides/{rideId}/messages` | `hasAnyRole('CUSTOMER','DRIVER')` | Y | `ChatController#send` | `1.19` |
| 31 | PUT | `/api/chat/rides/{rideId}/read` | `hasAnyRole('CUSTOMER','DRIVER')` | Y | `ChatController#markRead` | `1.19` |

### 1.4 `customer` (6 routes) — Task: `1.21`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 32 | GET | `/api/customer/places/recent` | `hasRole('CUSTOMER')` | N | `CustomerPlaceController#recent` | `1.21` |
| 33 | GET | `/api/customer/places/popular` | `hasRole('CUSTOMER')` | N | `CustomerPlaceController#popular` | `1.21` |
| 34 | POST | `/api/customer/places/report` | `hasRole('CUSTOMER')` | Y | `CustomerPlaceController#report` | `1.21` |
| 35 | GET | `/api/customer/profile` | `hasRole('CUSTOMER')` | N | `CustomerProfileController#get` | `1.21` |
| 36 | PUT | `/api/customer/profile` | `hasRole('CUSTOMER')` | Y | `CustomerProfileController#update` | `1.21` |
| 37 | POST | `/api/customer/profile/avatar` | `hasRole('CUSTOMER')` | Y | `CustomerProfileController#updateAvatar` | `1.21` |

### 1.5 `driver` & `driver/verification` (45 routes) — Tasks: `1.03`, `1.04`, `1.21`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 38 | GET | `/api/driver/verification` | `hasRole('DRIVER')` | N | `DriverOnboardingController#verification` | `1.03` |
| 39 | GET | `/api/driver/profile` | `hasRole('DRIVER')` | N | `DriverProfileController#getProfile` | `1.21` |
| 40 | PUT | `/api/driver/profile/identity` | `hasRole('DRIVER')` | Y | `DriverProfileController#updateIdentity` | `1.21`, `1.03` |
| 41 | POST | `/api/driver/profile/avatar` | `hasRole('DRIVER')` | Y | `DriverProfileController#updateAvatar` | `1.21` |
| 42 | GET | `/api/driver/capabilities` | `hasRole('DRIVER')` | N | `DriverCapabilityController#own` | `1.04` |
| 43 | PUT | `/api/driver/capabilities/{groupId}/preference` | `hasRole('DRIVER')` | Y | `DriverCapabilityController#preference` | `1.04` |
| 44 | GET | `/api/admin/vehicle-catalog/applications/{applicationId}/capabilities` | `hasRole('ADMIN')` | N | `DriverCapabilityController#admin` | `1.04` |
| 45–46 | GET | `/ekyc/driver-verification/v1/me` & `/api/driver/verification/v1/me` | `hasAnyRole('DRIVER','ADMIN')` | N | `DriverVerificationWorkflowController#me` | `1.03` |
| 47–48 | POST | `/ekyc/driver-verification/v1/applications` & `/api/driver/verification/v1/applications` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#create` | `1.03` |
| 49–50 | GET | `/ekyc/driver-verification/v1/applications` & `/api/driver/verification/v1/applications` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#listMine` | `1.03` |
| 51–52 | GET | `/ekyc/driver-verification/v1/applications/{applicationId}` & `/api/driver/verification/v1/applications/{applicationId}` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#detail` | `1.03` |
| 53–54 | PUT | `/ekyc/driver-verification/v1/applications/{applicationId}/captures/{slot}` & `/api/driver/verification/v1/applications/{applicationId}/captures/{slot}` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#replaceCapture` | `1.03` |
| 55–56 | GET | `/ekyc/driver-verification/v1/applications/{applicationId}/captures/{slot}` & `/api/driver/verification/v1/applications/{applicationId}/captures/{slot}` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#capturePreview` | `1.03` |
| 57–58 | POST | `/ekyc/driver-verification/v1/applications/{applicationId}/extract/{slot}` & `/api/driver/verification/v1/applications/{applicationId}/extract/{slot}` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#extract` | `1.03` |
| 59–60 | POST | `/ekyc/driver-verification/v1/applications/{applicationId}/qr-scan` & `/api/driver/verification/v1/applications/{applicationId}/qr-scan` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#qrScan` | `1.03` |
| 61–62 | PUT | `/ekyc/driver-verification/v1/applications/{applicationId}/confirmation` & `/api/driver/verification/v1/applications/{applicationId}/confirmation` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#confirm` | `1.03` |
| 63–64 | POST | `/ekyc/driver-verification/v1/applications/{applicationId}/face-quality` & `/api/driver/verification/v1/applications/{applicationId}/face-quality` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#faceQuality` | `1.03` |
| 65–66 | POST | `/ekyc/driver-verification/v1/applications/{applicationId}/submit` & `/api/driver/verification/v1/applications/{applicationId}/submit` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#submit` | `1.03` |
| 67–68 | POST | `/ekyc/driver-verification/v1/applications/{applicationId}/reverify` & `/api/driver/verification/v1/applications/{applicationId}/reverify` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#reverify` | `1.03` |
| 69–70 | POST | `/ekyc/driver-verification/v1/applications/{applicationId}/cancel-reverify` & `/api/driver/verification/v1/applications/{applicationId}/cancel-reverify` | `hasRole('DRIVER')` | N | `DriverVerificationWorkflowController#cancelReverify` | `1.03` |
| 71–72 | GET | `/ekyc/driver-verification/v1/admin/applications` & `/api/admin/verification/v1/applications` | `hasRole('ADMIN')` | N | `DriverVerificationWorkflowController#adminList` | `1.03` |
| 73–74 | GET | `/ekyc/driver-verification/v1/admin/review-queue` & `/api/admin/verification/v1/review-queue` | `hasRole('ADMIN')` | N | `DriverVerificationWorkflowController#reviewQueue` | `1.03` |
| 75–76 | GET | `/ekyc/driver-verification/v1/admin/applications/{applicationId}` & `/api/admin/verification/v1/applications/{applicationId}` | `hasRole('ADMIN')` | N | `DriverVerificationWorkflowController#adminDetail` | `1.03` |
| 77–78 | GET | `/ekyc/driver-verification/v1/admin/applications/{applicationId}/captures/{slot}` & `/api/admin/verification/v1/applications/{applicationId}/captures/{slot}` | `hasRole('ADMIN')` | N | `DriverVerificationWorkflowController#adminCapture` | `1.03` |
| 79–80 | POST | `/ekyc/driver-verification/v1/admin/applications/{applicationId}/face-comparison` & `/api/admin/verification/v1/admin/applications/{applicationId}/face-comparison` *(canonical: `/api/admin/verification/v1/applications/{applicationId}/face-comparison`)* | `hasRole('ADMIN')` | N | `DriverVerificationWorkflowController#faceComparison` | `1.03` |
| 81–82 | POST | `/ekyc/driver-verification/v1/admin/applications/{applicationId}/decision` & `/api/admin/verification/v1/applications/{applicationId}/decision` | `hasRole('ADMIN')` | N | `DriverVerificationWorkflowController#decision` | `1.03` |

*(Exact count in section 1.5: 7 single-path routes + 19 dual-path routes × 2 = 45 routes.)*

### 1.6 `ekyc` (1 conditional route) — Task: `1.03`
| # | Method | Path | Security | Condition | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 83 | POST | `/api/driver/ekyc/documents/{slot}/extract` | `(filter-only)` | `app.ekyc.enabled=true` | `EkycController#extract` | `1.03` |

### 1.7 `matching` (3 routes) — Task: `1.07`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 84 | GET | `/api/driver/offers/pending` | `hasRole('DRIVER')` | N | `OfferController#pendingOffer` | `1.07` |
| 85 | POST | `/api/driver/offers/{rideId}/accept` | `hasRole('DRIVER')` | N | `OfferController#driverAcceptedOffer` | `1.07` |
| 86 | POST | `/api/driver/offers/{rideId}/reject` | `hasRole('DRIVER')` | N | `OfferController#driverRejectedOffer` | `1.07` |

### 1.8 `notification` (6 routes) — Task: `1.16`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 87 | GET | `/api/notifications` | `(filter-only)` | N | `NotificationHistoryController#list` | `1.16` |
| 88 | GET | `/api/notifications/unread-count` | `(filter-only)` | N | `NotificationHistoryController#unreadCount` | `1.16` |
| 89 | PUT | `/api/notifications/{id}/read` | `(filter-only)` | N | `NotificationHistoryController#markRead` | `1.16` |
| 90 | PUT | `/api/notifications/read-all` | `(filter-only)` | N | `NotificationHistoryController#markAllRead` | `1.16` |
| 91 | POST | `/api/device-tokens` | `(filter-only)` | Y | `DeviceTokenController#addDeviceToken` | `1.16` |
| 92 | DELETE | `/api/device-tokens/{installationId}` | `(filter-only)` | N | `DeviceTokenController#deleteDeviceToken` | `1.16` |

### 1.9 `payment` (16 routes) — Tasks: `1.13`, `1.15`
| # | Method | Path | Security Annotation | `@Valid` | Condition | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|---|
| 93 | POST | `/api/driver/rides/{rideId}/payment/cash/confirm` | `hasRole('DRIVER')` | N | — | `CashPaymentController#confirmCashReceived` | `1.13` |
| 94 | PUT | `/api/customer/rides/{rideId}/payment/method` | `hasRole('CUSTOMER')` | N | — | `CustomerPaymentController#change` | `1.13` |
| 95 | GET | `/api/driver/wallet/credit-status` | `hasRole('DRIVER')` | N | — | `DriverWalletController#creditStatus` | `1.15` |
| 96 | GET | `/api/driver/wallet/daily-summary` | `hasRole('DRIVER')` | N | — | `DriverWalletController#dailySummary` | `1.15` |
| 97 | GET | `/api/driver/wallet` | `hasRole('DRIVER')` | N | — | `DriverWalletController#getWallet` | `1.15` |
| 98 | POST | `/api/driver/withdrawals` | `hasRole('DRIVER')` | N | — | `DriverWithdrawalController#create` | `1.15` |
| 99 | GET | `/api/driver/withdrawals/{id}` | `hasRole('DRIVER')` | N | — | `DriverWithdrawalController#get` | `1.15` |
| 100 | GET | `/api/driver/withdrawals` | `hasRole('DRIVER')` | N | — | `DriverWithdrawalController#list` | `1.15` |
| 101 | GET | `/api/admin/withdrawals` | `hasRole('ADMIN')` | N | — | `AdminWithdrawalController#list` | `1.15` |
| 102 | POST | `/api/admin/withdrawals/{id}/review` | `hasRole('ADMIN')` | N | — | `AdminWithdrawalController#review` | `1.15` |
| 103 | POST | `/api/mock/wallet/top-up` | `hasRole('ADMIN')` | Y | `dev/test/demo` + `wallet.mock-topup-enabled=true` | `MockWalletController#topUp` | `1.15` |
| 104 | GET | `/api/wallet/overview` | `hasAnyRole('DRIVER','CUSTOMER')` | N | — | `WalletQueryController#overview` | `1.15` |
| 105 | GET | `/api/wallet` | `hasAnyRole('DRIVER','CUSTOMER')` | N | — | `WalletQueryController#balances` | `1.15` |
| 106 | GET | `/api/wallet/transactions` | `hasAnyRole('DRIVER','CUSTOMER')` | N | — | `WalletQueryController#history` | `1.15` |
| 107 | POST | `/api/driver/wallet/top-up` | `hasRole('DRIVER')` | N | `dev/test/demo` + `wallet.mock-topup-enabled=true` | `DriverMockTopUpController#topUp` | `1.15` |
| 108 | POST | `/api/customer/wallet/top-up` | `hasRole('CUSTOMER')` | N | `dev/test/demo` + `wallet.mock-topup-enabled=true` | `CustomerMockTopUpController#topUp` | `1.15` |

### 1.10 `pricing` (5 routes) — Task: `1.05`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 109 | GET | `/api/admin/pricing-rules` | `hasRole('ADMIN')` | N | `AdminPricingRuleController#listRules` | `1.05` |
| 110 | POST | `/api/admin/pricing-rules` | `hasRole('ADMIN')` | Y | `AdminPricingRuleController#createRule` | `1.05` |
| 111 | PUT | `/api/admin/pricing-rules/{id}` | `hasRole('ADMIN')` | Y | `AdminPricingRuleController#updateRule` | `1.05` |
| 112 | POST | `/api/admin/pricing-rules/{id}/activate` | `hasRole('ADMIN')` | N | `AdminPricingRuleController#activateRule` | `1.05` |
| 113 | POST | `/api/admin/pricing-rules/{id}/deactivate` | `hasRole('ADMIN')` | N | `AdminPricingRuleController#deactivateRule` | `1.05` |

### 1.11 `rating` (9 routes) — Task: `1.16`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 114 | GET | `/api/customer/rides/{id}/driver-reviews` | `hasRole('CUSTOMER')` | N | `RideRatingController#driverReviews` | `1.16` |
| 115 | GET | `/api/driver/profile/reviews` | `hasRole('DRIVER')` | N | `RideRatingController#myReviews` | `1.16` |
| 116 | GET | `/api/driver/rides/{id}/customer-reviews` | `hasRole('DRIVER')` | N | `RideRatingController#customerReviews` | `1.16` |
| 117 | POST | `/api/customer/rides/{id}/rating` | `hasAnyRole('CUSTOMER','DRIVER')` | Y | `RideRatingController#rate` | `1.16` |
| 118 | POST | `/api/driver/rides/{id}/rating` | `hasAnyRole('CUSTOMER','DRIVER')` | Y | `RideRatingController#rate` | `1.16` |
| 119 | GET | `/api/customer/ratings/received` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `RideRatingController#received` | `1.16` |
| 120 | GET | `/api/driver/ratings/received` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `RideRatingController#received` | `1.16` |
| 121 | GET | `/api/customer/rides/{id}/rating/me` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `RideRatingController#myRating` | `1.16` |
| 122 | GET | `/api/driver/rides/{id}/rating/me` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `RideRatingController#myRating` | `1.16` |

### 1.12 `ride` (25 routes) — Tasks: `1.05`, `1.06`, `1.08`, `1.09`, `1.10`, `1.11`, `1.12`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 123 | POST | `/api/customer/rides/{id}/handover/{phase}/corrections` | `(filter-only)` | N | `HandoverCorrectionController#request` | `1.11` |
| 124 | POST | `/api/driver/rides/{id}/handover/{phase}/revisions` | `(filter-only)` | N | `HandoverCorrectionController#revise` | `1.11` |
| 125 | GET | `/api/customer/rides/{id}/handover/{phase}/revisions` | `(filter-only)` | N | `HandoverCorrectionController#history` | `1.11` |
| 126 | GET | `/api/driver/rides/{id}/handover/{phase}/revisions` | `(filter-only)` | N | `HandoverCorrectionController#history` | `1.11` |
| 127 | POST | `/api/customer/rides/estimate` | `(filter-only)` | Y | `RideController#estimateRide` | `1.05` |
| 128 | GET | `/api/customer/rides/{id}` | `(filter-only)` | N | `RideController#getRideDetail` | `1.06`, `1.02` |
| 129 | GET | `/api/driver/rides/{id}` | `(filter-only)` | N | `RideController#getRideDetail` | `1.06`, `1.02` |
| 130 | GET | `/api/customer/rides/active` | `(filter-only)` | N | `RideController#getActiveRide` | `1.06` |
| 131 | GET | `/api/driver/rides/active` | `(filter-only)` | N | `RideController#getActiveRide` | `1.06` |
| 132 | GET | `/api/customer/rides` | `(filter-only)` | Y | `RideController#listMyRides` | `1.06` |
| 133 | GET | `/api/driver/rides` | `(filter-only)` | Y | `RideController#listMyRides` | `1.06` |
| 134 | POST | `/api/customer/rides` | `(filter-only)` | Y | `RideController#createRide` | `1.06`, `1.09` |
| 135 | POST | `/api/customer/rides/{id}/cancel` | `(filter-only)` | Y | `RideController#cancelRide` | `1.08` |
| 136 | POST | `/api/driver/rides/{id}/cancel` | `(filter-only)` | Y | `RideController#cancelRide` | `1.08` |
| 137 | POST | `/api/driver/rides/{id}/handover/pickup` | `(filter-only)` | Y | `RideHandoverController#createPickupHandover` | `1.11` |
| 138 | POST | `/api/customer/rides/{id}/handover/pickup/confirm` | `(filter-only)` | Y | `RideHandoverController#confirmPickupHandover` | `1.11` |
| 139 | POST | `/api/driver/rides/{id}/handover/dropoff` | `(filter-only)` | Y | `RideHandoverController#createDropoffHandover` | `1.11` |
| 140 | POST | `/api/customer/rides/{id}/handover/dropoff/confirm` | `hasRole('CUSTOMER')` | Y | `RideHandoverController#confirmDropoffHandover` | `1.11` |
| 141 | POST | `/api/driver/rides/{id}/start-arriving` | `(filter-only)` | Y | `RideProgressController#startArriving` | `1.10` |
| 142 | POST | `/api/driver/rides/{id}/arrived` | `(filter-only)` | Y | `RideProgressController#arrivePickup` | `1.10` |
| 143 | POST | `/api/driver/rides/{id}/start` | `(filter-only)` | Y | `RideProgressController#startTrip` | `1.10` |
| 144 | POST | `/api/driver/rides/{id}/arrive-destination` | `(filter-only)` | Y | `RideProgressController#arriveDestination` | `1.10` |
| 145 | POST | `/api/driver/rides/{id}/complete` | `(filter-only)` | Y | `RideProgressController#completeRide` | `1.12` |
| 146 | GET | `/api/customer/rides/{id}/search-progress` | `(filter-only)` | N | `RideSearchProgressController#progress` | `1.07` |
| 147 | GET | `/api/customer/rides/{id}/approach-location` | `(filter-only)` | N | `RideSearchProgressController#location` | `1.10`, `1.21` |

### 1.13 `routing` (6 routes) — Task: `1.21`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 148 | GET | `/api/location/places/nearby` | `(filter-only)` | N | `PlaceNearbyController#nearby` | `1.21` |
| 149 | GET | `/api/location/places/search` | `(filter-only)` | N | `PlaceSearchController#search` | `1.21` |
| 150 | GET | `/api/location/places/detail` | `(filter-only)` | N | `PlaceSearchController#detail` | `1.21` |
| 151 | GET | `/api/location/places/reverse` | `(filter-only)` | N | `PlaceSearchController#reverse` | `1.21` |
| 152 | GET | `/api/location/geocode` | `(filter-only)` | N | `RoutingController#getCoordinates` | `1.21` |
| 153 | GET | `/api/location/directions` | `(filter-only)` | N | `RoutingController#getDirections` | `1.21`, `1.05` |

### 1.14 `support` & `support/ticket` (15 routes) — Task: `1.20`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 154 | POST | `/api/support/tickets` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `SupportController#create` | `1.20` |
| 155 | GET | `/api/support/tickets` | `(filter-only)` | N | `SupportController#list` | `1.20` |
| 156 | GET | `/api/admin/support/tickets` | `(filter-only)` | N | `SupportController#list` | `1.20` |
| 157 | GET | `/api/support/tickets/{id}` | `(filter-only)` | N | `SupportController#detail` | `1.20` |
| 158 | GET | `/api/admin/support/tickets/{id}` | `(filter-only)` | N | `SupportController#detail` | `1.20` |
| 159 | PATCH | `/api/admin/support/tickets/{id}` | `hasRole('ADMIN')` | N | `SupportController#update` | `1.20` |
| 160 | POST | `/api/tickets` | `hasAnyRole('CUSTOMER','DRIVER')` | Y | `TicketController#create` | `1.20` |
| 161 | GET | `/api/tickets` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `TicketController#list` | `1.20` |
| 162 | GET | `/api/tickets/{id}` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `TicketController#detail` | `1.20` |
| 163 | GET | `/api/tickets/{id}/messages` | `hasAnyRole('CUSTOMER','DRIVER')` | N | `TicketController#messages` | `1.20` |
| 164 | POST | `/api/tickets/{id}/messages` | `hasAnyRole('CUSTOMER','DRIVER')` | Y | `TicketController#sendMessage` | `1.20` |
| 165 | GET | `/api/admin/tickets` | `hasRole('ADMIN')` | N | `AdminTicketController#list` | `1.20` |
| 166 | GET | `/api/admin/tickets/{id}` | `hasRole('ADMIN')` | N | `AdminTicketController#detail` | `1.20` |
| 167 | POST | `/api/admin/tickets/{id}/claim` | `hasRole('ADMIN')` | N | `AdminTicketController#claim` | `1.20` |
| 168 | POST | `/api/admin/tickets/{id}/messages` | `hasRole('ADMIN')` | Y | `AdminTicketController#sendMessage` | `1.20` |

### 1.15 `tracking` (4 HTTP + STOMP) — Tasks: `1.07`, `1.21`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 169 | POST | `/api/driver/location` | `hasRole('DRIVER')` | N | `DriverLocationController#updateLocation` | `1.21` |
| 170 | PUT | `/api/driver/availability` | `hasRole('DRIVER')` | N | `DriverPresenceController#setAvailability` | `1.07`, `1.21` |
| 171 | GET | `/api/location/nearby-drivers` | `hasRole('ADMIN')` | N | `NearbyDriversController#findNearbyDrivers` | `1.21` |
| 172 | GET | `/api/location/customer/nearby-drivers` | `hasRole('CUSTOMER')` | N | `NearbyDriversController#findNearbyDriversForCustomer` | `1.21` |

### 1.16 `uploads` (1 route) — Task: `1.21`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 173 | GET | `/api/uploads/signature` | `(filter-only)` | N | `UploadSignatureController#getSignature` | `1.21` |

### 1.17 `vehicle` (9 routes) — Task: `1.04`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 174 | GET | `/api/vehicle-catalog` | `(filter-only)` | N | `VehicleCatalogController#read` | `1.04` |
| 175 | POST | `/api/admin/vehicle-catalog/license-preview` | `hasRole('ADMIN')` | Y | `VehicleCatalogController#preview` | `1.04` |
| 176 | GET | `/api/customer/vehicles` | `hasRole('CUSTOMER')` | N | `VehicleController#list` | `1.04` |
| 177 | POST | `/api/customer/vehicles` | `hasRole('CUSTOMER')` | Y | `VehicleController#create` | `1.04` |
| 178 | PUT | `/api/customer/vehicles/{id}/default` | `hasRole('CUSTOMER')` | N | `VehicleController#setDefault` | `1.04` |
| 179 | PUT | `/api/customer/vehicles/{id}` | `hasRole('CUSTOMER')` | Y | `VehicleController#update` | `1.04` |
| 180 | POST | `/api/customer/vehicles/{id}/images` | `hasRole('CUSTOMER')` | Y | `VehicleController#addImage` | `1.04` |
| 181 | DELETE | `/api/customer/vehicles/{id}/images/{imageId}` | `hasRole('CUSTOMER')` | N | `VehicleController#deleteImage` | `1.04` |
| 182 | DELETE | `/api/customer/vehicles/{id}` | `hasRole('CUSTOMER')` | N | `VehicleController#delete` | `1.04` |

### 1.18 `voucher` (10 routes) — Task: `1.14`
| # | Method | Path | Security Annotation | `@Valid` | Controller#Method | Phase 1 Task |
|---|---|---|---|---|---|---|
| 183 | GET | `/api/admin/vouchers` | `hasRole('ADMIN')` | N | `AdminVoucherController#list` | `1.14` |
| 184 | GET | `/api/admin/vouchers/{id}/claims` | `hasRole('ADMIN')` | N | `AdminVoucherController#listClaimants` | `1.14` |
| 185 | PATCH | `/api/admin/vouchers/{id}/status` | `hasRole('ADMIN')` | N | `AdminVoucherController#updateCampaignStatus` | `1.14` |
| 186 | GET | `/api/admin/vouchers/budget` | `hasRole('ADMIN')` | N | `AdminVoucherController#budget` | `1.14` |
| 187 | GET | `/api/admin/vouchers/budget/ledger` | `hasRole('ADMIN')` | N | `AdminVoucherController#budgetLedger` | `1.14` |
| 188 | PUT | `/api/admin/vouchers/budget` | `hasRole('ADMIN')` | Y | `AdminVoucherController#setBudget` | `1.14` |
| 189 | POST | `/api/admin/vouchers` | `hasRole('ADMIN')` | Y | `AdminVoucherController#create` | `1.14` |
| 190 | GET | `/api/customer/vouchers` | `(filter-only)` | N | `VoucherController#list` | `1.14` |
| 191 | GET | `/api/customer/vouchers/campaigns` | `(filter-only)` | N | `VoucherController#campaigns` | `1.14` |
| 192 | POST | `/api/customer/vouchers/campaigns/{id}/claim` | `(filter-only)` | N | `VoucherController#claim` | `1.14` |

---

## 2. WebSocket STOMP, Actuator & OpenAPI Surface

| Type | Endpoint / Destination | Auth / Exposure | Handler / Notes | Audit Task |
|---|---|---|---|---|
| STOMP Handshake | `/ws-location` | Firebase token on STOMP `CONNECT` | `WebSocketConfig` | `1.21`, `2.01` |
| STOMP Inbound | `/app/location` | Authenticated `DRIVER` | `CoordinateController#updateDriverLocation` | `1.21` |
| STOMP Outbound | `/user/queue/trip` | Per-user session | Offers & ride updates (`matching`, `ride`) | `1.07`, `1.21` |
| STOMP Outbound | `/user/queue/rides/{rideId}/location` | Per-user session | Live driver approach/trip GPS (`LocationService`) | `1.10`, `1.21` |
| Actuator | `/actuator/**` | Verify `SecurityConfig` & `application*.properties` | Health/info/metrics exposure | `2.01`, `2.10` |
| OpenAPI/Swagger | `/swagger-ui/**`, `/v3/api-docs/**` | Disabled in `application-prod.properties` (check `production` profile mismatch!) | `springdoc` | `2.01`, `2.10` |

---

## 3. Scheduled Background Jobs (6 Active + 1 Conditional)

| # | Class#Method | Schedule | Condition | Purpose | Audit Task |
|---|---|---|---|---|---|
| J1 | `AccountCleanupOutboxWorker#processPendingCleanup` | `fixedDelay` `1000ms` | Always | Retries Firebase/DB account cleanup outbox | `1.01`, `2.07` |
| J2 | `OfferTimeoutTask#handleExpiredOffers` | `fixedDelay` `1000ms` | Always | Expires timed-out offers & advances sequential dispatch | `1.07`, `2.07` |
| J3 | `RideDispatchOutboxWorker#dispatchPendingRides` | `fixedDelay` `1000ms` | Always | Claims pending dispatch outbox rows & triggers matching | `1.06`, `1.07`, `2.07` |
| J4 | `RideDispatchRecoveryTask#recoverLostDispatches` | `fixedDelay` `10000ms` | Always | Recovers `PENDING` immediate rides stuck >30s | `1.06`, `1.07`, `2.07` |
| J5 | `LocationService#cleanupStaleDrivers` | `fixedDelay` `10000ms` | Always | Evicts stale drivers from Redis `drivers:geo` | `1.07`, `1.21`, `2.07` |
| J6 | `ScheduledRideScheduler#processDueRides` | `fixedDelay` `30000ms` | Always | Dispatches due scheduled rides (`scheduled_at <= now + lead`) | `1.09`, `2.07` |
| J7 | `VerificationSync#reconcile` | `fixedDelay` `2000ms` | `app.verification-sync.enabled=true` | Syncs driver verification projection from external service | `1.03`, `2.07` |

---

## 4. Event Listeners (11 Handler Methods)

| Listener Class#Method | Phase / Async | Event | Audit Task |
|---|---|---|---|
| `PaymentService#onRideCompleted` | `BEFORE_COMMIT` (`MANDATORY`) | `RideCompleted` | `1.12`, `1.13`, `1.15` |
| `DriverReleaseService#onStatusChanged` | `AFTER_COMMIT` (`fallbackExecution=true`) | `RideStatusChanged` | `1.07`, `1.08`, `1.12` |
| `RideCancellationService#onStatusChanged` | `AFTER_COMMIT` (`fallbackExecution=true`) | `RideStatusChanged` | `1.08`, `1.07` |
| `RideNotificationListener#onRideStatusChanged` | `AFTER_COMMIT` (`fallbackExecution=true`) | `RideStatusChanged` | `1.16` |
| `RideNotificationListener#onPickupHandoverConfirmed` | `AFTER_COMMIT` (`fallbackExecution=true`) | `PickupHandoverConfirmed` | `1.11`, `1.16` |
| `RideNotificationListener#onDropoffHandoverConfirmed` | `AFTER_COMMIT` (`fallbackExecution=true`) | `DropoffHandoverConfirmed` | `1.11`, `1.16` |
| `PaymentNotificationListener#updated` | `AFTER_COMMIT` | `PaymentUpdated` | `1.13`, `1.16` |
| `WithdrawalNotificationListener#reviewed` | `AFTER_COMMIT` | `WithdrawalReviewed` | `1.15`, `1.16` |
| `TicketNotificationListener#replied` | `AFTER_COMMIT` | `TicketReplied` | `1.20`, `1.16` |
| `ChatPushListener#onMessageCreated` / `#onMessagesRead` | `@Async("chatTaskExecutor")` + `AFTER_COMMIT` | `ChatMessageCreated` / `ChatMessagesRead` | `1.19`, `1.16` |
| `VoucherGrantListener#onRideCompleted` | `AFTER_COMMIT` (`fallbackExecution=true`) | `RideCompleted` | `1.14`, `1.12` |
| `VoucherCampaignPublishedListener#onCampaignPublished` | `AFTER_COMMIT` (`fallbackExecution=true`) | `VoucherCampaignPublished` | `1.14`, `1.16` |
| `RideLifecycleEventListener#onNoDriverFound` | `@EventListener` (`@Transactional`) | `RideNoDriverFound` | `1.07`, `1.08` |

---

## 5. External Integrations (5 Active)

| Integration | Client / Usage | Config Properties | Non-Existent / N/A Note | Audit Task |
|---|---|---|---|---|
| **Firebase Auth & FCM** | `FirebaseAuthFilter`, `AuthService`, `FcmService` | `app.firebase.*` | — | `1.01`, `1.16`, `2.06` |
| **Goong Maps / Places (V1 & V2)** | `GoongClient`, `RoutingService`, `PlaceSearchService`, `PlaceNearbyService` | `goong.*` | Live test gated by `RUN_GOONG_LIVE_TEST=true` | `1.05`, `1.21`, `2.06` |
| **Modal eKYC HTTP Service** | `EkycClient`, `EkycController`, `DriverVerificationWorkflowService` | `app.ekyc.*` | Smoke test gated by `DATXE_EKYC_SMOKE=true` | `1.03`, `2.06`, `3.03` |
| **Cloudinary** | `CloudinarySignatureService` (local signing only) | `cloudinary.*` | Backend does not proxy binary uploads | `1.21`, `2.06` |
| **External Verification Sync** *(optional)* | `VerificationSync` | `app.verification-sync.*` | Disabled by default | `1.03`, `2.06`, `3.03` |
| **Live Payment Gateways (VNPay/MoMo)** | **NOT IMPLEMENTED** | — | `payment_transactions` table exists from `V1`, no gateway webhook/client; `VNPAY`/`MOMO` rejected in booking & method switch | `N/A` |
