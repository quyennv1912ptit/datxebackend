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
    override fun verify(token: String, roleHint: Role?): Recipient? {
        val decodedToken = firebaseAuth.verifyIdToken(token)
        val user = authenticatedUserProvider.findAuthenticatedUser(decodedToken.uid, roleHint) ?: return null
        if (roleHint != null && user.role != roleHint) return null

        return when (user.role) {
            Role.CUSTOMER -> Recipient.Customer(user.id)
            Role.DRIVER -> Recipient.Driver(user.id)
            Role.ADMIN -> null
        }
    }
}
