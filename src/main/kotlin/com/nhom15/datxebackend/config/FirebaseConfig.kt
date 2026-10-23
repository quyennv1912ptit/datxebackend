package com.nhom15.datxebackend.config

import com.google.api.client.googleapis.util.Utils
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource

@Configuration
class FirebaseConfig(
    @Value("\${firebase.credentials-location}")
    private val credentialsLocation: Resource,
) {

    @Bean
    fun firebaseApp(): FirebaseApp {
        FirebaseApp.getApps()
            .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?.let { return it }

        val credentials = credentialsLocation.inputStream.use {
            GoogleCredentials.fromStream(it)
        }
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            // Avoid the Firebase Admin 9.4.x Apache HTTP/2 default: certificate
            // responses fail during gzip handling with this runtime's network path.
            .setHttpTransport(Utils.getDefaultTransport())
            .build()
        return FirebaseApp.initializeApp(options)
    }

    @Bean
    fun firebaseAuth(app: FirebaseApp): FirebaseAuth = FirebaseAuth.getInstance(app)

    @Bean
    fun firebaseMessaging(app: FirebaseApp): FirebaseMessaging = FirebaseMessaging.getInstance(app)
}
