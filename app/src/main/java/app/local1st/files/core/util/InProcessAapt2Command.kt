package app.local1st.files.core.util

import com.android.aapt.ConfigurationOuterClass.Configuration
import com.android.aapt.Resources
import com.android.tools.build.bundletool.androidtools.Aapt2Command
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.chunk.xml.ResXmlStartNamespace
import com.reandroid.arsc.pool.TableStringPool
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ResValueMap
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Pure-Java implementation of the subset of `aapt2 convert` used by bundletool build-apks. */
object InProcessAapt2Command : Aapt2Command {

    override fun convertApkProtoToBinary(
        protoApk: Path,
        outBinaryApk: Path,
        options: Aapt2Command.ConvertOptions,
    ) {
        rejectExoticOptions(options)
        ZipFile(protoApk.toFile()).use { input ->
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(outBinaryApk))).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val source = entries.nextElement()
                    if (source.isDirectory) continue
                    val targetName = if (source.name == "resources.pb") "resources.arsc" else source.name
                    val target = ZipEntry(targetName).apply { time = source.time }
                    output.putNextEntry(target)
                    input.getInputStream(source).use { raw ->
                        when {
                            source.name == "resources.pb" ->
                                writeTable(Resources.ResourceTable.parseFrom(raw), output, options.forceSparseEncoding)
                            isCompiledXml(source.name) ->
                                writeXml(
                                    Resources.XmlNode.parseFrom(raw),
                                    output,
                                    utf8 = source.name != "AndroidManifest.xml",
                                )
                            else -> raw.copyTo(output)
                        }
                    }
                    output.closeEntry()
                }
            }
        }
    }

    override fun optimizeToSparseResourceTables(originalApk: Path, outputApk: Path) {
        throw UnsupportedOperationException("Sparse resource-table optimization is not supported in-process")
    }

    private fun isCompiledXml(name: String): Boolean {
        if (name == "AndroidManifest.xml") return true
        if (!name.startsWith("res/") || !name.endsWith(".xml")) return false
        val resourceDirectory = name.substringAfter("res/").substringBefore('/')
        return resourceDirectory != "raw" && !resourceDirectory.startsWith("raw-")
    }

    private fun rejectExoticOptions(options: Aapt2Command.ConvertOptions) {
        val enabled = buildList {
            if (options.collapseResourceNames) add("collapseResourceNames")
            if (options.resourceConfigPath.isPresent) add("resourceConfigPath")
            if (options.deduplicateResourceEntries) add("deduplicateResourceEntries")
        }
        if (enabled.isNotEmpty()) {
            throw UnsupportedOperationException(
                "In-process AAPT2 conversion does not support: ${enabled.joinToString()}",
            )
        }
    }

    private fun writeTable(
        proto: Resources.ResourceTable,
        output: java.io.OutputStream,
        sparse: Boolean,
    ) {
        if (proto.overlayableCount != 0 || proto.packageList.any { pkg ->
                pkg.typeList.any { type -> type.entryList.any { it.hasOverlayableItem() } }
            }
        ) {
            throw UnsupportedOperationException("Overlayable resource metadata is not supported in-process")
        }
        val table = TableBlock()
        proto.packageList.forEach { protoPackage ->
            val packageBlock = table.newPackage(protoPackage.packageId.id, protoPackage.packageName)
            protoPackage.typeList.forEach { protoType ->
                // styleable is compile-time metadata for R generation and aapt2 does not emit it
                // into the runtime resources.arsc table.
                if (protoType.name == "styleable") return@forEach
                val typeId = protoType.typeId.id
                val pair = packageBlock.getOrCreateSpecTypePair(typeId, protoType.name)
                pair.specBlock.setEntryCount(
                    (protoType.entryList.maxOfOrNull { it.entryId.id } ?: -1) + 1,
                )
                protoType.entryList.forEach { protoEntry ->
                    protoEntry.configValueList.forEach { configValue ->
                        val typeBlock = pair.typeBlockArray.getOrCreate(
                            toResConfig(configValue.config),
                            sparse,
                        )
                        val entry = typeBlock.getOrCreateEntry(protoEntry.entryId.id.toShort())
                        entry.setName(protoEntry.name, true)
                        writeValue(entry, configValue.value, table.stringPool)
                    }
                    pair.specBlock.getSpecFlag(protoEntry.entryId.id)?.apply {
                        var flags = protoEntry.configValueList.firstOrNull()?.config?.let { first ->
                            protoEntry.configValueList.drop(1).fold(0) { result, value ->
                                result or configDiffMask(first, value.config)
                            }
                        } ?: 0
                        if (protoEntry.hasVisibility() &&
                            protoEntry.visibility.level == Resources.Visibility.Level.PUBLIC
                        ) flags = flags or 0x40000000
                        if (protoEntry.hasVisibility() && protoEntry.visibility.stagedApi) {
                            flags = flags or 0x20000000
                        }
                        integer = flags
                    }
                }
            }
            packageBlock.sortTypes()
        }
        table.refresh()
        table.writeBytes(output)
    }

    private fun writeValue(
        entry: Entry,
        value: Resources.Value,
        strings: TableStringPool,
    ) {
        when (value.valueCase) {
            Resources.Value.ValueCase.ITEM -> writeItem(entry, value.item, strings)
            Resources.Value.ValueCase.COMPOUND_VALUE -> writeCompound(entry, value.compoundValue, strings)
            else -> entry.setValueAsRaw(ValueType.NULL, 0)
        }
    }

    private fun writeItem(entry: Entry, item: Resources.Item, strings: TableStringPool) {
        when (item.valueCase) {
            Resources.Item.ValueCase.REF -> {
                val ref = item.ref
                val dynamic = ref.hasIsDynamic() && ref.isDynamic.value
                val type = when (ref.type) {
                    Resources.Reference.Type.ATTRIBUTE -> if (dynamic) ValueType.DYNAMIC_ATTRIBUTE else ValueType.ATTRIBUTE
                    else -> if (dynamic) ValueType.DYNAMIC_REFERENCE else ValueType.REFERENCE
                }
                entry.setValueAsRaw(type, ref.id)
            }
            Resources.Item.ValueCase.STR -> stringValue(entry, item.str.value, strings)
            Resources.Item.ValueCase.RAW_STR -> stringValue(entry, item.rawStr.value, strings)
            Resources.Item.ValueCase.FILE -> stringValue(entry, item.file.path, strings)
            Resources.Item.ValueCase.STYLED_STR -> {
                val styled = item.styledStr
                val string = strings.getOrCreate(styled.value)
                val style = string.getOrCreateStyle()
                styled.spanList.forEach { style.add(it.tag, it.firstChar, it.lastChar) }
                entry.setValueAsRaw(ValueType.STRING, string.index)
            }
            Resources.Item.ValueCase.ID -> entry.setValueAsRaw(ValueType.BOOLEAN, 0)
            Resources.Item.ValueCase.PRIM -> {
                val prim = item.prim
                when (prim.oneofValueCase) {
                    Resources.Primitive.OneofValueCase.NULL_VALUE -> entry.setValueAsRaw(ValueType.NULL, 0)
                    Resources.Primitive.OneofValueCase.EMPTY_VALUE -> entry.setValueAsRaw(ValueType.NULL, 1)
                    Resources.Primitive.OneofValueCase.FLOAT_VALUE ->
                        entry.setValueAsRaw(ValueType.FLOAT, prim.floatValue.toRawBits())
                    Resources.Primitive.OneofValueCase.DIMENSION_VALUE ->
                        entry.setValueAsRaw(ValueType.DIMENSION, prim.dimensionValue)
                    Resources.Primitive.OneofValueCase.FRACTION_VALUE ->
                        entry.setValueAsRaw(ValueType.FRACTION, prim.fractionValue)
                    Resources.Primitive.OneofValueCase.INT_DECIMAL_VALUE ->
                        entry.setValueAsRaw(ValueType.DEC, prim.intDecimalValue)
                    Resources.Primitive.OneofValueCase.INT_HEXADECIMAL_VALUE ->
                        entry.setValueAsRaw(ValueType.HEX, prim.intHexadecimalValue)
                    Resources.Primitive.OneofValueCase.BOOLEAN_VALUE ->
                        entry.setValueAsRaw(ValueType.BOOLEAN, if (prim.booleanValue) -1 else 0)
                    Resources.Primitive.OneofValueCase.COLOR_ARGB8_VALUE ->
                        entry.setValueAsRaw(ValueType.COLOR_ARGB8, prim.colorArgb8Value)
                    Resources.Primitive.OneofValueCase.COLOR_RGB8_VALUE ->
                        entry.setValueAsRaw(ValueType.COLOR_RGB8, prim.colorRgb8Value)
                    Resources.Primitive.OneofValueCase.COLOR_ARGB4_VALUE ->
                        entry.setValueAsRaw(ValueType.COLOR_ARGB4, prim.colorArgb4Value)
                    Resources.Primitive.OneofValueCase.COLOR_RGB4_VALUE ->
                        entry.setValueAsRaw(ValueType.COLOR_RGB4, prim.colorRgb4Value)
                    else -> entry.setValueAsRaw(ValueType.NULL, 0)
                }
            }
            else -> entry.setValueAsRaw(ValueType.NULL, 0)
        }
    }

    private fun stringValue(entry: Entry, value: String, strings: TableStringPool): ValueItem {
        val string = strings.getOrCreate(value)
        return entry.setValueAsRaw(ValueType.STRING, string.index)
    }

    private fun writeCompound(entry: Entry, compound: Resources.CompoundValue, strings: TableStringPool) {
        val values = mutableListOf<Triple<Int, ValueType, Int>>()
        var parent = 0
        when (compound.valueCase) {
            Resources.CompoundValue.ValueCase.STYLE -> {
                val style = compound.style
                if (style.hasParent()) parent = style.parent.id
                style.entryList.forEach { child -> values += itemMap(child.key.id, child.item, strings) }
            }
            Resources.CompoundValue.ValueCase.ARRAY -> compound.array.elementList.forEachIndexed { index, child ->
                values += itemMap(0x01000000 or (index + 1), child.item, strings)
            }
            Resources.CompoundValue.ValueCase.PLURAL -> compound.plural.entryList.forEach { child ->
                val name = when (child.arity) {
                    Resources.Plural.Arity.ZERO -> 0x01000005
                    Resources.Plural.Arity.ONE -> 0x01000006
                    Resources.Plural.Arity.TWO -> 0x01000007
                    Resources.Plural.Arity.FEW -> 0x01000008
                    Resources.Plural.Arity.MANY -> 0x01000009
                    else -> 0x01000004
                }
                values += itemMap(name, child.item, strings)
            }
            Resources.CompoundValue.ValueCase.ATTR -> {
                val attr = compound.attr
                values += Triple(0x01000000, ValueType.DEC, attr.formatFlags)
                if (attr.minInt != Int.MIN_VALUE) values += Triple(0x01000001, ValueType.DEC, attr.minInt)
                if (attr.maxInt != Int.MAX_VALUE) values += Triple(0x01000002, ValueType.DEC, attr.maxInt)
                attr.symbolList.forEach { symbol ->
                    values += Triple(symbol.name.id, ValueType.DEC, symbol.value)
                }
            }
            Resources.CompoundValue.ValueCase.STYLEABLE -> compound.styleable.entryList.forEachIndexed { index, child ->
                values += Triple(0x01000000 or (index + 1), ValueType.REFERENCE, child.attr.id)
            }
            Resources.CompoundValue.ValueCase.MACRO -> {
                stringValue(entry, compound.macro.rawString, strings)
                return
            }
            else -> Unit
        }
        entry.ensureComplex(true)
        val map = requireNotNull(entry.resTableMapEntry)
        map.parentId = parent
        values.sortBy { it.first }
        map.setValuesCount(values.size)
        values.forEachIndexed { index, (name, type, data) ->
            map.value[index].apply {
                nameId = name
                setTypeAndData(type, data)
            }
        }
    }

    private fun itemMap(name: Int, item: Resources.Item, strings: TableStringPool): Triple<Int, ValueType, Int> {
        val scratchTable = TableBlock()
        val scratchPackage = scratchTable.newPackage(0x7f, "scratch")
        val scratchEntry = scratchPackage.getOrCreateEntry(1, 0, ResConfig())
        scratchEntry.setName("value", true)
        // Write strings into the real pool so indices remain valid in the result table.
        val result = when (item.valueCase) {
            Resources.Item.ValueCase.REF -> {
                val ref = item.ref
                val dynamic = ref.hasIsDynamic() && ref.isDynamic.value
                val type = when (ref.type) {
                    Resources.Reference.Type.ATTRIBUTE -> if (dynamic) ValueType.DYNAMIC_ATTRIBUTE else ValueType.ATTRIBUTE
                    else -> if (dynamic) ValueType.DYNAMIC_REFERENCE else ValueType.REFERENCE
                }
                type to ref.id
            }
            Resources.Item.ValueCase.STR -> ValueType.STRING to strings.getOrCreate(item.str.value).index
            Resources.Item.ValueCase.RAW_STR -> ValueType.STRING to strings.getOrCreate(item.rawStr.value).index
            Resources.Item.ValueCase.FILE -> ValueType.STRING to strings.getOrCreate(item.file.path).index
            Resources.Item.ValueCase.STYLED_STR -> {
                val styled = item.styledStr
                val string = strings.getOrCreate(styled.value)
                val style = string.getOrCreateStyle()
                styled.spanList.forEach { style.add(it.tag, it.firstChar, it.lastChar) }
                ValueType.STRING to string.index
            }
            Resources.Item.ValueCase.PRIM -> {
                writeItem(scratchEntry, item, scratchTable.stringPool)
                scratchEntry.resValue.valueType to scratchEntry.resValue.data
            }
            Resources.Item.ValueCase.ID -> ValueType.BOOLEAN to 0
            else -> throw UnsupportedOperationException("Unsupported compound item: ${item.valueCase}")
        }
        return Triple(name, result.first, result.second)
    }

    private fun toResConfig(config: Configuration): ResConfig {
        // The pinned AAPT proto cannot represent qualifiers newer than it (e.g. Android 14 grammatical
        // gender). Such fields arrive as protobuf unknown fields; silently dropping them would collapse
        // distinct configs onto one ResConfig and overwrite entries. Reject rather than corrupt.
        if (config.unknownFields.serializedSize > 0 || config.product.isNotBlank()) {
            throw UnsupportedOperationException(
                "Unsupported resource configuration qualifier (unknown or unhandled): " +
                    config.toString().trim(),
            )
        }
        val qualifiers = buildList {
            if (config.mcc != 0) add("mcc${config.mcc}")
            if (config.mnc != 0) add("mnc${config.mnc}")
            if (config.locale.isNotBlank()) add("b+${config.locale.replace('-', '+')}")
            enumQualifier(config.layoutDirection.name, "LAYOUT_DIRECTION_")?.let(::add)
            if (config.smallestScreenWidthDp != 0) add("sw${config.smallestScreenWidthDp}dp")
            if (config.screenWidthDp != 0) add("w${config.screenWidthDp}dp")
            if (config.screenHeightDp != 0) add("h${config.screenHeightDp}dp")
            enumQualifier(config.screenLayoutSize.name, "SCREEN_LAYOUT_SIZE_")?.let(::add)
            enumQualifier(config.screenLayoutLong.name, "SCREEN_LAYOUT_LONG_")?.let(::add)
            enumQualifier(config.screenRound.name, "SCREEN_ROUND_")?.let(::add)
            enumQualifier(config.wideColorGamut.name, "WIDE_COLOR_GAMUT_")?.let(::add)
            enumQualifier(config.hdr.name, "HDR_")?.let(::add)
            enumQualifier(config.orientation.name, "ORIENTATION_")?.let(::add)
            enumQualifier(config.uiModeType.name, "UI_MODE_TYPE_")?.let(::add)
            enumQualifier(config.uiModeNight.name, "UI_MODE_NIGHT_")?.let(::add)
            if (config.density != 0) add(
                when (config.density) {
                    0xfffe -> "anydpi"
                    0xffff -> "nodpi"
                    else -> "${config.density}dpi"
                },
            )
            enumQualifier(config.touchscreen.name, "TOUCHSCREEN_")?.let(::add)
            enumQualifier(config.keysHidden.name, "KEYS_HIDDEN_")?.let(::add)
            enumQualifier(config.keyboard.name, "KEYBOARD_")?.let(::add)
            enumQualifier(config.navHidden.name, "NAV_HIDDEN_")?.let(::add)
            enumQualifier(config.navigation.name, "NAVIGATION_")?.let(::add)
            if (config.screenWidth != 0 && config.screenHeight != 0) {
                add("${maxOf(config.screenWidth, config.screenHeight)}x${minOf(config.screenWidth, config.screenHeight)}")
            }
            if (config.sdkVersion != 0) add("v${config.sdkVersion}")
        }
        return ResConfig.parse(qualifiers.joinToString("-"))
    }

    private fun configDiffMask(first: Configuration, second: Configuration): Int {
        var result = 0
        if (first.mcc != second.mcc) result = result or 0x0001
        if (first.mnc != second.mnc) result = result or 0x0002
        if (first.locale != second.locale) result = result or 0x0004
        if (first.touchscreenValue != second.touchscreenValue) result = result or 0x0008
        if (first.keyboardValue != second.keyboardValue) result = result or 0x0010
        if (first.keysHiddenValue != second.keysHiddenValue || first.navHiddenValue != second.navHiddenValue) {
            result = result or 0x0020
        }
        if (first.navigationValue != second.navigationValue) result = result or 0x0040
        if (first.orientationValue != second.orientationValue) result = result or 0x0080
        if (first.density != second.density) result = result or 0x0100
        if (first.screenWidth != second.screenWidth || first.screenHeight != second.screenHeight ||
            first.screenWidthDp != second.screenWidthDp || first.screenHeightDp != second.screenHeightDp
        ) result = result or 0x0200
        if (first.sdkVersion != second.sdkVersion) result = result or 0x0400
        if (first.screenLayoutSizeValue != second.screenLayoutSizeValue ||
            first.screenLayoutLongValue != second.screenLayoutLongValue
        ) result = result or 0x0800
        if (first.uiModeTypeValue != second.uiModeTypeValue ||
            first.uiModeNightValue != second.uiModeNightValue
        ) result = result or 0x1000
        if (first.smallestScreenWidthDp != second.smallestScreenWidthDp) result = result or 0x2000
        if (first.layoutDirectionValue != second.layoutDirectionValue) result = result or 0x4000
        if (first.screenRoundValue != second.screenRoundValue) result = result or 0x8000
        if (first.wideColorGamutValue != second.wideColorGamutValue || first.hdrValue != second.hdrValue) {
            result = result or 0x10000
        }
        return result
    }

    private fun enumQualifier(name: String, prefix: String): String? {
        val value = name.removePrefix(prefix).lowercase(Locale.ROOT)
        return value.takeUnless { it == name.lowercase(Locale.ROOT) || it == "unset" || it == "uset" || it == "unrecognized" }
    }

    private fun writeXml(proto: Resources.XmlNode, output: java.io.OutputStream, utf8: Boolean) {
        require(proto.hasElement()) { "Proto XML root is not an element" }
        val document = ResXmlDocument().apply { stringPool.isUtf8 = utf8 }
        val root = document.newElement()
        populateElement(root, proto, emptyMap())
        document.refresh()
        document.writeBytes(output)
    }

    private fun populateElement(
        target: ResXmlElement,
        sourceNode: Resources.XmlNode,
        inheritedPrefixes: Map<String, String>,
    ) {
        val source = sourceNode.element
        val sourceLine = when {
            sourceNode.hasSource() -> sourceNode.source.lineNumber
            source.namespaceDeclarationList.firstOrNull()?.hasSource() == true ->
                source.namespaceDeclarationList.first().source.lineNumber
            else -> 0
        }
        val prefixes = inheritedPrefixes.toMutableMap()
        source.namespaceDeclarationList.forEach { namespace ->
            (target.newNamespace(namespace.uri, namespace.prefix) as ResXmlStartNamespace).lineNumber =
                if (namespace.hasSource()) namespace.source.lineNumber else sourceLine
            prefixes[namespace.uri] = namespace.prefix
        }
        target.setName(source.name)
        target.setNamespace(source.namespaceUri.ifBlank { null }, prefixes[source.namespaceUri])
        target.lineNumber = sourceLine
        source.attributeList.forEach { sourceAttribute ->
            val attribute = target.newAttribute()
            attribute.setNamespace(sourceAttribute.namespaceUri.ifBlank { null }, prefixes[sourceAttribute.namespaceUri])
            attribute.setName(sourceAttribute.name, sourceAttribute.resourceId)
            if (sourceAttribute.hasCompiledItem()) {
                writeXmlItem(attribute, sourceAttribute.compiledItem)
            } else {
                attribute.setValueAsString(sourceAttribute.value)
            }
        }
        source.childList.forEach { child ->
            when (child.nodeCase) {
                Resources.XmlNode.NodeCase.ELEMENT ->
                    populateElement(target.createChildElement(), child, prefixes)
                Resources.XmlNode.NodeCase.TEXT -> if (child.text.isNotBlank()) {
                    target.createResXmlTextNode().apply {
                        text = child.text
                        lineNumber = if (child.hasSource()) child.source.lineNumber else 0
                    }
                }
                else -> Unit
            }
        }
    }

    private fun writeXmlItem(target: ValueItem, item: Resources.Item) {
        when (item.valueCase) {
            Resources.Item.ValueCase.REF -> {
                val ref = item.ref
                target.setTypeAndData(
                    if (ref.type == Resources.Reference.Type.ATTRIBUTE) ValueType.ATTRIBUTE else ValueType.REFERENCE,
                    ref.id,
                )
            }
            Resources.Item.ValueCase.STR -> target.setValueAsString(item.str.value)
            Resources.Item.ValueCase.RAW_STR -> target.setValueAsString(item.rawStr.value)
            Resources.Item.ValueCase.PRIM -> {
                val prim = item.prim
                when (prim.oneofValueCase) {
                    Resources.Primitive.OneofValueCase.NULL_VALUE -> target.setTypeAndData(ValueType.NULL, 0)
                    Resources.Primitive.OneofValueCase.EMPTY_VALUE -> target.setTypeAndData(ValueType.NULL, 1)
                    Resources.Primitive.OneofValueCase.FLOAT_VALUE -> target.setTypeAndData(ValueType.FLOAT, prim.floatValue.toRawBits())
                    Resources.Primitive.OneofValueCase.DIMENSION_VALUE -> target.setTypeAndData(ValueType.DIMENSION, prim.dimensionValue)
                    Resources.Primitive.OneofValueCase.FRACTION_VALUE -> target.setTypeAndData(ValueType.FRACTION, prim.fractionValue)
                    Resources.Primitive.OneofValueCase.INT_DECIMAL_VALUE -> target.setTypeAndData(ValueType.DEC, prim.intDecimalValue)
                    Resources.Primitive.OneofValueCase.INT_HEXADECIMAL_VALUE -> target.setTypeAndData(ValueType.HEX, prim.intHexadecimalValue)
                    Resources.Primitive.OneofValueCase.BOOLEAN_VALUE -> target.setTypeAndData(ValueType.BOOLEAN, if (prim.booleanValue) -1 else 0)
                    Resources.Primitive.OneofValueCase.COLOR_ARGB8_VALUE -> target.setTypeAndData(ValueType.COLOR_ARGB8, prim.colorArgb8Value)
                    Resources.Primitive.OneofValueCase.COLOR_RGB8_VALUE -> target.setTypeAndData(ValueType.COLOR_RGB8, prim.colorRgb8Value)
                    Resources.Primitive.OneofValueCase.COLOR_ARGB4_VALUE -> target.setTypeAndData(ValueType.COLOR_ARGB4, prim.colorArgb4Value)
                    Resources.Primitive.OneofValueCase.COLOR_RGB4_VALUE -> target.setTypeAndData(ValueType.COLOR_RGB4, prim.colorRgb4Value)
                    else -> target.setTypeAndData(ValueType.NULL, 0)
                }
            }
            else -> target.setValueAsString("")
        }
    }
}
