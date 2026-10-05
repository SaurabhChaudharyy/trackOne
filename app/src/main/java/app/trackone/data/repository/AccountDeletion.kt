package app.trackone.data.repository

/** How a request to delete the account ended. */
sealed class DeletionResult {
    /** The cloud backup and the account are gone and this device is signed out. */
    object Deleted : DeletionResult()

    /** Firebase wants the user to prove it is them again before it will delete the account. */
    object NeedsRecentLogin : DeletionResult()

    data class Failed(val message: String) : DeletionResult()
}

/** What the user can do to confirm their identity again, decided by how the account signs in. */
enum class ReauthMethod {
    PASSWORD, GOOGLE, NONE;

    companion object {
        /** [providerIds] are the Firebase sign-in providers on the account ("password", "google.com"). */
        fun forProviders(providerIds: List<String>): ReauthMethod = when {
            "password" in providerIds -> PASSWORD     // quicker than a Google round trip when both exist
            "google.com" in providerIds -> GOOGLE
            else -> NONE
        }
    }
}

/**
 * Deleting an account, in an order that never strands anything: the cloud backup goes first, while
 * the user can still reach it, and only then the account. If the backup can't be deleted the account
 * is kept, so no data is left behind with nobody able to ask for it to be removed.
 *
 * Firebase refuses to delete an account whose last sign-in is old; that comes back as
 * [DeletionResult.NeedsRecentLogin]. After the user confirms, calling [run] again finishes the job:
 * the backup is already gone and deleting it again is harmless.
 *
 * The steps are passed in, so this ordering can be tested without Firebase.
 */
class AccountDeletion(
    private val deleteCloudData: suspend () -> Unit,
    private val deleteUser: suspend () -> Unit,
    private val signOutLocally: suspend () -> Unit,
    private val needsRecentLogin: (Throwable) -> Boolean
) {
    suspend fun run(): DeletionResult {
        try {
            deleteCloudData()
        } catch (e: Exception) {
            return DeletionResult.Failed(e.message ?: "Couldn't delete your cloud backup")
        }

        try {
            deleteUser()
        } catch (e: Exception) {
            return if (needsRecentLogin(e)) DeletionResult.NeedsRecentLogin
            else DeletionResult.Failed(e.message ?: "Couldn't delete your account")
        }

        // The account is already gone; failing to tidy this device's sign-in must not report otherwise.
        try { signOutLocally() } catch (_: Exception) { }
        return DeletionResult.Deleted
    }
}
