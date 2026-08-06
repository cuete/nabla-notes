package com.nabla.notes.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import com.nabla.notes.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Wraps Microsoft Authentication Library (MSAL) for single-account OAuth2 flows.
 *
 * Auth flow:
 *  1. Call [initialize] once (idempotent, safe to call from onCreate).
 *  2. Call [acquireToken] to get an access token — handles silent→interactive fallback.
 *  3. Call [signOut] to clear the cached account.
 *
 * Scopes: Files.ReadWrite, User.Read (for OneDrive + user info)
 * Authority: https://login.microsoftonline.com/consumers (personal Microsoft accounts)
 */
@Singleton
class MsalManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MsalManager"
        private val SCOPES = arrayOf("Files.ReadWrite", "User.Read")
        private const val CONSUMERS_AUTHORITY = "https://login.microsoftonline.com/consumers"
    }

    private var msalApp: ISingleAccountPublicClientApplication? = null
    private var initialized = false

    /**
     * Initialize MSAL from res/raw/msal_config.json.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    suspend fun initialize(): Result<Unit> {
        if (initialized) return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                PublicClientApplication.createSingleAccountPublicClientApplication(
                    context,
                    R.raw.msal_config,
                    object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                        override fun onCreated(application: ISingleAccountPublicClientApplication) {
                            msalApp = application
                            initialized = true
                            Log.d(TAG, "MSAL initialized successfully")
                            cont.resume(Result.success(Unit))
                        }

                        override fun onError(exception: MsalException) {
                            Log.e(TAG, "MSAL initialization failed", exception)
                            cont.resume(Result.failure(exception))
                        }
                    }
                )
            }
        }
    }

    /**
     * Get the current signed-in account (null if not signed in).
     */
    suspend fun getCurrentAccount(): IAccount? = withContext(Dispatchers.IO) {
        try {
            msalApp?.getCurrentAccount()?.currentAccount
        } catch (e: Exception) {
            Log.w(TAG, "Could not get current account", e)
            null
        }
    }

    /**
     * Interactively sign in (shows Microsoft browser-based login UI).
     * Must be called from a UI coroutine with valid Activity.
     */
    suspend fun signIn(activity: Activity): Result<IAccount> =
        suspendCancellableCoroutine { cont ->
            val app = msalApp ?: run {
                cont.resume(Result.failure(IllegalStateException("MSAL not initialized")))
                return@suspendCancellableCoroutine
            }
            app.signIn(
                activity,
                null,
                SCOPES,
                object : AuthenticationCallback {
                    override fun onSuccess(result: IAuthenticationResult) {
                        Log.d(TAG, "Interactive sign-in succeeded")
                        cont.resume(Result.success(result.account))
                    }

                    override fun onError(exception: MsalException) {
                        Log.e(TAG, "Interactive sign-in failed", exception)
                        cont.resume(Result.failure(exception))
                    }

                    override fun onCancel() {
                        cont.resume(Result.failure(Exception("Sign-in cancelled")))
                    }
                }
            )
        }

    /**
     * Sign out the current account and clear the token cache.
     */
    suspend fun signOut(): Result<Unit> = suspendCancellableCoroutine { cont ->
        msalApp?.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
            override fun onSignOut() {
                Log.d(TAG, "Signed out successfully")
                cont.resume(Result.success(Unit))
            }

            override fun onError(exception: MsalException) {
                Log.e(TAG, "Sign-out failed", exception)
                cont.resume(Result.failure(exception))
            }
        }) ?: cont.resume(Result.failure(IllegalStateException("MSAL not initialized")))
    }

    /**
     * Acquire an access token silently (no UI).
     * Falls back to interactive sign-in if the silent attempt fails with MsalUiRequiredException.
     *
     * IMPORTANT: Uses [CONSUMERS_AUTHORITY] for personal Microsoft accounts —
     * using account.authority directly fails for personal accounts (learned from DocScanner).
     */
    suspend fun acquireToken(activity: Activity): Result<String> {
        val app = msalApp ?: return Result.failure(IllegalStateException("MSAL not initialized"))

        // Ensure we have a current account; if not, start interactive sign-in
        val account = getCurrentAccount() ?: run {
            Log.d(TAG, "No cached account, starting interactive sign-in")
            return signIn(activity).flatMap { acquireTokenSilent(app, it) }
        }

        // Try silent first
        val silentResult = acquireTokenSilent(app, account)
        val silentError = silentResult.exceptionOrNull()

        return if (silentError is MsalUiRequiredException) {
            // Silent auth needs interaction — fall back to interactive
            Log.d(TAG, "Silent auth requires UI interaction, falling back")
            signIn(activity).flatMap { acquireTokenSilent(app, it) }
        } else {
            silentResult
        }
    }

    private suspend fun acquireTokenSilent(
        app: ISingleAccountPublicClientApplication,
        account: IAccount
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val params = AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(CONSUMERS_AUTHORITY)  // Must use consumers authority for personal accounts
            .withScopes(SCOPES.toList())
            .withCallback(object : SilentAuthenticationCallback {
                override fun onSuccess(result: IAuthenticationResult) {
                    cont.resume(Result.success(result.accessToken))
                }

                override fun onError(exception: MsalException) {
                    cont.resume(Result.failure(exception))
                }
            })
            .build()
        app.acquireTokenSilentAsync(params)
    }

    /**
     * Acquire a token silently only — no interactive fallback.
     * Used from the widget popup to avoid launching the browser unexpectedly.
     * Returns failure if the user hasn't signed in or the cache is expired.
     */
    suspend fun acquireTokenSilentOnly(): Result<String> {
        val app = msalApp ?: return Result.failure(IllegalStateException("MSAL not initialized"))
        val account = getCurrentAccount()
            ?: return Result.failure(Exception("Not signed in"))
        return acquireTokenSilent(app, account)
    }

    /** Helper: flat-map Result to avoid nested Results */
    private inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> =
        fold(onSuccess = { transform(it) }, onFailure = { Result.failure(it) })
}
