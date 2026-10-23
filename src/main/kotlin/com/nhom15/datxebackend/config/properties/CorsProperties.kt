package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.cors")
data class CorsProperties(
    // Danh sách các domain frontend (App khách, App tài xế, Web Admin) được phép truy cập
    // Ví dụ: ["http://localhost:3000", "https://admin.potitgo.vn"]
    val allowedOrigins: List<String> = emptyList()
)