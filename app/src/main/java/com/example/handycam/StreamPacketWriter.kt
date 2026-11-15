package com.example.handycam

import java.io.OutputStream
import java.nio.ByteBuffer

internal fun OutputStream.writeStreamPacket(ptsUs: Long, data: ByteArray) {
    val header = ByteBuffer.allocate(12).putLong(ptsUs).putInt(data.size).array()
    write(header)
    write(data)
    flush()
}
