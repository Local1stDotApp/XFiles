package app.local1st.files.core.util

import com.android.bundle.Devices.DeviceSpec
import com.android.tools.build.bundletool.androidtools.Aapt2Command
import com.android.tools.build.bundletool.commands.BuildApksCommand
import com.android.tools.build.bundletool.commands.ExtractApksCommand
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only differential acceptance harness; none of this code is packaged in the Android app. */
class AabConverterHarnessTest {

    @Test
    fun rawXmlResourcesAreCopiedWithoutProtoParsing() {
        val input = Files.createTempFile("proto-with-raw-xml", ".apk")
        val output = Files.createTempFile("binary-with-raw-xml", ".apk")
        val rawXml = "<?xml version=\"1.0\"?><resources/>".toByteArray()
        try {
            ZipOutputStream(Files.newOutputStream(input)).use { zip ->
                listOf("res/raw/keep.xml", "res/raw-en/keep.xml").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(rawXml)
                    zip.closeEntry()
                }
            }

            InProcessAapt2Command.convertApkProtoToBinary(
                input,
                output,
                Aapt2Command.ConvertOptions.builder().build(),
            )

            ZipFile(output.toFile()).use { zip ->
                listOf("res/raw/keep.xml", "res/raw-en/keep.xml").forEach { name ->
                    assertArrayEquals(rawXml, zip.getInputStream(zip.getEntry(name)).use { it.readBytes() })
                }
            }
        } finally {
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
        }
    }

    @Test
    fun differentialAndFullPipeline() {
        org.junit.Assume.assumeTrue(
            "Set -Dxfiles.harness=true to run the AAB differential acceptance harness",
            System.getProperty("xfiles.harness") == "true",
        )
        val repo = File(requireNotNull(System.getProperty("xfiles.repo")))
        val aapt2 = Path.of(requireNotNull(System.getProperty("xfiles.aapt2")))
        val bundles = buildList {
            System.getProperty("xfiles.reviewerBundle")?.takeIf { it.isNotBlank() }?.let {
                add("reviewer fixture" to File(it))
            }
            add("XFiles" to File(repo, "app/build/outputs/bundle/debug/app-debug.aab"))
        }
        bundles.forEach { (_, bundle) -> assertTrue("Missing test bundle: $bundle", bundle.isFile) }

        val root = Files.createTempDirectory(File(repo, "app/build").toPath(), "aab-differential-")
        try {
            val host = Aapt2Command.createFromExecutablePath(aapt2)
            bundles.forEachIndexed { bundleIndex, (label, bundle) ->
                DEVICE_CASES.forEachIndexed { specIndex, deviceCase ->
                    val caseLabel = "$label / ${deviceCase.label}"
                    val bundleDir = Files.createDirectories(root.resolve("bundle-$bundleIndex/spec-$specIndex"))
                    val captured = Files.createDirectories(bundleDir.resolve("proto"))
                    val hostApks = bundleDir.resolve("host.apks")
                    val capturingHost = CapturingCommand(host, captured)
                    build(bundle.toPath(), hostApks, capturingHost, deviceCase.spec)

                    val protoSplits = capturingHost.conversions()
                    assertTrue("No proto splits captured for $caseLabel", protoSplits.isNotEmpty())
                    val javaBinaries = mutableListOf<Path>()
                    protoSplits.forEachIndexed { splitIndex, conversion ->
                        val javaBinary = bundleDir.resolve("split-$splitIndex-java.apk")
                        val hostBinary = bundleDir.resolve("split-$splitIndex-host.apk")
                        val options = Aapt2Command.ConvertOptions.builder().build()
                        InProcessAapt2Command.convertApkProtoToBinary(conversion.protoApk, javaBinary, options)
                        host.convertApkProtoToBinary(conversion.protoApk, hostBinary, options)
                        assertApkSemanticEquals(hostBinary, javaBinary, caseLabel)
                        if (label == "reviewer fixture") {
                            val sparseJava = bundleDir.resolve("split-$splitIndex-sparse-java.apk")
                            val sparseHost = bundleDir.resolve("split-$splitIndex-sparse-host.apk")
                            InProcessAapt2Command.convertApkProtoToBinary(
                                conversion.protoApk,
                                sparseJava,
                                conversion.options,
                            )
                            host.convertApkProtoToBinary(conversion.protoApk, sparseHost, conversion.options)
                            assertApkSemanticEquals(sparseHost, sparseJava, "$caseLabel / build options")
                            javaBinaries.add(sparseJava)
                        } else {
                            javaBinaries.add(javaBinary)
                        }
                    }
                    if (label == "reviewer fixture") {
                        assertAapt2StringResources(aapt2, javaBinaries, FIXTURE_STRINGS)
                    }

                    val javaApks = bundleDir.resolve("java.apks")
                    build(bundle.toPath(), javaApks, InProcessAapt2Command, deviceCase.spec)
                    assertInstallableLooking(javaApks)
                    val extractedDir = bundleDir.resolve("extracted")
                    val extracted = ExtractApksCommand.builder()
                        .setApksArchivePath(javaApks)
                        .setDeviceSpec(deviceCase.spec)
                        .setOutputDirectory(extractedDir)
                        .build()
                        .execute()
                    val baseApk = extracted.singleOrNull { it.fileName.toString() == "base-master.apk" }
                    assertTrue("Extracted base-master.apk missing for $caseLabel: $extracted", baseApk != null)
                    if (label == "reviewer fixture") {
                        assertAapt2StringResources(aapt2, listOf(requireNotNull(baseApk)), FIXTURE_STRINGS)
                    }
                    println(
                        "AAB differential + extracted-base dump OK: $caseLabel " +
                            "(${protoSplits.size} proto splits)",
                    )
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun build(bundle: Path, output: Path, aapt2: Aapt2Command, deviceSpec: DeviceSpec) {
        BuildApksCommand.builder()
            .setBundlePath(bundle)
            .setOutputFile(output)
            .setOverwriteOutput(true)
            .setDeviceSpec(deviceSpec)
            .setAapt2Command(aapt2)
            .setEnableSparseEncoding(false)
            .setInjectMinSdk(false)
            .build()
            .execute()
    }

    private data class DeviceCase(val label: String, val spec: DeviceSpec)

    private fun assertApkSemanticEquals(expected: Path, actual: Path, label: String) {
        ZipFile(expected.toFile()).use { left ->
            ZipFile(actual.toFile()).use { right ->
                val names = left.entries().asSequence().map { it.name }
                    .filter { it == "resources.arsc" || it == "AndroidManifest.xml" || (it.startsWith("res/") && it.endsWith(".xml")) }
                    .toSortedSet()
                val actualNames = right.entries().asSequence().map { it.name }
                    .filter { it == "resources.arsc" || it == "AndroidManifest.xml" || (it.startsWith("res/") && it.endsWith(".xml")) }
                    .toSortedSet()
                assertEquals("Binary resource/XML file set mismatch for $label", names, actualNames)
                names.forEach { name ->
                    val leftEntry = requireNotNull(left.getEntry(name))
                    val rightEntry = requireNotNull(right.getEntry(name))
                    if (name == "resources.arsc") {
                        val leftSemantic = left.getInputStream(leftEntry).use { tableSemantic(TableBlock.load(it)) }
                        val rightSemantic = right.getInputStream(rightEntry).use { tableSemantic(TableBlock.load(it)) }
                        assertTableSemanticEquals(leftSemantic, rightSemantic, label)
                    } else {
                        val leftSemantic = left.getInputStream(leftEntry).use {
                            ResXmlDocument().apply { readBytes(it) }.toJson().toString()
                        }
                        val rightSemantic = right.getInputStream(rightEntry).use {
                            ResXmlDocument().apply { readBytes(it) }.toJson().toString()
                        }
                        assertEquals("Semantic mismatch in $name", leftSemantic, rightSemantic)
                    }
                }
            }
        }
    }

    /**
     * ARSCLib JSON preserves physical chunk and string-pool order. Those are allowed to differ,
     * so build a hierarchy keyed by every package/type/entry/config identity and compare each
     * complete key set in both directions before comparing decoded values.
     */
    private fun tableSemantic(table: TableBlock): TableSemantic {
        val packages = linkedMapOf<Int, PackageSemantic>()
        table.packages.forEachRemaining { packageBlock ->
            val types = linkedMapOf<Int, TypeSemantic>()
            packageBlock.specTypePairs.forEachRemaining { pair ->
                val entryInstances = mutableListOf<EntryInstance>()
                pair.typeBlocks.forEachRemaining { typeBlock ->
                    typeBlock.entryArray.iterator(true).forEachRemaining { entry ->
                        val scalar = entry.resValue
                        val compound = entry.resTableMapEntry
                        entryInstances += EntryInstance(
                            entryId = entry.id,
                            entryName = entry.name,
                            specFlags = entry.specFlag?.integer ?: 0,
                            config = typeBlock.resConfig.bytes.toHex(),
                            value = ConfigValueSemantic(
                                parentId = compound?.parentId,
                                values = when {
                                    scalar != null -> listOf(valueSemantic(0, scalar))
                                    compound != null -> compound.value.iterator().asSequence()
                                        .map { valueSemantic(it.nameId, it) }
                                        .sortedBy(ValueSemantic::nameId)
                                        .toList()
                                    else -> emptyList()
                                },
                            ),
                        )
                    }
                }
                val entries = linkedMapOf<Int, EntrySemantic>()
                entryInstances.groupBy(EntryInstance::entryId).forEach { (entryId, instances) ->
                    val first = instances.first()
                    require(instances.all { it.entryName == first.entryName }) {
                        "Conflicting names for type ${pair.typeName} entry $entryId"
                    }
                    require(instances.all { it.specFlags == first.specFlags }) {
                        "Conflicting spec flags for type ${pair.typeName} entry $entryId"
                    }
                    val configs = instances.associate { it.config to it.value }
                    require(configs.size == instances.size) {
                        "Duplicate configs for type ${pair.typeName} entry ${first.entryName}"
                    }
                    entries[entryId] = EntrySemantic(first.entryName, first.specFlags, configs)
                }
                val previousType = types.put(pair.id, TypeSemantic(pair.typeName, entries))
                require(previousType == null) { "Duplicate type id ${pair.id} in ${packageBlock.name}" }
            }
            val previousPackage = packages.put(packageBlock.id, PackageSemantic(packageBlock.name, types))
            require(previousPackage == null) { "Duplicate package id ${packageBlock.id}" }
        }
        return TableSemantic(packages)
    }

    private fun assertTableSemanticEquals(expected: TableSemantic, actual: TableSemantic, label: String) {
        assertEquals("Package set mismatch for $label", expected.packages.keys, actual.packages.keys)
        expected.packages.forEach { (packageId, expectedPackage) ->
            val actualPackage = requireNotNull(actual.packages[packageId])
            assertEquals("Package name mismatch for $label package $packageId", expectedPackage.name, actualPackage.name)
            assertEquals(
                "Type set mismatch for $label package ${expectedPackage.name}",
                expectedPackage.types.keys,
                actualPackage.types.keys,
            )
            expectedPackage.types.forEach { (typeId, expectedType) ->
                val actualType = requireNotNull(actualPackage.types[typeId])
                assertEquals("Type name mismatch for $label type $typeId", expectedType.name, actualType.name)
                assertEquals(
                    "Entry set mismatch for $label type ${expectedType.name}",
                    expectedType.entries.keys,
                    actualType.entries.keys,
                )
                expectedType.entries.forEach { (entryId, expectedEntry) ->
                    val actualEntry = requireNotNull(actualType.entries[entryId])
                    assertEquals(
                        "Entry name mismatch for $label ${expectedType.name}/$entryId",
                        expectedEntry.name,
                        actualEntry.name,
                    )
                    assertEquals(
                        "Spec flags mismatch for $label ${expectedType.name}/${expectedEntry.name}",
                        expectedEntry.specFlags,
                        actualEntry.specFlags,
                    )
                    assertEquals(
                        "Config set mismatch for $label ${expectedType.name}/${expectedEntry.name}",
                        expectedEntry.configValues.keys,
                        actualEntry.configValues.keys,
                    )
                    expectedEntry.configValues.forEach { (config, expectedValue) ->
                        assertEquals(
                            "Value mismatch for $label ${expectedType.name}/${expectedEntry.name} config $config",
                            expectedValue,
                            actualEntry.configValues[config],
                        )
                    }
                }
            }
        }
    }

    private fun valueSemantic(nameId: Int, value: ValueItem): ValueSemantic = ValueSemantic(
        nameId = nameId,
        type = value.valueType?.name,
        data = if (value.valueType == ValueType.STRING) null else value.data,
        decodedString = value.valueAsString,
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class TableSemantic(val packages: Map<Int, PackageSemantic>)

    private data class PackageSemantic(
        val name: String?,
        val types: Map<Int, TypeSemantic>,
    )

    private data class TypeSemantic(
        val name: String?,
        val entries: Map<Int, EntrySemantic>,
    )

    private data class EntrySemantic(
        val name: String?,
        val specFlags: Int,
        val configValues: Map<String, ConfigValueSemantic>,
    )

    private data class EntryInstance(
        val entryId: Int,
        val entryName: String?,
        val specFlags: Int,
        val config: String,
        val value: ConfigValueSemantic,
    )

    private data class ConfigValueSemantic(
        val parentId: Int?,
        val values: List<ValueSemantic>,
    )

    private data class ValueSemantic(
        val nameId: Int,
        val type: String?,
        val data: Int?,
        val decodedString: String?,
    )

    private fun assertAapt2StringResources(
        aapt2: Path,
        apks: List<Path>,
        expected: Map<String, String>,
    ) {
        val dumped = linkedMapOf<String, MutableSet<String>>()
        apks.forEach { apk ->
            val process = ProcessBuilder(aapt2.toString(), "dump", "resources", apk.toString())
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals("aapt2 dump resources failed for $apk:\n$output", 0, process.waitFor())

            var currentString: String? = null
            output.lineSequence().forEach { line ->
                val resource = RESOURCE_LINE.matchEntire(line)
                if (resource != null) {
                    currentString = resource.groupValues[1]
                } else if (line.trimStart().startsWith("resource ")) {
                    currentString = null
                } else {
                    val value = STRING_VALUE_LINE.matchEntire(line)
                    if (currentString != null && value != null) {
                        dumped.getOrPut(requireNotNull(currentString)) { linkedSetOf() } += value.groupValues[1]
                    }
                }
            }
        }
        expected.forEach { (name, value) ->
            assertEquals(
                "Host aapt2 did not resolve string/$name to the expected value",
                setOf(value),
                dumped[name],
            )
        }
    }

    private fun assertInstallableLooking(apks: Path) {
        ZipFile(apks.toFile()).use { archive ->
            val apkEntries = archive.entries().asSequence().filter { it.name.endsWith(".apk") }.toList()
            assertTrue("No APKs in $apks", apkEntries.isNotEmpty())
            apkEntries.forEach { apk ->
                assertTrue("Empty APK ${apk.name}", apk.size > 1024)
                archive.getInputStream(apk).use { input ->
                    val magic = ByteArray(4)
                    input.read(magic)
                    assertTrue("Bad zip magic for ${apk.name}", magic.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
                }
            }
        }
    }

    private class CapturingCommand(
        private val delegate: Aapt2Command,
        private val outputDir: Path,
    ) : Aapt2Command {
        private val next = AtomicInteger()
        private val captured = ConcurrentHashMap<Int, CapturedConversion>()

        override fun convertApkProtoToBinary(
            protoApk: Path,
            outBinaryApk: Path,
            options: Aapt2Command.ConvertOptions,
        ) {
            val index = next.getAndIncrement()
            val capturedProto = outputDir.resolve("proto-$index.apk")
            Files.copy(protoApk, capturedProto)
            captured[index] = CapturedConversion(capturedProto, options)
            delegate.convertApkProtoToBinary(protoApk, outBinaryApk, options)
        }

        fun conversions(): List<CapturedConversion> = captured.entries
            .sortedBy { it.key }
            .map { it.value }

        override fun optimizeToSparseResourceTables(originalApk: Path, outputApk: Path) =
            delegate.optimizeToSparseResourceTables(originalApk, outputApk)
    }

    private data class CapturedConversion(
        val protoApk: Path,
        val options: Aapt2Command.ConvertOptions,
    )

    private companion object {
        val FIXTURE_STRINGS = mapOf(
            "app_name" to "AAB Test",
            "hello" to "Hello from AAB",
        )
        val DEVICE_CASES = listOf(420, 480, 560).flatMap { density ->
            listOf(
                DeviceCase("density=$density, abis=x86_64", deviceSpec(density, listOf("x86_64"))),
                DeviceCase(
                    "density=$density, abis=x86_64+arm64-v8a",
                    deviceSpec(density, listOf("x86_64", "arm64-v8a")),
                ),
            )
        }
        val RESOURCE_LINE = Regex("""\s*resource\s+0x[0-9a-fA-F]+\s+string/(\S+)\s*""")
        val STRING_VALUE_LINE = Regex("""\s*\([^)]*\)\s+\"(.*)\"\s*""")

        fun deviceSpec(density: Int, abis: List<String>): DeviceSpec = DeviceSpec.newBuilder()
            .addAllSupportedAbis(abis)
            .setScreenDensity(density)
            .setSdkVersion(36)
            .addSupportedLocales("en-US")
            .build()
    }
}
