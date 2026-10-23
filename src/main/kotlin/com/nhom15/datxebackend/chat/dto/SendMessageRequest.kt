package com.nhom15.datxebackend.chat.dto

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.chat.entity.ChatMessageType
import jakarta.validation.constraints.Size
import java.util.UUID

data class SendMessageRequest(
    @JsonProperty("client_message_id")
    @JsonAlias("clientMessageId")
    val clientMessageId: UUID,
    @JsonProperty("message_type")
    @JsonAlias("messageType")
    val messageType: ChatMessageType = ChatMessageType.TEXT,
    @field:Size(max = 1000)
    val content: String? = null,
    @JsonProperty("attachment_url")
    @JsonAlias("attachmentUrl")
    @field:Size(max = 2048)
    val attachmentUrl: String? = null,
    @JsonProperty("attachment_public_id")
    @JsonAlias("attachmentPublicId", "cloudinary_public_id")
    @field:Size(max = 512)
    val attachmentPublicId: String? = null,
)
