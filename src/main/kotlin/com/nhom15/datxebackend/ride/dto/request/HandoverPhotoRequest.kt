package com.nhom15.datxebackend.ride.dto.request

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.nhom15.datxebackend.ride.entity.HandoverPhotoAngle
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class HandoverPhotoRequest(
    val angle: HandoverPhotoAngle,

    @JsonProperty("fileUrl")
    @JsonAlias("secureUrl", "secure_url")
    @field:NotBlank
    @field:Size(max = 2_048)
    val fileUrl: String,

    @JsonProperty("publicId")
    @JsonAlias("public_id", "cloudinaryPublicId", "cloudinary_public_id")
    @field:NotBlank
    @field:Size(max = 512)
    val publicId: String,
)
