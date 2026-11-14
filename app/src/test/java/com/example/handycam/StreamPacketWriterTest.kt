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
    @Test
    fun preservesStreamRelativeMicrosecondTimestamps() {
        val output = ByteArrayOutputStream()
        output.writeStreamPacket(1_000_000L, byteArrayOf(7))
        output.writeStreamPacket(1_066_667L, byteArrayOf(8))

        val packet = ByteBuffer.wrap(output.toByteArray())
        assertEquals(1_000_000L, packet.long)
        assertEquals(1, packet.int)
        assertEquals(7, packet.get().toInt())
        assertEquals(1_066_667L, packet.long)
        assertEquals(1, packet.int)
        assertEquals(8, packet.get().toInt())
    }
}
