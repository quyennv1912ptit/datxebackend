package com.nhom15.datxebackend.notification.ws

import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.notification.api.Recipient


data class VerifiedStompIdentity(
    val recipient: Recipient,
    val firebaseUid: String,
    val role: Role,
)

interface AuthTokenVerifier {
    fun verify(token: String, roleHint: Role? = null): Recipient?

    fun verifyIdentity(token: String, roleHint: Role? = null): VerifiedStompIdentity? =
        verify(token, roleHint)?.let { recipient ->
            val role = when (recipient) {
                is Recipient.Customer -> Role.CUSTOMER
                is Recipient.Driver -> Role.DRIVER
            }
            VerifiedStompIdentity(recipient, recipient.key, role)
        }

    fun isSessionActive(
        principal: StompPrincipal,
        token: String?,
        checkRemoteRevocation: Boolean,
    ): Boolean = true
}
