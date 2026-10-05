package app.trackone.ui.settings

import app.trackone.data.repository.ReauthMethod
import app.trackone.data.repository.DeletionResult
import app.trackone.data.repository.AccountDeleter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import app.trackone.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthState {
    object Unknown : AuthState()
    object SignedOut : AuthState()
    data class SignedIn(
        val displayName: String?,
        val email: String?,
        val photoUrl: String?
    ) : AuthState()
}

sealed class EmailAuthUiState {
    object Idle : EmailAuthUiState()
    object Loading : EmailAuthUiState()
    object Success : EmailAuthUiState()
    data class Info(val message: String) : EmailAuthUiState()
    data class Error(val message: String) : EmailAuthUiState()
}

sealed class AccountDeletionUiState {
    object Idle : AccountDeletionUiState()
    object Deleting : AccountDeletionUiState()
    /** Firebase wants the password again; [error] is set when the last attempt was wrong. */
    data class NeedsPassword(val error: String? = null) : AccountDeletionUiState()
    /** Firebase wants a fresh Google sign-in to confirm it is the user. */
    object NeedsGoogle : AccountDeletionUiState()
    object Deleted : AccountDeletionUiState()
    data class Error(val message: String) : AccountDeletionUiState()
}

sealed class GoogleSignInUiState {
    object Idle : GoogleSignInUiState()
    object Loading : GoogleSignInUiState()
    data class Error(val message: String) : GoogleSignInUiState()
}

/**
 * Owns everything about "who is signed in": Google Sign-In, email/password auth, and
 * sign-out. [CloudBackupViewModel] and [CsvImportViewModel] are independent seams that don't
 * know this class exists — the Fragment is the only thing that reacts to [authState] changes
 * to trigger effects in those other ViewModels (e.g. refreshing last-sync time on sign-in).
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val accountDeleter: AccountDeleter,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val user = firebaseAuth.currentUser
        _authState.value = if (user != null) {
            AuthState.SignedIn(
                displayName = user.displayName,
                email = user.email,
                photoUrl = user.photoUrl?.toString()
            )
        } else {
            AuthState.SignedOut
        }
    }

    init {
        firebaseAuth.addAuthStateListener(authListener)
    }

    override fun onCleared() {
        super.onCleared()
        firebaseAuth.removeAuthStateListener(authListener)
    }

    /** Trigger the listener manually by re-reading current state (e.g. Fragment.onResume). */
    fun refreshAuthState() {
        authListener.onAuthStateChanged(firebaseAuth)
    }

    // ── Google Sign-In ────────────────────────────────────────────────────

    fun getGoogleSignInClient() = authRepository.getGoogleSignInClient()

    val isGoogleSignInConfigured: Boolean get() = authRepository.isGoogleSignInConfigured

    private val _googleSignInState = MutableStateFlow<GoogleSignInUiState>(GoogleSignInUiState.Idle)
    val googleSignInState: StateFlow<GoogleSignInUiState> = _googleSignInState.asStateFlow()

    fun handleGoogleSignInResult(idToken: String) {
        viewModelScope.launch {
            _googleSignInState.value = GoogleSignInUiState.Loading
            val result = authRepository.signInWithGoogle(idToken)
            result.fold(
                onSuccess = {
                    refreshAuthState()
                    _googleSignInState.value = GoogleSignInUiState.Idle
                },
                onFailure = { e ->
                    _googleSignInState.value = GoogleSignInUiState.Error(e.message ?: "Google Sign-In failed")
                }
            )
        }
    }

    fun resetGoogleSignInState() {
        _googleSignInState.value = GoogleSignInUiState.Idle
    }

    // ── Email/password auth ──────────────────────────────────────────────

    private val _emailAuthState = MutableStateFlow<EmailAuthUiState>(EmailAuthUiState.Idle)
    val emailAuthState: StateFlow<EmailAuthUiState> = _emailAuthState.asStateFlow()

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _emailAuthState.value = EmailAuthUiState.Loading
            val result = authRepository.signUpWithEmail(email, password)
            result.fold(
                onSuccess = {
                    refreshAuthState()
                    _emailAuthState.value = EmailAuthUiState.Success
                },
                onFailure = { e ->
                    _emailAuthState.value = EmailAuthUiState.Error(e.message ?: "Sign-up failed")
                }
            )
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _emailAuthState.value = EmailAuthUiState.Loading
            val result = authRepository.signInWithEmail(email, password)
            result.fold(
                onSuccess = {
                    refreshAuthState()
                    _emailAuthState.value = EmailAuthUiState.Success
                },
                onFailure = { e ->
                    _emailAuthState.value = EmailAuthUiState.Error(e.message ?: "Sign-in failed")
                }
            )
        }
    }

    fun sendPasswordReset(email: String) {
        viewModelScope.launch {
            _emailAuthState.value = EmailAuthUiState.Loading
            val result = authRepository.sendPasswordResetEmail(email)
            result.fold(
                onSuccess = {
                    _emailAuthState.value = EmailAuthUiState.Info("Password reset email sent to $email")
                },
                onFailure = { e ->
                    _emailAuthState.value = EmailAuthUiState.Error(e.message ?: "Could not send reset email")
                }
            )
        }
    }

    fun resetEmailAuthState() {
        _emailAuthState.value = EmailAuthUiState.Idle
    }

    // ── Delete account ─────────────────────────────────────────────────────

    private val _accountDeletion = MutableStateFlow<AccountDeletionUiState>(AccountDeletionUiState.Idle)
    val accountDeletion: StateFlow<AccountDeletionUiState> = _accountDeletion.asStateFlow()

    /** Deletes the cloud backup and the account (see [AccountDeletion] for the order and the retry). */
    fun deleteAccount() {
        viewModelScope.launch {
            _accountDeletion.value = AccountDeletionUiState.Deleting
            _accountDeletion.value = when (val result = accountDeleter.delete()) {
                DeletionResult.Deleted -> {
                    _authState.value = AuthState.SignedOut
                    AccountDeletionUiState.Deleted
                }
                DeletionResult.NeedsRecentLogin -> when (authRepository.reauthMethod) {
                    ReauthMethod.PASSWORD -> AccountDeletionUiState.NeedsPassword()
                    ReauthMethod.GOOGLE -> AccountDeletionUiState.NeedsGoogle
                    ReauthMethod.NONE -> AccountDeletionUiState.Error(
                        "For your security, please sign out, sign in again, and then delete your account."
                    )
                }
                is DeletionResult.Failed -> AccountDeletionUiState.Error(result.message)
            }
        }
    }

    /** The user typed their password to confirm it is them; on success the deletion carries on. */
    fun confirmPasswordAndDelete(password: String) {
        viewModelScope.launch {
            _accountDeletion.value = AccountDeletionUiState.Deleting
            authRepository.reauthenticateWithPassword(password).fold(
                onSuccess = { deleteAccount() },
                onFailure = { e -> _accountDeletion.value = AccountDeletionUiState.NeedsPassword(e.message) }
            )
        }
    }

    /** The user signed in with Google again to confirm it is them; on success the deletion carries on. */
    fun confirmGoogleAndDelete(idToken: String) {
        viewModelScope.launch {
            _accountDeletion.value = AccountDeletionUiState.Deleting
            authRepository.reauthenticateWithGoogle(idToken).fold(
                onSuccess = { deleteAccount() },
                onFailure = { e -> _accountDeletion.value = AccountDeletionUiState.Error(e.message ?: "Couldn't confirm your Google account") }
            )
        }
    }

    /** Back to idle: after a result was shown, or when the user cancelled a confirmation. */
    fun resetAccountDeletion() {
        _accountDeletion.value = AccountDeletionUiState.Idle
    }

    // ── Sign out ───────────────────────────────────────────────────────────

    fun signOut() {
        viewModelScope.launch {
            authRepository.signOut()
            _authState.value = AuthState.SignedOut
        }
    }
}
