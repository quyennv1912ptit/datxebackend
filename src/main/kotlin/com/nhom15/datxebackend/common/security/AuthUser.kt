package com.nhom15.datxebackend.common.security

import com.nhom15.datxebackend.common.enums.Role

/** Authenticated account data shared with business modules without exposing persistence entities. */
data class AuthUser(
    val id: Long,
    val firebaseUid: String,
    val role: Role,
    val fullName: String?,
    val email: String?,
    val phoneNumber: String?,
    val status: String,
    val verificationStatus: String? = null,
)
