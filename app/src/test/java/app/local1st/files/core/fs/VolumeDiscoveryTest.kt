package app.local1st.files.core.fs

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeDiscoveryTest {

    @Test
    fun primaryAndEmulatedVolumesAreInternalEvenWhenTheLabelMentionsUsb() {
        assertEquals(
            EntryKind.VOLUME_INTERNAL,
            volumeKind(
                isPrimary = true,
                isEmulated = true,
                isRemovable = false,
                description = "Internal shared storage",
                path = "/storage/emulated/0",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_INTERNAL,
            volumeKind(
                isPrimary = false,
                isEmulated = true,
                isRemovable = true,
                description = "USB drive",
                path = "/storage/emulated/0",
            ),
        )
    }

    @Test
    fun usbDescriptionPathOrDiskBitWinsOverRemovableSd() {
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "USB drive",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "OTG 存储",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "U盘",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "U 盘",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "U盤",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "优盘",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "隨身碟",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "Kingston",
                path = "/mnt/usb_storage/USB_DISK0",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_USB,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "Kingston",
                path = "/storage/ABCD-1234",
                diskIsUsb = true,
            ),
        )
    }

    @Test
    fun removableWithoutUsbCuesIsAnSdCard() {
        assertEquals(
            EntryKind.VOLUME_SD,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "SD card",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_SD,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "Kingston",
                path = "/storage/ABCD-1234",
                diskIsUsb = false,
            ),
        )
        // Brand names containing 'u' plus 盘 must not become USB.
        assertEquals(
            EntryKind.VOLUME_SD,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "Kingston 存储盘",
                path = "/storage/ABCD-1234",
            ),
        )
        assertEquals(
            EntryKind.VOLUME_SD,
            volumeKind(
                isPrimary = false,
                isEmulated = false,
                isRemovable = true,
                description = "Fujitsu 磁盘",
                path = "/storage/ABCD-1234",
            ),
        )
    }
}
