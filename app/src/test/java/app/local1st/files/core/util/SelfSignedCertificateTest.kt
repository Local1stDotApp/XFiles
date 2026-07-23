package app.local1st.files.core.util

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the hand-written DER in [SelfSignedCertificate]. A malformed certificate would only
 * surface deep inside apksig while installing a bundle, so assert the shape here instead.
 */
class SelfSignedCertificateTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun selfSignedCertificateIsWellFormed() {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + 100L * 365 * 24 * 60 * 60 * 1000)
        val serial = BigInteger(159, SecureRandom()).setBit(158)

        val certificate = SelfSignedCertificate.create(
            keyPair = keyPair,
            commonName = "XFiles AAB Installer",
            notBefore = notBefore,
            notAfter = notAfter,
            serial = serial,
        )

        // Verifying against our own public key is what "self-signed" means, and it only passes
        // if the signature covers exactly the TBSCertificate bytes we emitted.
        certificate.verify(keyPair.public)

        assertEquals(3, certificate.version)
        assertEquals(serial, certificate.serialNumber)
        assertEquals(keyPair.public, certificate.publicKey)
        assertEquals("SHA256withRSA", certificate.sigAlgName)
        val expectedName = X500Principal("CN=XFiles AAB Installer")
        assertEquals(expectedName, certificate.subjectX500Principal)
        assertEquals(expectedName, certificate.issuerX500Principal)

        // Certificates carry second precision, so compare after truncating.
        assertEquals(notBefore.time / 1000, certificate.notBefore.time / 1000)
        assertEquals(notAfter.time / 1000, certificate.notAfter.time / 1000)
        certificate.checkValidity(Date(now))
    }

    /** The 100-year validity above crosses 2050, where RFC 5280 switches to GeneralizedTime. */
    @Test
    fun datesOnBothSidesOfTheUtcTimeCutoffRoundTrip() {
        val beforeCutoff = Date(2_524_608_000_000L) // 2050-01-01T00:00:00Z minus a year
        val afterCutoff = Date(4_102_444_800_000L) // 2100-01-01T00:00:00Z
        val certificate = SelfSignedCertificate.create(
            keyPair = keyPair,
            commonName = "XFiles",
            notBefore = beforeCutoff,
            notAfter = afterCutoff,
            serial = BigInteger.ONE,
        )
        certificate.verify(keyPair.public)
        assertEquals(beforeCutoff.time / 1000, certificate.notBefore.time / 1000)
        assertEquals(afterCutoff.time / 1000, certificate.notAfter.time / 1000)
    }

    @Test
    fun encodingSurvivesALongCommonName() {
        // Names past 127 bytes force DER's long-form length encoding.
        val longName = "XFiles ".repeat(30).trim()
        val certificate = SelfSignedCertificate.create(
            keyPair = keyPair,
            commonName = longName,
            notBefore = Date(0),
            notAfter = Date(4_102_444_800_000L),
            serial = BigInteger.TEN,
        )
        certificate.verify(keyPair.public)
        assertTrue(certificate.subjectX500Principal.name.contains(longName))
    }
}
