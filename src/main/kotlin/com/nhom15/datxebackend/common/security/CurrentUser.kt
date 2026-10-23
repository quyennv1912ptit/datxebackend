package com.nhom15.datxebackend.common.security

import org.springframework.security.core.annotation.AuthenticationPrincipal

/** Inject the authenticated principal into a controller parameter. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@AuthenticationPrincipal
annotation class CurrentUser
