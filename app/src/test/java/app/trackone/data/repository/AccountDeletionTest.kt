package app.trackone.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountDeletionTest {

    private class StaleLogin : Exception("recent login required")

    private val calls = mutableListOf<String>()
    private var cloudFailure: Exception? = null
    private var userFailures = ArrayDeque<Exception?>()       // one entry per deleteUser call; null = succeeds
    private var signOutFailure: Exception? = null

    private lateinit var deletion: AccountDeletion

    @Before
    fun setUp() {
        deletion = AccountDeletion(
            deleteCloudData = { calls += "cloud"; cloudFailure?.let { throw it } },
            deleteUser = { calls += "user"; userFailures.removeFirstOrNull()?.let { throw it } },
            signOutLocally = { calls += "signOut"; signOutFailure?.let { throw it } },
            needsRecentLogin = { it is StaleLogin }
        )
    }

    @Test
    fun `the cloud backup goes first, then the account, then the device is signed out`() = runTest {
        val result = deletion.run()

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("cloud", "user", "signOut"), calls)
    }

    @Test
    fun `if the backup cannot be deleted the account is kept so nothing is left orphaned`() = runTest {
        cloudFailure = Exception("offline")

        val result = deletion.run()

        assertTrue(result is DeletionResult.Failed)
        assertEquals(listOf("cloud"), calls)                      // never reached deleteUser or signOut
    }

    @Test
    fun `a stale sign-in asks the user to confirm it is them instead of failing`() = runTest {
        userFailures.add(StaleLogin())

        val result = deletion.run()

        assertEquals(DeletionResult.NeedsRecentLogin, result)
        assertEquals(listOf("cloud", "user"), calls)              // not signed out: the user is still here
    }

    @Test
    fun `after the user confirms, running again finishes the job`() = runTest {
        userFailures.add(StaleLogin())
        deletion.run()
        calls.clear()

        val result = deletion.run()                               // the backup is already gone; deleting it again is harmless

        assertEquals(DeletionResult.Deleted, result)
        assertEquals(listOf("cloud", "user", "signOut"), calls)
    }

    @Test
    fun `any other account failure is reported with its reason`() = runTest {
        userFailures.add(Exception("network error"))

        val result = deletion.run()

        assertEquals(DeletionResult.Failed("network error"), result)
        assertEquals(listOf("cloud", "user"), calls)
    }

    @Test
    fun `a failure while signing out afterwards does not undo a deleted account`() = runTest {
        signOutFailure = Exception("google client gone")

        val result = deletion.run()

        assertEquals(DeletionResult.Deleted, result)
    }

    @Test
    fun `the method used to confirm identity follows how the account signs in`() {
        assertEquals(ReauthMethod.PASSWORD, ReauthMethod.forProviders(listOf("firebase", "password")))
        assertEquals(ReauthMethod.GOOGLE, ReauthMethod.forProviders(listOf("firebase", "google.com")))
        assertEquals(ReauthMethod.PASSWORD, ReauthMethod.forProviders(listOf("google.com", "password")))   // can use either; password is quicker
        assertEquals(ReauthMethod.NONE, ReauthMethod.forProviders(listOf("firebase")))
        assertEquals(ReauthMethod.NONE, ReauthMethod.forProviders(emptyList()))
    }
}
