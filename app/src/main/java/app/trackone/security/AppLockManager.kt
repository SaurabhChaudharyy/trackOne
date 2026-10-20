package app.trackone.security

/** Storage seam for [AppLockManager] — whether fingerprint lock is turned on, and how long it waits. */
interface AppLockStore {
    var isLockEnabled: Boolean

    /** How long the app may be out of the foreground before coming back asks for an unlock. 0 = at once. */
    var relockDelayMs: Long
}

/**
 * Decides whether the biometric lock screen should be shown. Pure decision logic, with no
 * Android framework dependency, so it can be unit tested against a fake [AppLockStore].
 *
 * Like other apps with a "lock after" setting, leaving the app starts a timer and coming back
 * locks only if the delay has passed. [clock] must keep counting through deep sleep
 * (SystemClock.elapsedRealtime does; System.nanoTime does not), or a phone left in a pocket
 * overnight would still be unlocked.
 *
 * The state is in memory on purpose: a cold start is always locked, so killing the app (or the
 * system doing it) is a way to lock it.
 */
class AppLockManager(
    private val store: AppLockStore,
    private val clock: () -> Long
) {

    /** Cold start counts as "coming from background" — locked from the first frame if enabled. */
    private var isLocked: Boolean = store.isLockEnabled

    /** When the app last left the foreground without locking; null while it is in front. */
    private var backgroundedAt: Long? = null

    fun shouldShowLockScreen(): Boolean = isLocked

    fun onUnlocked() {
        isLocked = false
        backgroundedAt = null
    }

    /** Call when the app leaves the foreground (last Activity stopped). */
    fun onAppBackgrounded() {
        if (!store.isLockEnabled || isLocked) return
        if (store.relockDelayMs <= 0L) {
            isLocked = true
        } else {
            backgroundedAt = clock()
        }
    }

    /** Call when the app comes back to the foreground (first Activity started), before asking [shouldShowLockScreen]. */
    fun onAppForegrounded() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (!store.isLockEnabled) return
        val away = clock() - since
        // A negative gap means the clock restarted (a reboot): nothing can be said about how long it was, so lock.
        if (away < 0L || away >= store.relockDelayMs) isLocked = true
    }
}
