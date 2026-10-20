package app.trackone.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fake — no SharedPreferences/Android framework involved. */
private class FakeAppLockStore(
    override var isLockEnabled: Boolean = false,
    override var relockDelayMs: Long = 0L
) : AppLockStore

/** A clock the test moves by hand, standing in for SystemClock.elapsedRealtime. */
private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
    override fun invoke() = now
    fun advance(ms: Long) { now += ms }
}

class AppLockManagerTest {

    private val clock = FakeClock()

    private fun manager(enabled: Boolean = true, delayMs: Long = 0L) =
        AppLockManager(FakeAppLockStore(enabled, delayMs), clock)

    /** Leave the app, wait, come back — what AppLockWatcher reports around a trip to another app. */
    private fun AppLockManager.awayFor(ms: Long) {
        onAppBackgrounded()
        clock.advance(ms)
        onAppForegrounded()
    }

    @Test
    fun `lock disabled - lock screen never shows`() {
        val manager = manager(enabled = false)

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `lock enabled - lock screen shows on cold start, before any unlock`() {
        assertTrue(manager().shouldShowLockScreen())
    }

    @Test
    fun `after a successful unlock, the lock screen stops showing`() {
        val manager = manager()

        manager.onUnlocked()

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `with no delay, backgrounding the app re-locks it immediately`() {
        val manager = manager(delayMs = 0)
        manager.onUnlocked()

        manager.onAppBackgrounded()

        assertTrue(manager.shouldShowLockScreen())
    }

    @Test
    fun `returning within the delay does not lock`() {
        val manager = manager(delayMs = 60_000)
        manager.onUnlocked()

        manager.awayFor(30_000)

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `returning exactly at the delay locks`() {
        val manager = manager(delayMs = 60_000)
        manager.onUnlocked()

        manager.awayFor(60_000)

        assertTrue(manager.shouldShowLockScreen())
    }

    @Test
    fun `returning after the delay locks`() {
        val manager = manager(delayMs = 60_000)
        manager.onUnlocked()

        manager.awayFor(10 * 60_000)

        assertTrue(manager.shouldShowLockScreen())
    }

    @Test
    fun `each trip away is timed on its own`() {
        val manager = manager(delayMs = 60_000)
        manager.onUnlocked()
        manager.awayFor(30_000)
        clock.advance(10 * 60_000)              // a long stretch in the foreground in between

        manager.awayFor(10_000)

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `time spent in the foreground never counts toward the delay`() {
        val manager = manager(delayMs = 60_000)
        manager.onUnlocked()
        manager.awayFor(5_000)

        clock.advance(60 * 60_000)
        manager.onAppForegrounded()              // e.g. another Activity starting while the app is in use

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `a clock that moved backwards, such as after a reboot, locks`() {
        val manager = manager(delayMs = 60_000)
        manager.onUnlocked()
        manager.onAppBackgrounded()
        clock.advance(-500_000)

        manager.onAppForegrounded()

        assertTrue(manager.shouldShowLockScreen())
    }

    @Test
    fun `an app that is already locked stays locked however briefly it was away`() {
        val manager = manager(delayMs = 60_000)          // cold start: locked, never unlocked

        manager.awayFor(1_000)

        assertTrue(manager.shouldShowLockScreen())
    }

    @Test
    fun `the delay in force when the app returns is the one that counts`() {
        val store = FakeAppLockStore(isLockEnabled = true, relockDelayMs = 60_000)
        val manager = AppLockManager(store, clock)
        manager.onUnlocked()
        manager.onAppBackgrounded()
        clock.advance(120_000)
        store.relockDelayMs = 5 * 60_000                  // loosened while away

        manager.onAppForegrounded()

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `turning the lock off while away leaves the app unlocked`() {
        val store = FakeAppLockStore(isLockEnabled = true, relockDelayMs = 60_000)
        val manager = AppLockManager(store, clock)
        manager.onUnlocked()
        manager.onAppBackgrounded()
        clock.advance(10 * 60_000)
        store.isLockEnabled = false

        manager.onAppForegrounded()

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `the options offer immediate locking and the default is one of them`() {
        assertTrue(LockDelay.options.any { it.millis == 0L })
        assertTrue(LockDelay.options.any { it.millis == LockDelay.DEFAULT_MS })
        assertEquals(LockDelay.options.size, LockDelay.options.map { it.label }.toSet().size)
        assertEquals(LockDelay.options.map { it.millis }, LockDelay.options.map { it.millis }.sorted())
    }
}
