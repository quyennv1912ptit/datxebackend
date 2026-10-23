package com.nhom15.datxebackend.uploads

data class UploadSignatureResponse(
    val signature: String,
    val timestamp: Long,
    val apiKey: String,
    val cloudName: String,
    val folder: String,
    val allowedFormats: List<String>? = null,
)
