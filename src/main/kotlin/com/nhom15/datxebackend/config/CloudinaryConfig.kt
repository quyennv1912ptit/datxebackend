package com.nhom15.datxebackend.config

import com.cloudinary.Cloudinary
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CloudinaryConfig(
    @Value("\${cloudinary.url}") private val url: String,
) {
    @Bean
    fun cloudinary(): Cloudinary = Cloudinary(url)
}