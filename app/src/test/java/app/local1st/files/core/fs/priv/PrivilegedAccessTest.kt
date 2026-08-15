package app.local1st.files.core.fs.priv

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The no-probe lookups back every enhancement call site (viewer/thumbnail fds, app-manager
 * affordances, the Settings caption). With root browsing on by default, none of them may
 * launch `su` — these tests pin that the cached path leaves the probe cache untouched.
 */
class PrivilegedAccessTest {

    @Before
    fun setUp() {
        PrivilegedAccess.enabled = true
        PrivilegedAccess.preference = TransportPref.AUTO
        SuTransport.reset()
    }

    @After
    fun tearDown() {
        PrivilegedAccess.enabled = false
        PrivilegedAccess.preference = TransportPref.AUTO
        SuTransport.reset()
    }

    @Test
    fun noProbeLookupSkipsUnprobedSuAndDoesNotProbeIt() {
        assertNull(SuTransport.cachedAvailability())
        assertNull(PrivilegedAccess.activeFor(TransportPref.SU, probeSu = false))
        // AUTO falls through to Shizuku, which is unavailable on the JVM.
        assertNull(PrivilegedAccess.activeFor(TransportPref.AUTO, probeSu = false))
        // The lookups themselves must not have probed anything.
        assertNull(SuTransport.cachedAvailability())
    }

    @Test
    fun offPreferenceYieldsNoTransport() {
        assertNull(PrivilegedAccess.activeFor(TransportPref.OFF))
        assertNull(PrivilegedAccess.activeFor(TransportPref.OFF, probeSu = false))
    }

    @Test
    fun noProbeGatesStayOffWithoutTheEnabledSwitch() {
        PrivilegedAccess.enabled = false
        assertFalse(PrivilegedAccess.usableWithoutSuProbe())
        assertFalse(PrivilegedAccess.canOpenFd())
        assertNull(PrivilegedAccess.fdTransport())
        assertNull(SuTransport.cachedAvailability())
    }
}
