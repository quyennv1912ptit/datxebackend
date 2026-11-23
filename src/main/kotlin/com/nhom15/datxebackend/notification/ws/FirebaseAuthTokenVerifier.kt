package com.nhom15.datxebackend.notification.ws

import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthenticatedUserProvider
import com.nhom15.datxebackend.notification.api.Recipient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class FirebaseAuthTokenVerifier(
    private val firebaseAuth: FirebaseAuth,
    private val authenticatedUserProvider: AuthenticatedUserProvider,
) : AuthTokenVerifier {

    override fun verify(token: String, roleHint: Role?): Recipient? {
        // A revoked token must fail STOMP CONNECT just like an expired one.
        val decodedToken = try {
            firebaseAuth.verifyIdToken(token, true)
        } catch (exception: FirebaseAuthException) {
            if (exception.authErrorCode == AuthErrorCode.REVOKED_ID_TOKEN) {
                log.debug("Rejected revoked Firebase ID token on STOMP CONNECT")
            } else {
                log.debug("Rejected invalid Firebase ID token on STOMP CONNECT: {}", exception.message)
            }
            return null
        } catch (exception: IllegalArgumentException) {
            log.debug("Rejected malformed Firebase ID token on STOMP CONNECT: {}", exception.message)
            return null
        }
        val user = authenticatedUserProvider.findAuthenticatedUser(decodedToken.uid, roleHint) ?: return null
        if (roleHint != null && user.role != roleHint) return null

        return when (user.role) {
            Role.CUSTOMER -> Recipient.Customer(user.id)
            Role.DRIVER -> Recipient.Driver(user.id)
            Role.ADMIN -> null
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(FirebaseAuthTokenVerifier::class.java)
    }
}
