package com.nhom15.datxebackend.ekyc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@EnabledIfEnvironmentVariable(named = "DATXE_EKYC_SMOKE", matches = "true")
class EkycModalSmokeTest {
    @Test
    fun `calls the private Modal deployment with a synthetic image`() {
        val client = EkycConfig().ekycClient(
            System.getenv("DATXE_EKYC_ORIGIN"),
            System.getenv("DATXE_EKYC_API_KEY"),
            System.getenv("DATXE_EKYC_MODAL_KEY"),
            System.getenv("DATXE_EKYC_MODAL_SECRET"),
            JsonMapper.builder().build(),
        )
        val png = ByteArrayOutputStream()
        val image = BufferedImage(560, 350, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = java.awt.Color.BLACK
            graphics.drawString("SYNTHETIC TEST", 20, 55)
        } finally {
            graphics.dispose()
        }
        assertTrue(ImageIO.write(image, "png", png))
        val result = client.extract(
            EkycDocumentSlot.CCCD_FRONT, MediaType.IMAGE_PNG, png.toByteArray().inputStream(),
        )
        assertEquals("cccd_front", result.path("slot").asString())
        assertTrue(result.path("candidates").isArray)
    }
}
