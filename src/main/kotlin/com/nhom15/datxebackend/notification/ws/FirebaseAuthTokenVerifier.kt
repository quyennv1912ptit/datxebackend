package com.nhom15.datxebackend.notification.ws

import com.google.firebase.auth.FirebaseAuth
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthenticatedUserProvider
import com.nhom15.datxebackend.notification.api.Recipient
import org.springframework.stereotype.Component

@Component
class FirebaseAuthTokenVerifier(
    private val firebaseAuth: FirebaseAuth,
    private val authenticatedUserProvider: AuthenticatedUserProvider,
) : AuthTokenVerifier {
    override fun verify(token: String, roleHint: Role?): Recipient? =
        verifyIdentity(token, roleHint)?.recipient

    override fun verifyIdentity(token: String, roleHint: Role?): VerifiedStompIdentity? {
        val decodedToken = firebaseAuth.verifyIdToken(token, true)
        val user = authenticatedUserProvider.findAuthenticatedUser(decodedToken.uid, roleHint) ?: return null
        if (roleHint != null && user.role != roleHint) return null

        val recipient = when (user.role) {
            Role.CUSTOMER -> Recipient.Customer(user.id)
            Role.DRIVER -> Recipient.Driver(user.id)
            Role.ADMIN -> return null
        }
        return VerifiedStompIdentity(recipient = recipient, firebaseUid = decodedToken.uid, role = user.role)
    }

    override fun isSessionActive(
        principal: StompPrincipal,
        token: String?,
        checkRemoteRevocation: Boolean,
    ): Boolean {
        val firebaseUid = principal.firebaseUid?.takeIf { it.isNotBlank() } ?: return false
        val user = try {
            authenticatedUserProvider.findAuthenticatedUser(firebaseUid, principal.role)
        } catch (_: Exception) {
            return false
        } ?: return false

        if (user.role != principal.role || user.id != principal.recipient.id) {
            return false
        }

        if (checkRemoteRevocation) {
            val rawToken = token?.takeIf { it.isNotBlank() } ?: return false
            val decoded = try {
                firebaseAuth.verifyIdToken(rawToken, true)
            } catch (_: Exception) {
                return false
            }
            if (decoded.uid != firebaseUid) {
                return false
            }
        }
        return true
    }
}
