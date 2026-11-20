package com.nhom15.datxebackend

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

/** Real Flyway + Hibernate schema validation; external providers are isolated from tests. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = [
    "spring.datasource.url=\${TEST_DB_URL}", "spring.datasource.username=\${TEST_DB_USER}", "spring.datasource.password=\${TEST_DB_PASSWORD:}",
    "spring.data.redis.url=redis://127.0.0.1:1", "cloudinary.url=cloudinary://test:test@test",
    "firebase.credentials-location=classpath:unused-test-credentials.json",
    "app.bootstrap.admin.firebase-uid=", "app.bootstrap.admin.email=", "app.bootstrap.admin.full-name=",
    "app.bootstrap.pricing.enabled=false", "app.ekyc.enabled=false", "app.verification-sync.enabled=false",
])
class DatxebackendApplicationTests {
    @MockitoBean lateinit var firebaseApp: FirebaseApp
    @MockitoBean lateinit var firebaseAuth: FirebaseAuth
    @MockitoBean lateinit var firebaseMessaging: FirebaseMessaging
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) lateinit var redis: StringRedisTemplate
    @Autowired @Qualifier("requestMappingHandlerMapping")
    lateinit var routes: RequestMappingHandlerMapping

    @Test fun contextLoads() {}

    @Test fun retiredRoutesAreAbsent() {
        val routesByPath = routes.handlerMethods.keys.flatMap { route -> route.patternValues.map { it to route.methodsCondition.methods } }
        val paths = routesByPath.map { it.first }.toSet()
        assertTrue("/ekyc/driver-verification/v1/admin/applications/{applicationId}/decision" in paths)
        assertTrue("/api/driver/profile/identity" in paths)
        assertFalse(paths.any { it in setOf("/api/driver/documents", "/api/driver/skills", "/api/admin/driver-documents",
            "/api/admin/driver-documents/{id}/review", "/api/admin/driver-skills/{id}/verify", "/api/admin/drivers/{id}/verification-status") })
        assertFalse(routesByPath.any { (path, methods) -> path == "/api/driver/profile" && RequestMethod.PUT in methods })
    }
}
