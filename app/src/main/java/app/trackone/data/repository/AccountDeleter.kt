package app.trackone.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/** Deletes the signed-in account and its cloud backup, in the order [AccountDeletion] guarantees. */
@Singleton
class AccountDeleter @Inject constructor(
    private val authRepository: AuthRepository,
    private val cloudBackupRepository: CloudBackupRepository
) {
    suspend fun delete(): DeletionResult = AccountDeletion(
        deleteCloudData = { cloudBackupRepository.deleteCloudData() },
        deleteUser = { authRepository.deleteCurrentUser() },
        signOutLocally = {
            authRepository.revokeGoogleAccess()
            authRepository.signOut()
        },
        needsRecentLogin = authRepository::isRecentLoginError
    ).run()
}
