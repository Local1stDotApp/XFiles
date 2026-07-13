package app.local1st.files.core.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** One signing certificate, reduced to the fields worth showing. */
data class CertInfo(
    val sha256: String,
    val sha1: String,
    val issuer: String,
    val subject: String,
    val serial: String,
    val algorithm: String,
    val validFrom: Long,
    val validTo: Long,
)

/** A group of component names (activities / services / receivers / providers). */
data class ComponentGroup(val title: String, val names: List<String>)

/** Everything we can cheaply surface about an installed app. */
data class AppDetails(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val apkSize: Long,
    val sourceDir: String,
    val splitCount: Int,
    val dataDir: String,
    val uid: Int,
    val enabled: Boolean,
    val system: Boolean,
    val debuggable: Boolean,
    val installerPackage: String?,
    val certificates: List<CertInfo>,
    val permissions: List<String>,
    val components: List<ComponentGroup>,
    val features: List<String>,
)

/** Reads structured [AppDetails] via [PackageManager]. Call off the main thread. */
object AppInspector {

    fun inspect(context: Context, packageName: String): AppDetails? {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_PERMISSIONS or
            PackageManager.GET_CONFIGURATIONS or
            signingFlag()
        val pkg = try {
            getPackageInfo(pm, packageName, flags)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val app = pkg.applicationInfo ?: return null

        val splitCount = app.splitSourceDirs?.size ?: 0
        return AppDetails(
            packageName = packageName,
            label = app.loadLabel(pm).toString(),
            versionName = pkg.versionName ?: "?",
            versionCode = versionCodeOf(pkg),
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) app.minSdkVersion else 0,
            targetSdk = app.targetSdkVersion,
            compileSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) app.compileSdkVersion else 0,
            firstInstallTime = pkg.firstInstallTime,
            lastUpdateTime = pkg.lastUpdateTime,
            apkSize = app.sourceDir?.let { java.io.File(it).length() } ?: -1L,
            sourceDir = app.sourceDir ?: "",
            splitCount = splitCount,
            dataDir = app.dataDir ?: "",
            uid = app.uid,
            enabled = app.enabled,
            system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            debuggable = app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            installerPackage = installerOf(pm, packageName),
            certificates = readCertificates(pkg),
            permissions = pkg.requestedPermissions?.toList().orEmpty().sorted(),
            components = listOf(
                ComponentGroup("Activities", pkg.activities?.map { it.name }.orEmpty()),
                ComponentGroup("Services", pkg.services?.map { it.name }.orEmpty()),
                ComponentGroup("Receivers", pkg.receivers?.map { it.name }.orEmpty()),
                ComponentGroup("Providers", pkg.providers?.map { it.name }.orEmpty()),
            ),
            features = pkg.reqFeatures?.mapNotNull { it.name }.orEmpty().sorted(),
        )
    }

    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES

    private fun getPackageInfo(pm: PackageManager, pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }

    private fun versionCodeOf(pkg: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
        else @Suppress("DEPRECATION") pkg.versionCode.toLong()

    private fun installerOf(pm: PackageManager, packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }

    private fun readCertificates(pkg: PackageInfo): List<CertInfo> {
        val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.signingInfo?.let { info ->
                if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkg.signatures
        }
        return signatures.orEmpty().mapNotNull { toCertInfo(it) }
    }

    private fun toCertInfo(sig: Signature): CertInfo? {
        val der = sig.toByteArray()
        val x509 = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }.getOrNull()
        return CertInfo(
            sha256 = hash(der, "SHA-256"),
            sha1 = hash(der, "SHA-1"),
            issuer = x509?.issuerX500Principal?.name ?: "",
            subject = x509?.subjectX500Principal?.name ?: "",
            serial = x509?.serialNumber?.toString(16)?.uppercase() ?: "",
            algorithm = x509?.sigAlgName ?: "",
            validFrom = x509?.notBefore?.time ?: 0L,
            validTo = x509?.notAfter?.time ?: 0L,
        )
    }

    private fun hash(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes)
            .joinToString(":") { "%02X".format(it) }
}
