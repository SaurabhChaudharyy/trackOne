package app.trackone.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fake — no SharedPreferences/Android framework involved. */
private class FakeAppLockStore(override var isLockEnabled: Boolean = false) : AppLockStore

class AppLockManagerTest {

    @Test
    fun `lock disabled - lock screen never shows`() {
        val manager = AppLockManager(FakeAppLockStore(isLockEnabled = false))

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `lock enabled - lock screen shows on cold start, before any unlock`() {
        val manager = AppLockManager(FakeAppLockStore(isLockEnabled = true))

        assertTrue(manager.shouldShowLockScreen())
    }

    @Test
    fun `after a successful unlock, the lock screen stops showing`() {
        val manager = AppLockManager(FakeAppLockStore(isLockEnabled = true))

        manager.onUnlocked()

        assertFalse(manager.shouldShowLockScreen())
    }

    @Test
    fun `once unlocked, backgrounding the app re-locks it immediately`() {
        val manager = AppLockManager(FakeAppLockStore(isLockEnabled = true))
        manager.onUnlocked()

        manager.onAppBackgrounded()

        assertTrue(manager.shouldShowLockScreen())
    }
}
