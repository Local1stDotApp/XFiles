package app.local1st.files.core.fs

/**
 * Classifies a [android.os.storage.StorageVolume] into the pane-root kind the tree shows.
 *
 * [diskIsUsb] is the hidden DiskInfo bit when reflection can read it; null means "unknown",
 * never "not USB". Public StorageVolume fields plus the system description cover the rest.
 */
internal fun volumeKind(
    isPrimary: Boolean,
    isEmulated: Boolean,
    isRemovable: Boolean,
    description: String,
    path: String,
    diskIsUsb: Boolean? = null,
): EntryKind {
    if (isPrimary || isEmulated) return EntryKind.VOLUME_INTERNAL
    if (diskIsUsb == true || looksLikeUsb(description, path)) return EntryKind.VOLUME_USB
    if (isRemovable) return EntryKind.VOLUME_SD
    return EntryKind.VOLUME_INTERNAL
}

internal fun looksLikeUsb(description: String, path: String): Boolean {
    val text = description.lowercase()
    if ("usb" in text || "otg" in text) return true
    if (looksLikeChineseUsb(description)) return true
    val lowerPath = path.lowercase()
    return "/usb" in lowerPath || lowerPath.contains("usbotg")
}

/**
 * Chinese USB names: "U盘"/"U盤" as a unit (not the letter u inside a brand plus 盘),
 * plus 优盘 and 隨身碟.
 */
private fun looksLikeChineseUsb(description: String): Boolean {
    val compact = description.lowercase().replace(" ", "").replace("-", "")
    if ("u盘" in compact || "u盤" in compact) return true
    if ("优盘" in description || "優盤" in description) return true
    return "随身碟" in description || "隨身碟" in description
}
