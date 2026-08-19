package com.lightcast.receiver.cast

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

object CastCertificateGenerator {

    private var cachedSslContext: SSLContext? = null
    var privateKey: PrivateKey? = null
        private set
    var certificateDer: ByteArray? = null
        private set

    @Synchronized
    fun getSSLContext(): SSLContext {
        cachedSslContext?.let { return it }

        val keyPairGen = KeyPairGenerator.getInstance("RSA")
        keyPairGen.initialize(2048, SecureRandom())
        val keyPair = keyPairGen.generateKeyPair()
        privateKey = keyPair.private

        val (cert, der) = generateSelfSignedCertificateWithDer(keyPair)
        certificateDer = der

        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        val password = "lightcast_cert_pass".toCharArray()
        keyStore.setKeyEntry("cast_key", keyPair.private, password, arrayOf(cert))

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, SecureRandom())

        cachedSslContext = sslContext
        return sslContext
    }

    private fun generateSelfSignedCertificateWithDer(keyPair: KeyPair): Pair<X509Certificate, ByteArray> {
        val serial = BigInteger.valueOf(System.currentTimeMillis())

        // Cast senders rifiutano certificati TLS con durata residua eccessiva.
        // Manteniamo il certificato effimero: valido da 5 min fa a +72 ore.
        val now = System.currentTimeMillis()
        val notBefore = formatUtcTime(Date(now - 5L * 60L * 1000L))
        val notAfter = formatUtcTime(Date(now + 72L * 60L * 60L * 1000L))

        // SHA256withRSA OID: 1.2.840.113549.1.1.11
        val sha256WithRsaOid = byteArrayOf(
            0x2a.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(),
            0xf7.toByte(), 0x0d.toByte(), 0x01.toByte(), 0x01.toByte(), 0x0b.toByte()
        )
        val algId = derSequence(derOid(sha256WithRsaOid), derNull())

        // Subject / Issuer: CN=LightCast Receiver, O=Google Cast, C=US
        val nameSeq = derSequence(
            derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x03)), derUtf8String("LightCast Receiver"))),
            derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x0a)), derUtf8String("Google Cast"))),
            derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x06)), derPrintableString("US")))
        )

        val validity = derSequence(
            derUtcTime(notBefore),
            derUtcTime(notAfter)
        )

        val pubKeyInfo = keyPair.public.encoded

        val tbsCert = derSequence(
            derExplicit(0, derInteger(BigInteger.valueOf(2))), // v3
            derInteger(serial),
            algId,
            nameSeq, // issuer
            validity,
            nameSeq, // subject
            pubKeyInfo
        )

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbsCert)
        val signatureBytes = sig.sign()

        val certDer = derSequence(
            tbsCert,
            algId,
            derBitString(signatureBytes)
        )

        val cf = CertificateFactory.getInstance("X.509")
        val cert = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        return Pair(cert, certDer)
    }

    private fun formatUtcTime(date: Date): ByteArray {
        val formatter = SimpleDateFormat(
            "yyMMddHHmmss'Z'",
            Locale.US
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        return formatter
            .format(date)
            .toByteArray(Charsets.US_ASCII)
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (e in elements) body.write(e)
        val bytes = body.toByteArray()
        return derTag(0x30, bytes)
    }

    private fun derSet(vararg elements: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        for (e in elements) body.write(e)
        val bytes = body.toByteArray()
        return derTag(0x31, bytes)
    }

    private fun derExplicit(tagNum: Int, value: ByteArray): ByteArray {
        return derTag(0xa0 or tagNum, value)
    }

    private fun derInteger(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return derTag(0x02, bytes)
    }

    private fun derOid(oidBytes: ByteArray): ByteArray {
        return derTag(0x06, oidBytes)
    }

    private fun derNull(): ByteArray {
        return byteArrayOf(0x05, 0x00)
    }

    private fun derUtf8String(str: String): ByteArray {
        val bytes = str.toByteArray(Charsets.UTF_8)
        return derTag(0x0c, bytes)
    }

    private fun derPrintableString(str: String): ByteArray {
        val bytes = str.toByteArray(Charsets.US_ASCII)
        return derTag(0x13, bytes)
    }

    private fun derUtcTime(timeBytes: ByteArray): ByteArray {
        return derTag(0x17, timeBytes)
    }

    private fun derBitString(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x00) // unused bits = 0
        out.write(bytes)
        return derTag(0x03, out.toByteArray())
    }

    private fun derTag(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = value.size
        if (len < 128) {
            out.write(len)
        } else if (len < 256) {
            out.write(0x81)
            out.write(len)
        } else if (len < 65536) {
            out.write(0x82)
            out.write(len ushr 8)
            out.write(len and 0xff)
        } else {
            out.write(0x83)
            out.write(len ushr 16)
            out.write((len ushr 8) and 0xff)
            out.write(len and 0xff)
        }
        out.write(value)
        return out.toByteArray()
    }
}
