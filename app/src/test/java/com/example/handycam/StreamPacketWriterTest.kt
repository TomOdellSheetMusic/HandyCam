package com.example.handycam

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPacketWriterTest {
    @Test
    fun writesTimestampLengthAndPayloadInNetworkByteOrder() {
        val output = ByteArrayOutputStream()
        val payload = byteArrayOf(1, 2, 3)

        output.writeStreamPacket(-1L, payload)

        val packet = ByteBuffer.wrap(output.toByteArray())
        assertEquals(-1L, packet.long)
        assertEquals(payload.size, packet.int)
        val actualPayload = ByteArray(packet.remaining())
        packet.get(actualPayload)
        assertArrayEquals(payload, actualPayload)
    }
}
