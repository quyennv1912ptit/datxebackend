package com.nhom15.datxebackend.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    val rootFolder: String = "datxe",
    val maxFileSize: DataSize = DataSize.ofMegabytes(10),
    val allowedContentTypes: Set<String> = setOf("image/jpeg", "image/png", "image/webp")
) {
    init {
        require(rootFolder.isNotBlank()) { "app.storage.root-folder không được để trống" }
        require(maxFileSize.toBytes() > 0) { "app.storage.max-file-size phải lớn hơn 0" }
        require(allowedContentTypes.isNotEmpty() && allowedContentTypes.all { it.startsWith("image/") }) {
            "app.storage.allowed-content-types phải chứa ít nhất một loại ảnh hợp lệ"
        }
    }
}
