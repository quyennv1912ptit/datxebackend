package com.nhom15.datxebackend.auth.service

import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import com.nhom15.datxebackend.admin.entity.Admin
import com.nhom15.datxebackend.admin.repository.AdminRepository
import com.nhom15.datxebackend.auth.dto.AuthLoginRequest
import com.nhom15.datxebackend.auth.dto.AuthRegisterRequest
import com.nhom15.datxebackend.auth.dto.AuthProfileResponse
import com.nhom15.datxebackend.auth.security.AuthenticatedUserCache
import com.nhom15.datxebackend.common.api.ErrorCode
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.common.security.AuthenticatedUserProvider
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.enums.UserStatus
import com.nhom15.datxebackend.common.exception.AppException
import com.nhom15.datxebackend.common.exception.BadRequestException
import com.nhom15.datxebackend.customer.entity.Customer
import com.nhom15.datxebackend.customer.repository.CustomerRepository
import com.nhom15.datxebackend.driver.entity.Driver
import com.nhom15.datxebackend.driver.repository.DriverRepository
import com.nhom15.datxebackend.notification.token.DeviceTokenService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val firebaseAuth: FirebaseAuth,
    private val customerRepository: CustomerRepository,
    private val driverRepository: DriverRepository,
    private val adminRepository: AdminRepository,
    private val deviceTokenService: DeviceTokenService,
    private val authenticatedUserCache: AuthenticatedUserCache,
) : AuthenticatedUserProvider {
    @Transactional
    fun loginCustomer(request: AuthLoginRequest): AuthProfileResponse {
        val token = verifyToken(request.idToken)
        val existing = customerRepository.findByFirebaseUid(token.uid).orElse(null)
        if (existing == null) {
            ensureUidAvailableFor(token.uid, Role.CUSTOMER)
            throw AppException(ErrorCode.ACCOUNT_NOT_FOUND)
        }
        val verifiedPhone = verifiedPhone(token, request.phoneNumber, required = true)

        if (existing.deletedAt != null || existing.status != UserStatus.ACTIVE) {
            throw AppException(ErrorCode.ACCOUNT_LOCKED)
        }
        if (existing.phoneNumber == null && verifiedPhone != null) existing.phoneNumber = verifiedPhone
        return profileOf(toAuthUser(customerRepository.save(existing)))
    }

    @Transactional
    fun registerCustomer(request: AuthRegisterRequest): AuthProfileResponse {
        val token = verifyToken(request.idToken)
        if (customerRepository.findByFirebaseUid(token.uid).isPresent) {
            throw AppException(ErrorCode.ACCOUNT_ALREADY_EXISTS)
        }
        ensureUidAvailableFor(token.uid, Role.CUSTOMER)
        val verifiedPhone = verifiedPhone(token, request.phoneNumber, required = true)
        val customer = Customer().apply {
            firebaseUid = token.uid
            fullName = request.fullName.trim()
            email = token.email
            phoneNumber = verifiedPhone
        }
        return profileOf(toAuthUser(customerRepository.save(customer)))
    }

    @Transactional
    fun loginDriver(request: AuthLoginRequest): AuthProfileResponse {
        val token = verifyToken(request.idToken)
        val existing = driverRepository.findByFirebaseUid(token.uid).orElse(null)
        if (existing == null) {
            ensureUidAvailableFor(token.uid, Role.DRIVER)
            throw AppException(ErrorCode.ACCOUNT_NOT_FOUND)
        }
        if (existing.deletedAt != null) throw AppException(ErrorCode.ACCOUNT_LOCKED)
        val verifiedPhone = verifiedPhone(token, request.phoneNumber, required = false)
        if (existing.phoneNumber.isBlank() && verifiedPhone != null) existing.phoneNumber = verifiedPhone

        return profileOf(toAuthUser(driverRepository.save(existing)))
    }

    @Transactional
    fun registerDriver(request: AuthRegisterRequest): AuthProfileResponse {
        val token = verifyToken(request.idToken)
        if (driverRepository.findByFirebaseUid(token.uid).isPresent) {
            throw AppException(ErrorCode.ACCOUNT_ALREADY_EXISTS)
        }
        ensureUidAvailableFor(token.uid, Role.DRIVER)
        val verifiedPhone = verifiedPhone(token, request.phoneNumber, required = true)
        val driver = Driver().apply {
            firebaseUid = token.uid
            phoneNumber = verifiedPhone!!
            email = token.email
            fullName = request.fullName.trim()
            verificationStatus = PENDING_VERIFICATION
        }
        return profileOf(toAuthUser(driverRepository.save(driver)))
    }

    @Transactional(readOnly = true)
    fun loginAdmin(request: AuthLoginRequest): AuthProfileResponse {
        val token = verifyToken(request.idToken)
        val admin = adminRepository.findByFirebaseUid(token.uid).orElseThrow {
            AppException(ErrorCode.FORBIDDEN, "Tài khoản admin chưa được cấp quyền")
        }
        if (admin.status != ACTIVE_STATUS) throw AppException(ErrorCode.ACCOUNT_LOCKED)
        return profileOf(toAuthUser(admin))
    }

    /** Resolve token UID to an enabled database account; login endpoints create accounts separately. */
    @Transactional(readOnly = true)
    override fun findAuthenticatedUser(firebaseUid: String): AuthUser? =
        authenticatedUserCache.get(firebaseUid) { loadAuthenticatedUser(firebaseUid) }

    private fun loadAuthenticatedUser(firebaseUid: String): AuthUser? {
        adminRepository.findByFirebaseUid(firebaseUid).orElse(null)?.let { admin ->
            return admin.takeIf { it.status == ACTIVE_STATUS }?.let(::toAuthUser)
        }
        driverRepository.findByFirebaseUid(firebaseUid).orElse(null)?.let { driver ->
            if (driver.deletedAt == null) return toAuthUser(driver)
        }
        customerRepository.findByFirebaseUid(firebaseUid).orElse(null)?.let { customer ->
            return customer.takeIf { it.deletedAt == null && it.status == UserStatus.ACTIVE }?.let(::toAuthUser)
        }
        return null
    }

    @Transactional
    fun logout(user: AuthUser, installationId: String) {
        if (user.role != Role.ADMIN) {
            deviceTokenService.unregister(user.id, user.role, installationId)
        }
    }

    fun profileOf(user: AuthUser): AuthProfileResponse = AuthProfileResponse(
        id = user.id,
        role = user.role,
        firebaseUid = user.firebaseUid,
        fullName = user.fullName,
        email = user.email,
        phoneNumber = user.phoneNumber,
        status = user.status,
        verificationStatus = user.verificationStatus,
    )

    private fun verifyToken(idToken: String): FirebaseToken {
        if (idToken.isBlank()) {
            throw BadRequestException(ErrorCode.VALIDATION_ERROR, "Firebase ID token không được để trống")
        }
        return try {
            firebaseAuth.verifyIdToken(idToken.trim(), true)
        } catch (exception: FirebaseAuthException) {
            log.warn("Firebase ID token verification failed: authErrorCode={}", exception.authErrorCode)
            if (exception.authErrorCode == AuthErrorCode.CERTIFICATE_FETCH_FAILED) {
                throw AppException(ErrorCode.FIREBASE_AUTH_UNAVAILABLE, cause = exception)
            }
            throw AppException(ErrorCode.TOKEN_INVALID, cause = exception)
        } catch (exception: IllegalArgumentException) {
            log.warn("Firebase ID token verification failed: malformed token")
            throw AppException(ErrorCode.TOKEN_INVALID, cause = exception)
        }
    }

    private fun verifiedPhone(token: FirebaseToken, requestedPhone: String?, required: Boolean): String? {
        val phoneFromFirebase = (token.claims[PHONE_NUMBER_CLAIM] as? String)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val requested = requestedPhone?.trim()?.takeIf(String::isNotEmpty)

        if (requested != null && requested != phoneFromFirebase) {
            throw BadRequestException(ErrorCode.PHONE_MISMATCH)
        }
        if (required && phoneFromFirebase == null) {
            throw BadRequestException(
                ErrorCode.VALIDATION_ERROR,
                "Cần xác minh số điện thoại bằng Firebase trước khi đăng nhập hoặc tạo tài khoản",
            )
        }
        return phoneFromFirebase
    }

    private fun ensureUidAvailableFor(firebaseUid: String, requestedRole: Role) {
        val existsForAnotherRole = when (requestedRole) {
            Role.CUSTOMER -> driverRepository.existsByFirebaseUid(firebaseUid) || adminRepository.existsByFirebaseUid(firebaseUid)
            Role.DRIVER -> customerRepository.existsByFirebaseUid(firebaseUid) || adminRepository.existsByFirebaseUid(firebaseUid)
            Role.ADMIN -> customerRepository.existsByFirebaseUid(firebaseUid) || driverRepository.existsByFirebaseUid(firebaseUid)
        }
        if (existsForAnotherRole) {
            throw AppException(ErrorCode.ACCOUNT_ALREADY_EXISTS, "Firebase account đã được liên kết với role khác")
        }
    }

    private fun toAuthUser(customer: Customer) = AuthUser(
        id = customer.id,
        firebaseUid = customer.firebaseUid,
        role = Role.CUSTOMER,
        fullName = customer.fullName,
        email = customer.email,
        phoneNumber = customer.phoneNumber,
        status = customer.status.name,
    )

    private fun toAuthUser(driver: Driver) = AuthUser(
        id = driver.id,
        firebaseUid = driver.firebaseUid,
        role = Role.DRIVER,
        fullName = driver.fullName,
        email = driver.email,
        phoneNumber = driver.phoneNumber,
        status = driver.status.name,
        verificationStatus = driver.verificationStatus,
    )

    private fun toAuthUser(admin: Admin) = AuthUser(
        id = admin.id,
        firebaseUid = admin.firebaseUid,
        role = Role.ADMIN,
        fullName = admin.fullName,
        email = admin.email,
        phoneNumber = null,
        status = admin.status,
    )

    private companion object {
        val log = LoggerFactory.getLogger(AuthService::class.java)
        const val ACTIVE_STATUS = "ACTIVE"
        const val PENDING_VERIFICATION = "PENDING"
        const val PHONE_NUMBER_CLAIM = "phone_number"
    }
}
