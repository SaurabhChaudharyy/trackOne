package app.trackone.di

import android.content.Context
import app.trackone.security.AppLockManager
import app.trackone.security.AppLockPrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideAppLockPrefs(@ApplicationContext context: Context): AppLockPrefs =
        AppLockPrefs(context)

    // A single AppLockManager instance for the process, so its in-memory "unlocked this
    // session" state (see AppLockManager) survives across every Activity.
    @Provides
    @Singleton
    fun provideAppLockManager(prefs: AppLockPrefs): AppLockManager =
        AppLockManager(prefs)
}
