package com.nhom15.datxebackend.common.security

import com.nhom15.datxebackend.common.enums.Role

/** Looks up an enabled application account for an already verified identity token. */
interface AuthenticatedUserProvider {
    fun findAuthenticatedUser(firebaseUid: String, roleHint: Role? = null): AuthUser?
}
