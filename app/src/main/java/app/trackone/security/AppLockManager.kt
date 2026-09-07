package app.trackone.security

/** Storage seam for [AppLockManager] — whether fingerprint lock is turned on. */
interface AppLockStore {
    var isLockEnabled: Boolean
}

/**
 * Decides whether the biometric lock screen should be shown. Pure decision logic, with no
 * Android framework dependency, so it can be unit tested against a fake [AppLockStore].
 */
class AppLockManager(private val store: AppLockStore) {

    /** Cold start counts as "coming from background" — locked from the first frame if enabled. */
    private var isLocked: Boolean = store.isLockEnabled

    fun shouldShowLockScreen(): Boolean = isLocked

    fun onUnlocked() {
        isLocked = false
    }

    /** Call when the app leaves the foreground (last Activity stopped). */
    fun onAppBackgrounded() {
        if (store.isLockEnabled) isLocked = true
    }
}
