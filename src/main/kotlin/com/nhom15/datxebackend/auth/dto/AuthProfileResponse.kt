package com.nhom15.datxebackend.auth.dto

import com.nhom15.datxebackend.common.enums.Role

data class AuthProfileResponse(
    val id: Long,
    val role: Role,
    val firebaseUid: String,
    val fullName: String?,
    val email: String?,
    val phoneNumber: String?,
    val status: String,
    val verificationStatus: String? = null,
)
