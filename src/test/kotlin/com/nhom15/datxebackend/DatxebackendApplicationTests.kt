package com.nhom15.datxebackend

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Answers
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean

/** Real Flyway + Hibernate schema validation; external providers are isolated from tests. */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = [
    "spring.datasource.url=\${TEST_DB_URL}", "spring.datasource.username=\${TEST_DB_USER}", "spring.datasource.password=\${TEST_DB_PASSWORD:}",
    "spring.data.redis.url=redis://127.0.0.1:1", "cloudinary.url=cloudinary://test:test@test",
    "firebase.credentials-location=classpath:unused-test-credentials.json", "app.bootstrap.admin.firebase-uid=",
])
class DatxebackendApplicationTests {
    @MockitoBean lateinit var firebaseApp: FirebaseApp
    @MockitoBean lateinit var firebaseAuth: FirebaseAuth
    @MockitoBean lateinit var firebaseMessaging: FirebaseMessaging
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS) lateinit var redis: StringRedisTemplate
    @Test fun contextLoads() {}
}
