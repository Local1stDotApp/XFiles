package app.local1st.files.core.util

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Writes a minimal self-signed X.509 v3 certificate.
 *
 * The platform already provides everything an APK signing identity needs — RSA key generation,
 * SHA-256 signing, certificate parsing — except assembling the certificate, which is the only
 * reason BouncyCastle used to be a dependency (0.6 MB of dex for this one call). The DER below
 * is the RFC 5280 minimum with no extensions; the public key is spliced in verbatim because
 * [java.security.PublicKey.getEncoded] already hands back a SubjectPublicKeyInfo.
 */
internal object SelfSignedCertificate {

    fun create(
        keyPair: KeyPair,
        commonName: String,
        notBefore: Date,
        notAfter: Date,
        serial: BigInteger,
    ): X509Certificate {
        require(keyPair.public.format == "X.509") {
            "Public key encoding is ${keyPair.public.format}, expected an X.509 SubjectPublicKeyInfo"
        }
        val algorithm = der(SEQUENCE, der(OBJECT_IDENTIFIER, OID_SHA256_WITH_RSA), der(NULL))
        // Issuer and subject are the same name — that is what makes the certificate self-signed.
        val name = der(
            SEQUENCE,
            der(
                SET,
                der(
                    SEQUENCE,
                    der(OBJECT_IDENTIFIER, OID_COMMON_NAME),
                    der(UTF8_STRING, commonName.toByteArray(Charsets.UTF_8)),
                ),
            ),
        )
        val tbsCertificate = der(
            SEQUENCE,
            der(EXPLICIT_VERSION, der(INTEGER, byteArrayOf(2))), // v3
            der(INTEGER, serial.toByteArray()),
            algorithm,
            name,
            der(SEQUENCE, time(notBefore), time(notAfter)),
            name,
            keyPair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }
        val certificate = der(
            SEQUENCE,
            tbsCertificate,
            algorithm,
            der(BIT_STRING, byteArrayOf(0) + signature), // 0 = no unused trailing bits
        )
        // Parsing back through the platform both hands out the X509Certificate callers need and
        // fails loudly here rather than deep inside apksig if the DER above is ever malformed.
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(certificate.inputStream()) as X509Certificate
    }

    /** RFC 5280 dates are UTCTime through 2049 and GeneralizedTime from 2050 on. */
    private fun time(date: Date): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val year = Calendar.getInstance(utc).apply { time = date }.get(Calendar.YEAR)
        val (tag, pattern) =
            if (year < 2050) UTC_TIME to "yyMMddHHmmss'Z'" else GENERALIZED_TIME to "yyyyMMddHHmmss'Z'"
        val text = SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(date)
        return der(tag, text.toByteArray(Charsets.US_ASCII))
    }

    /** Tag, definite length, then the concatenated parts. */
    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = parts.sumOf { it.size }
        if (length < 0x80) {
            out.write(length)
        } else {
            val octets = ArrayList<Int>(4)
            var remaining = length
            while (remaining > 0) {
                octets.add(remaining and 0xFF)
                remaining = remaining ushr 8
            }
            out.write(0x80 or octets.size)
            octets.asReversed().forEach(out::write)
        }
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private const val INTEGER = 0x02
    private const val BIT_STRING = 0x03
    private const val NULL = 0x05
    private const val OBJECT_IDENTIFIER = 0x06
    private const val UTF8_STRING = 0x0C
    private const val SEQUENCE = 0x30
    private const val SET = 0x31
    private const val UTC_TIME = 0x17
    private const val GENERALIZED_TIME = 0x18
    private const val EXPLICIT_VERSION = 0xA0 // [0] EXPLICIT

    /** sha256WithRSAEncryption, 1.2.840.113549.1.1.11. */
    private val OID_SHA256_WITH_RSA =
        byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)

    /** id-at-commonName, 2.5.4.3. */
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)
}
