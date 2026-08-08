package app.local1st.files.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNavigationStateTest {
    @Test
    fun browserIsPermanentRoot() {
        val navigation = AppNavigationState()

        assertFalse(navigation.navigateBack())
        assertEquals(1, navigation.backStack.size)
        assertSame(AppScreen.Browser, navigation.backStack.single().screen)
    }

    @Test
    fun navigationPushesAndPopsDestinations() {
        val navigation = AppNavigationState()
        val settings = navigation.navigate(AppScreen.Settings)
        val details = navigation.navigate(AppScreen.AppInfo("app.example"))

        assertEquals(listOf(0L, settings.id, details.id), navigation.backStack.map { it.id })
        assertTrue(navigation.navigateBack(details.id))
        assertSame(AppScreen.Settings, navigation.backStack.last().screen)
        assertTrue(navigation.navigateBack(settings.id))
        assertSame(AppScreen.Browser, navigation.backStack.last().screen)
    }

    @Test
    fun staleCallbackCannotPopNewDestination() {
        val navigation = AppNavigationState()
        val settings = navigation.navigate(AppScreen.Settings)
        val details = navigation.navigate(AppScreen.AppInfo("app.example"))

        assertFalse(navigation.navigateBack(settings.id))
        assertEquals(details.id, navigation.backStack.last().id)
    }

    @Test
    fun replaceTopKeepsPreviousDestinationAndGetsFreshIdentity() {
        val navigation = AppNavigationState()
        val first = navigation.navigate(AppScreen.AppInfo("one.example"))
        val replacement = navigation.navigate(
            AppScreen.AppInfo("two.example"),
            replaceTop = true,
        )

        assertEquals(2, navigation.backStack.size)
        assertTrue(replacement.id > first.id)
        assertEquals("two.example", (navigation.backStack.last().screen as AppScreen.AppInfo).packageName)
    }
}
