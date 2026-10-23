package com.nhom15.datxebackend.notification.ws

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.notification.api.Recipient


interface AuthTokenVerifier {
    fun verify(token: String, roleHint: Role? = null): Recipient?
}
