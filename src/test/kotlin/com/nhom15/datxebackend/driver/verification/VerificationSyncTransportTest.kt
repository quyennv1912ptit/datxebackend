package com.nhom15.datxebackend.driver.verification

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationSyncTransportTest {
    @Test
    fun `uses authenticated checkpoint transport and fails closed after upstream errors`() {
        val projection = mock(VerificationProjectionRepository::class.java)
        val key = "synthetic-sync-transport-fixture"
        val stream = UUID.randomUUID()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val fail = AtomicBoolean()
        server.createContext("/ekyc/driver-verification/v1/internal") { exchange ->
            val isHead = exchange.requestURI.path.endsWith("/decision-head")
            val accepted = exchange.requestHeaders.getFirst("X-Verification-Sync-Key") == key &&
                (isHead || exchange.requestURI.rawQuery == "after=4")
            val page = if (isHead) "" else ""","events":[],"hasMore":false"""
            val status = if (!accepted) 401 else if (fail.get()) 503 else 200
            val body = """{"success":true,"data":{"streamId":"$stream","head":4$page}}""".toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val sync = VerificationSync(projection, "http://127.0.0.1:${server.address.port}", key)
            `when`(projection.cursor()).thenReturn(4)
            sync.reconcile()
            verify(projection).applyPage(4L, VerificationFeed(stream, 4L, emptyList(), false))
            val head = VerificationHead(stream, 4)
            assertTrue(sync.confirms(head))
            assertFalse(sync.confirms(VerificationHead(stream, 3)))
            fail.set(true)
            assertFalse(sync.confirms(head))
            sync.reconcile()
            verify(projection).failed()
        } finally {
            server.stop(0)
        }
    }
}
