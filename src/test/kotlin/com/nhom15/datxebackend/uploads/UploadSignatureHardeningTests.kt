package com.nhom15.datxebackend.uploads

import com.cloudinary.Cloudinary
import com.nhom15.datxebackend.chat.entity.Conversation
import com.nhom15.datxebackend.chat.service.ConversationService
import com.nhom15.datxebackend.chat.service.ParticipantConversationContext
import com.nhom15.datxebackend.common.enums.Role
import com.nhom15.datxebackend.common.security.AuthUser
import com.nhom15.datxebackend.ride.entity.Ride
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`

class UploadSignatureHardeningTests {
    private val actor = AuthUser(7L, "customer-uid", Role.CUSTOMER, null, null, null, "ACTIVE")

    private fun cloudinary() = spy(
        Cloudinary(
            mapOf(
                "cloud_name" to "test-cloud",
                "api_key" to "test-key",
                "api_secret" to "test-secret",
            ),
        ),
    )

    @Test
    fun `signature signs exactly the params the current client sends`() {
        val cloudinary = cloudinary()
        val service = UploadSignatureService(cloudinary, mock(ConversationService::class.java))

        val response = service.create(actor, "SUPPORT", null)

        // Cloudinary requires the signed param set to match EXACTLY what the client posts.
        // Signing public_id/overwrite here would break every upload until the RN client
        // is updated to send them; ownership is enforced server-side by validateOwnedImage.
        val signCall = mockingDetails(cloudinary).invocations.single { it.method.name == "apiSignRequest" }
        @Suppress("UNCHECKED_CAST")
        val params = signCall.arguments[0] as Map<String, Any>
        assertEquals("app_dat_xe/customer/7", params["folder"])
        assertTrue("public_id" !in params)
        assertTrue("overwrite" !in params)
        assertTrue("resource_type" !in params)
        assertEquals("jpg,jpeg,png,webp", params["allowed_formats"])
        assertEquals(response.timestamp.toString(), params["timestamp"].toString())
        assertEquals("test-secret", signCall.arguments[1])
    }

    @Test
    fun `chat signature folder matches the per conversation per sender chat validation prefix`() {
        val cloudinary = cloudinary()
        val conversations = mock(ConversationService::class.java)
        val conversation = Conversation().apply { id = 12L; rideId = 99L }
        val context = ParticipantConversationContext(mock(Ride::class.java), conversation)
        `when`(conversations.requireParticipant(99L, actor)).thenReturn(context)
        `when`(conversations.requireOpen(context)).thenReturn(conversation)

        val response = UploadSignatureService(cloudinary, conversations).create(actor, "CHAT", 99L)

        assertEquals("datxe/chat/12/CUSTOMER/7", response.folder)
        assertEquals(
            "datxe/chat/12/CUSTOMER/7/",
            CloudinaryFolders.chatPrefix(conversation.id, actor.role, actor.id),
        )
    }
}
