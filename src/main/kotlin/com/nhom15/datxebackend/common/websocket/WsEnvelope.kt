package com.nhom15.datxebackend.common.websocket

import java.util.UUID

data class WsEnvelope<T>(
    val type: PayloadType,
    val payload: T,
    val messageId: String = UUID.randomUUID().toString(),
)
