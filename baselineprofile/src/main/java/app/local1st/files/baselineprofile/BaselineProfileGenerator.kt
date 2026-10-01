package app.local1st.files.baselineprofile

import android.graphics.Rect
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

private const val PACKAGE = "app.local1st.files"
private const val TIMEOUT_MS = 5_000L

// Seeded under Pictures so a fresh emulator has a folder of real thumbnails to expand.
private const val SAMPLE_DIR = "XFilesProfile"
private const val SAMPLE_PATH = "/sdcard/Pictures/$SAMPLE_DIR"
private val SAMPLE_IMAGES = (1..12).map { "%02d.png".format(it) }
private val SAMPLE_OTHERS = listOf("notes.txt", "readme.md", "list.csv", "log.txt")

private val ROW_MARK = By.desc(Pattern.compile("Expand|Collapse|Select|Deselect"))

/**
 * A tree row: it carries a chevron or a selection mark. The pane switcher chip above the list
 * is long-clickable and shows the focused folder's name too, but has neither.
 */
private fun rowSelector(): BySelector = By.longClickable(true).hasDescendant(ROW_MARK, 2)

private fun rowSelector(label: String): BySelector = rowSelector().hasChild(By.text(label))

/**
 * Records the Baseline Profile: the code behind cold start, expanding and collapsing folders,
 * scrolling the tree, and moving to and from the viewer, search and settings. Without it an
 * installed APK runs all of that interpreted until the JIT catches up, and older phones drop
 * frames on exactly these paths.
 *
 * Rows and actions are found by their English labels, so run it on an English device (the
 * managed emulator is). Each pass leaves the tree as it found it: the app restores the tree
 * on launch, and the rule runs the pass until the profile stops changing.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE) {
        prepareDevice()
        pressHome()
        startActivityAndWait()

        // A fresh install opens with internal storage expanded; later passes find it collapsed.
        expand("Internal shared storage")
        expand("Pictures")
        expand(SAMPLE_DIR)
        flingList()

        // Select a row, then leave selection mode.
        reveal("01.png").findObject(By.desc("Select")).click()
        await(By.desc("Clear")).click()
        awaitGone(By.desc("Clear"))

        // Viewer: open, page to the next image, come back.
        reveal("01.png").click()
        await(By.desc("01.png"))
        device.swipe(device.displayWidth * 4 / 5, device.displayHeight / 2, device.displayWidth / 5, device.displayHeight / 2, 20)
        await(By.desc("02.png"))
        device.pressBack()
        awaitGone(By.desc("02.png"))

        collapse(SAMPLE_DIR)
        collapse("Pictures")

        // Search: type a query, let results stream in, close it.
        await(By.desc("Search")).click()
        await(By.clazz("android.widget.EditText")).text = "png"
        await(By.text("01.png"))
        await(By.desc("Close search")).click()
        awaitGone(By.desc("Close search"))

        // Settings, through the More sheet. With a folder focused the sheet lists its actions
        // first, and Settings shows once the sheet is dragged fully open.
        await(By.desc("More")).click()
        val handle = await(By.desc("Drag handle")).visibleCenter
        if (!device.hasObject(By.text("Settings"))) {
            device.swipe(handle.x, handle.y, handle.x, device.displayHeight / 6, 20)
        }
        await(By.text("Settings")).click()
        await(By.text("Appearance"))
        device.findObject(By.scrollable(true))?.run {
            fling(Direction.DOWN)
            fling(Direction.UP)
        }
        device.pressBack()
        awaitGone(By.text("Appearance"))

        collapse("Internal shared storage")

        // App manager: the apps load with their icons. A clean emulator installs only a few,
        // so the system apps give the list something to scroll.
        expand("App manager")
        expand("Installed")
        expand("System")
        flingList()
        collapse("System")
        collapse("Installed")
        collapse("App manager")
    }

    private fun MacrobenchmarkScope.prepareDevice() {
        device.executeShellCommand("appops set --uid $PACKAGE MANAGE_EXTERNAL_STORAGE allow")
        device.executeShellCommand("pm grant $PACKAGE android.permission.POST_NOTIFICATIONS")
        val existing = device.executeShellCommand("ls $SAMPLE_PATH")
        if (SAMPLE_IMAGES.all { it in existing } && SAMPLE_OTHERS.all { it in existing }) return
        device.executeShellCommand("mkdir -p $SAMPLE_PATH/Album $SAMPLE_PATH/Documents")
        // Screenshots stand in for photos: any real image gives the thumbnail path its work.
        SAMPLE_IMAGES.forEach { device.executeShellCommand("screencap -p $SAMPLE_PATH/$it") }
        device.executeShellCommand("touch " + SAMPLE_OTHERS.joinToString(" ") { "$SAMPLE_PATH/$it" })
    }

    /** The tree, or null while it all fits on screen: Compose marks it scrollable only then. */
    private fun MacrobenchmarkScope.list(): UiObject2? =
        device.findObject(By.scrollable(true).hasChild(By.longClickable(true)))?.apply {
            // Keep swipes clear of the breadcrumb bar and the floating toolbar.
            setGestureMargins(0, device.displayHeight / 5, 0, device.displayHeight / 4)
        }

    /** Scrolls [label]'s row into the part of the list that the bars don't cover. */
    private fun MacrobenchmarkScope.reveal(label: String): UiObject2 {
        val top = device.displayHeight / 6
        val bottom = device.displayHeight * 3 / 4
        // Not on screen: search down to the end, then back up.
        var search = Direction.DOWN
        // The end the last scroll ran into. Rows there are clear of the bars already: the list pads
        // its first and last rows out from under them.
        var atEnd: Direction? = null
        repeat(40) {
            awaitSettled()
            val row = device.wait(Until.findObject(rowSelector(label)), 1_000L)
            val y = row?.visibleCenter?.y
            val direction = when {
                y == null -> search
                y > bottom && atEnd != Direction.DOWN -> Direction.DOWN
                y < top && atEnd != Direction.UP -> Direction.UP
                else -> return row
            }
            val more = list()?.scroll(direction, if (y == null) 0.6f else 0.4f) ?: false
            atEnd = if (more) null else direction
            if (!more && y == null) search = Direction.UP
        }
        error("Row '$label' not found")
    }

    private fun MacrobenchmarkScope.expand(label: String) = toggle(label, from = "Expand", to = "Collapse")

    private fun MacrobenchmarkScope.collapse(label: String) = toggle(label, from = "Collapse", to = "Expand")

    private fun MacrobenchmarkScope.toggle(label: String, from: String, to: String) {
        val row = reveal(label)
        if (!row.hasObject(By.desc(from))) return
        row.click()
        await(rowSelector(label).hasChild(By.desc(to)))
    }

    /** One fling down, then back to the top. */
    private fun MacrobenchmarkScope.flingList() {
        list()?.fling(Direction.DOWN) ?: return
        awaitSettled()
        repeat(10) { if (list()?.fling(Direction.UP) != true) return }
    }

    /**
     * Waits for the rows to stop moving: they slide while a folder expands or collapses and while
     * a fling runs out. waitForIdle doesn't cover this, as Compose sends no accessibility events
     * for moves, and a row tapped mid-slide takes the tap somewhere else.
     */
    private fun MacrobenchmarkScope.awaitSettled() {
        var last: List<Rect>? = null
        repeat(40) {
            val rows = try {
                device.findObjects(rowSelector()).map { it.visibleBounds }
            } catch (_: StaleObjectException) {
                null // A row left while it was measured.
            }
            if (rows != null && rows == last) return
            last = rows
            Thread.sleep(100)
        }
    }

    private fun MacrobenchmarkScope.await(selector: BySelector): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), TIMEOUT_MS)) { "Timed out waiting for $selector" }

    /** Waits for [selector] to leave, so the next tap doesn't land mid-transition and get dropped. */
    private fun MacrobenchmarkScope.awaitGone(selector: BySelector) {
        check(device.wait(Until.gone(selector), TIMEOUT_MS)) { "Timed out waiting for $selector to go" }
        device.waitForIdle()
    }
}
