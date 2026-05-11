package app.trackone.data.repository

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [FirebaseAuth] for Hilt injection and
 * Google Sign-In orchestration.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AuthRepository"
        /**
         * Default Web Client ID placeholder.
         * Replace this with the actual Web client ID from your Firebase project
         * (found in google-services.json → client → oauth_client → client_type 3).
         */
        private const val DEFAULT_WEB_CLIENT_ID =
            "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    val currentUser: FirebaseUser? get() = auth.currentUser
    val isSignedIn: Boolean get() = currentUser != null

    /**
     * Builds a [GoogleSignInClient] configured for ID-token authentication.
     * The returned client is used by [SettingsFragment] to launch the sign-in intent.
     */
    fun getGoogleSignInClient(): GoogleSignInClient {
        val webClientId = getWebClientId()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    /**
     * Exchanges the Google ID token for a Firebase credential and signs in.
     * @return [Result.success] with the [FirebaseUser] on success.
     */
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user
            if (user != null) {
                Log.d(TAG, "signInWithGoogle: success – ${user.email}")
                Result.success(user)
            } else {
                Result.failure(Exception("Sign-in succeeded but user is null"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "signInWithGoogle: failed", e)
            Result.failure(e)
        }
    }

    /**
     * Signs the user out of Firebase and the Google Sign-In client
     * so the account picker is shown again on the next sign-in attempt.
     */
    suspend fun signOut(): Result<Unit> {
        return try {
            auth.signOut()
            getGoogleSignInClient().signOut().await()
            Log.d(TAG, "signOut: success")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "signOut: failed", e)
            Result.failure(e)
        }
    }

    /**
     * Reads the Web Client ID from the string resource injected by google-services.json.
     * Falls back to the hardcoded placeholder if the resource is missing (shouldn't
     * happen in a correctly-configured project).
     */
    private fun getWebClientId(): String {
        return try {
            val resId = context.resources.getIdentifier(
                "default_web_client_id", "string", context.packageName
            )
            if (resId != 0) context.getString(resId) else DEFAULT_WEB_CLIENT_ID
        } catch (e: Exception) {
            DEFAULT_WEB_CLIENT_ID
        }
    }
}
