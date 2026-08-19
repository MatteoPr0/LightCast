package com.lightcast.receiver.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

data class CastMessage(
    val protocolVersion: Int = 0,
    val sourceId: String = "",
    val destinationId: String = "",
    val namespace: String = "",
    val payloadType: Int = 0, // 0 = STRING, 1 = BINARY
    val payloadUtf8: String? = null,
    val payloadBinary: ByteArray? = null
) {
    fun toByteArray(): ByteArray {
        val bos = ByteArrayOutputStream()
        
        // Field 1: protocol_version (varint)
        writeVarint(bos, (1 shl 3) or 0)
        writeVarint(bos, protocolVersion)

        // Field 2: source_id (string)
        writeString(bos, 2, sourceId)

        // Field 3: destination_id (string)
        writeString(bos, 3, destinationId)

        // Field 4: namespace (string)
        writeString(bos, 4, namespace)

        // Field 5: payload_type (varint)
        writeVarint(bos, (5 shl 3) or 0)
        writeVarint(bos, payloadType)

        // Field 6: payload_utf8 (string)
        if (payloadUtf8 != null) {
            writeString(bos, 6, payloadUtf8)
        }

        // Field 7: payload_binary (bytes)
        if (payloadBinary != null) {
            writeBytes(bos, 7, payloadBinary)
        }

        return bos.toByteArray()
    }

    companion object {
        fun parseFrom(data: ByteArray): CastMessage {
            val stream = ByteArrayInputStream(data)
            var protocolVersion = 0
            var sourceId = ""
            var destinationId = ""
            var namespace = ""
            var payloadType = 0
            var payloadUtf8: String? = null
            var payloadBinary: ByteArray? = null

            while (stream.available() > 0) {
                val tag = readVarint(stream)
                if (tag == -1) break
                val fieldNumber = tag ushr 3
                val wireType = tag and 0x07

                when (fieldNumber) {
                    1 -> protocolVersion = readVarint(stream)
                    2 -> sourceId = readString(stream)
                    3 -> destinationId = readString(stream)
                    4 -> namespace = readString(stream)
                    5 -> payloadType = readVarint(stream)
                    6 -> payloadUtf8 = readString(stream)
                    7 -> payloadBinary = readBytes(stream)
                    else -> skipField(stream, wireType)
                }
            }

            return CastMessage(
                protocolVersion = protocolVersion,
                sourceId = sourceId,
                destinationId = destinationId,
                namespace = namespace,
                payloadType = payloadType,
                payloadUtf8 = payloadUtf8,
                payloadBinary = payloadBinary
            )
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while ((v and 0xFFFFFF80.toInt()) != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v and 0x7F)
        }

        private fun writeString(out: ByteArrayOutputStream, fieldNumber: Int, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeBytes(out, fieldNumber, bytes)
        }

        private fun writeBytes(out: ByteArrayOutputStream, fieldNumber: Int, bytes: ByteArray) {
            writeVarint(out, (fieldNumber shl 3) or 2)
            writeVarint(out, bytes.size)
            out.write(bytes)
        }

        private fun readVarint(stream: InputStream): Int {
            var result = 0
            var shift = 0
            while (shift < 32) {
                val b = stream.read()
                if (b == -1) return if (shift == 0) -1 else result
                result = result or ((b and 0x7F) shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        private fun readString(stream: InputStream): String {
            val bytes = readBytes(stream)
            return String(bytes, Charsets.UTF_8)
        }

        private fun readBytes(stream: InputStream): ByteArray {
            val length = readVarint(stream)
            if (length <= 0) return ByteArray(0)
            val buffer = ByteArray(length)
            var readTotal = 0
            while (readTotal < length) {
                val read = stream.read(buffer, readTotal, length - readTotal)
                if (read == -1) break
                readTotal += read
            }
            return buffer
        }

        private fun skipField(stream: InputStream, wireType: Int) {
            when (wireType) {
                0 -> readVarint(stream)
                1 -> stream.skip(8)
                2 -> {
                    val len = readVarint(stream)
                    if (len > 0) stream.skip(len.toLong())
                }
                5 -> stream.skip(4)
            }
        }
    }
}
