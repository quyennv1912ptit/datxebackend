
package com.nhom15.datxebackend.notification.channel

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.AndroidConfig
import com.nhom15.datxebackend.common.websocket.WsEnvelope
import com.nhom15.datxebackend.notification.api.Recipient
import com.nhom15.datxebackend.notification.token.DeviceTokenRepository
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

@Component
class FcmChannel(
    private val tokenRepo: DeviceTokenRepository,
    private val messaging: FirebaseMessaging,
    private val objectMapper: ObjectMapper
) : NotificationChannel {

    private val logger = LoggerFactory.getLogger(FcmChannel::class.java)

    override fun isAvailable(to: Recipient): Boolean {
        return tokenRepo
            .findByRoleAndOwnerId(to.role, to.id)
            .isNotEmpty()
    }

    override fun send(
        to: Recipient,
        envelope: WsEnvelope<*>
    ): Boolean {
        val tokens = tokenRepo.findByRoleAndOwnerId(to.role, to.id)

        if (tokens.isEmpty()) {
            return false
        }

        val payload = try {
            objectMapper.writeValueAsString(envelope)
        } catch (e: Exception) {
            logger.error("Không thể serialize notification payload", e)
            return false
        }

        var sentAtLeastOne = false

        for (token in tokens) {
            try {
                val message = Message.builder()
                    .setToken(token.fcmToken)
                    .putData("payload", payload)
                    .setAndroidConfig(
                        AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build(),
                    )
                    .build()

                messaging.send(message)
                sentAtLeastOne = true

            } catch (e: FirebaseMessagingException) {
                when (e.messagingErrorCode) {
                    MessagingErrorCode.UNREGISTERED -> {
                        try {
                            tokenRepo.delete(token)
                            logger.info(
                                "Đã xóa device token không còn hợp lệ"
                            )
                        } catch (deleteError: Exception) {
                            logger.error(
                                "Không thể xóa device token không hợp lệ",
                                deleteError
                            )
                        }
                    }

                    MessagingErrorCode.INVALID_ARGUMENT -> {
                        logger.warn(
                            "FCM từ chối message do INVALID_ARGUMENT"
                        )
                    }

                    else -> {
                        logger.warn(
                            "Gửi FCM thất bại, errorCode={}",
                            e.messagingErrorCode,
                            e
                        )
                    }
                }
            }
        }

        return sentAtLeastOne
    }
}
