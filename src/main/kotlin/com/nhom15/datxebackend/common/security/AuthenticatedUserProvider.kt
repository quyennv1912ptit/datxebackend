package com.nhom15.datxebackend.common.security

/** Looks up an enabled application account for an already verified identity token. */
interface AuthenticatedUserProvider {
    fun findAuthenticatedUser(firebaseUid: String): AuthUser?
}
