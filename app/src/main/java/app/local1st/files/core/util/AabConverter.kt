package app.local1st.files.core.util

import android.content.Context
import android.os.Build
import com.android.bundle.Devices.DeviceSpec
import com.android.tools.build.bundletool.commands.BuildApksCommand
import com.android.tools.build.bundletool.commands.ExtractApksCommand
import com.android.tools.build.bundletool.model.SigningConfiguration
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.UUID
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

object AabConverter {

    fun install(
        context: Context,
        bundle: File,
        label: String,
        onSigningKeyRegenerated: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        val workDir = File(app.cacheDir, "aab-install-${UUID.randomUUID()}")
        if (!workDir.mkdirs()) throw IllegalStateException("Cannot create bundle work directory")
        try {
            val deviceSpec = deviceSpec(app)
            val signingResult = signingConfiguration(app)
            if (signingResult.regenerated) onSigningKeyRegenerated?.invoke()
            val signing = signingResult.configuration
            val apkFiles = try {
                buildAndExtract(bundle, workDir, deviceSpec, signing, universal = false)
            } catch (first: Exception) {
                workDir.listFiles()?.forEach { it.deleteRecursively() }
                try {
                    buildAndExtract(bundle, workDir, deviceSpec, signing, universal = true)
                } catch (second: Exception) {
                    second.addSuppressed(first)
                    throw second
                } catch (second: LinkageError) {
                    // Universal mode merges dex through bundletool's D8, whose internals the
                    // vendored jar strips (see vendor/bundletool-shaded); only multi-module
                    // bundles with minSdk < 21 reach it. That arrives as a LinkageError, which
                    // the caller's Exception handler would miss — report the failure instead.
                    throw IllegalStateException(
                        "Cannot build APKs for this device, and the universal fallback needs " +
                            "dex merging that this build omits",
                        first,
                    )
                }
            }
            if (apkFiles.isEmpty()) throw IllegalStateException("Bundletool produced no installable APK")
            ApkInstaller.install(
                app,
                label,
                apkFiles.map { apk ->
                    ApkInstaller.ApkSource(apk.name, apk.length()) { FileInputStream(apk) }
                },
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun buildAndExtract(
        bundle: File,
        workDir: File,
        deviceSpec: DeviceSpec,
        signing: SigningConfiguration,
        universal: Boolean,
    ): List<File> {
        val apksArchive = File(workDir, if (universal) "universal.apks" else "device.apks")
        val builder = BuildApksCommand.builder()
            .setBundlePath(bundle.toPath())
            .setOutputFile(apksArchive.toPath())
            .setOverwriteOutput(true)
            .setSigningConfiguration(signing)
            .setAapt2Command(InProcessAapt2Command)
            .setEnableSparseEncoding(false)
            .setInjectMinSdk(false)
        if (universal) {
            builder.setApkBuildMode(BuildApksCommand.ApkBuildMode.UNIVERSAL)
        } else {
            builder.setDeviceSpec(deviceSpec)
        }
        builder.build().execute()

        val outputDir = File(workDir, if (universal) "universal" else "device")
        val paths = ExtractApksCommand.builder()
            .setApksArchivePath(apksArchive.toPath())
            .setDeviceSpec(deviceSpec)
            .setOutputDirectory(outputDir.toPath())
            .build()
            .execute()
        return paths.map { it.toFile() }.filter { it.isFile && it.extension.equals("apk", true) }
    }

    private fun deviceSpec(context: Context): DeviceSpec {
        val locales = context.resources.configuration.locales
        val languageTags = buildList {
            for (i in 0 until locales.size()) add(locales[i].toLanguageTag())
        }.filter { it.isNotBlank() }.distinct()
        return DeviceSpec.newBuilder()
            .addAllSupportedAbis(Build.SUPPORTED_ABIS.asIterable())
            .setScreenDensity(context.resources.displayMetrics.densityDpi)
            .setSdkVersion(Build.VERSION.SDK_INT)
            .addAllSupportedLocales(languageTags)
            .build()
    }

    private data class SigningResult(
        val configuration: SigningConfiguration,
        val regenerated: Boolean,
    )

    @Synchronized
    private fun signingConfiguration(context: Context): SigningResult {
        val provider = BouncyCastleProvider()
        val storeFile = File(context.filesDir, SIGNING_KEY_FILE)
        var regenerated = false
        if (storeFile.isFile) {
            try {
                val (privateKey, certificate) = loadSigningKey(storeFile, provider)
                return SigningResult(
                    SigningConfiguration.builder().setSignerConfig(privateKey, certificate).build(),
                    regenerated = false,
                )
            } catch (_: Exception) {
                if (!storeFile.delete()) throw IllegalStateException("Cannot replace the invalid bundle signing key")
                regenerated = true
            }
        }

        val keyPair = KeyPairGenerator.getInstance("RSA", provider).apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val name = X500Name("CN=XFiles AAB Installer")
        val certificateBuilder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(159, SecureRandom()).setBit(158),
            Date(now - DAY_MILLIS),
            Date(now + CERT_VALIDITY_MILLIS),
            name,
            keyPair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(certificateBuilder.build(signer))
        val temp = File(context.filesDir, "$SIGNING_KEY_FILE.tmp")
        DataOutputStream(FileOutputStream(temp).buffered()).use { output ->
            output.writeInt(KEY_FILE_VERSION)
            output.writeInt(keyPair.private.encoded.size)
            output.write(keyPair.private.encoded)
            output.writeInt(certificate.encoded.size)
            output.write(certificate.encoded)
        }
        if (!temp.renameTo(storeFile)) {
            temp.delete()
            throw IllegalStateException("Cannot save the bundle signing key")
        }
        return SigningResult(
            SigningConfiguration.builder().setSignerConfig(keyPair.private, certificate).build(),
            regenerated,
        )
    }

    private fun loadSigningKey(
        file: File,
        provider: BouncyCastleProvider,
    ): Pair<PrivateKey, X509Certificate> = DataInputStream(FileInputStream(file).buffered()).use { input ->
        if (input.readInt() != KEY_FILE_VERSION) throw IllegalStateException("Unknown bundle signing key format")
        val keyBytes = ByteArray(input.readInt().takeIf { it in 1..MAX_KEY_BYTES }
            ?: throw IllegalStateException("Invalid bundle signing key"))
        input.readFully(keyBytes)
        val certificateBytes = ByteArray(input.readInt().takeIf { it in 1..MAX_KEY_BYTES }
            ?: throw IllegalStateException("Invalid bundle signing certificate"))
        input.readFully(certificateBytes)
        val privateKey = KeyFactory.getInstance("RSA", provider)
            .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
        val certificate = CertificateFactory.getInstance("X.509", provider)
            .generateCertificate(certificateBytes.inputStream()) as X509Certificate
        privateKey to certificate
    }

    private const val SIGNING_KEY_FILE = "aab-signing-key.bin"
    private const val KEY_FILE_VERSION = 1
    private const val MAX_KEY_BYTES = 1024 * 1024
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    private const val CERT_VALIDITY_MILLIS = 100L * 365 * DAY_MILLIS
}
