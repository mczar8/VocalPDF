package com.example.security

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Verrouillage de l'application par code PIN à 4 chiffres.
 *
 * Quand il est activé, l'app demande le code :
 *  - à chaque lancement
 *  - à chaque retour dans l'app (écran éteint, bouton Accueil, changement d'app…)
 * Le code est stocké haché et salé ; 5 erreurs entraînent un délai croissant.
 */
class AppLockManager(context: Context) {

    sealed interface VerifyResult {
        data object Success : VerifyResult
        data class Wrong(val attemptsBeforeDelay: Int) : VerifyResult
        data class LockedOut(val remainingMs: Long) : VerifyResult
    }

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _isEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_ENABLED, false) && prefs.getString(KEY_HASH, null) != null
    )
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    /** Verrouillée au démarrage du processus si l'option est active. */
    private val _isLocked = MutableStateFlow(_isEnabled.value)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private var internalLeaveRequestedAt = 0L
    private var leftForInternalActivity = false
    private var stoppedAt = 0L

    fun remainingLockoutMs(): Long =
        (prefs.getLong(KEY_LOCKOUT_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    fun verify(pin: String): VerifyResult {
        val remaining = remainingLockoutMs()
        if (remaining > 0) return VerifyResult.LockedOut(remaining)
        val salt = prefs.getString(KEY_SALT, null)
        val hash = prefs.getString(KEY_HASH, null)
        if (salt == null || hash == null) return VerifyResult.Success

        if (PinHasher.matches(pin, salt, hash)) {
            prefs.edit { putInt(KEY_FAILED, 0); putLong(KEY_LOCKOUT_UNTIL, 0L) }
            return VerifyResult.Success
        }
        val failures = prefs.getInt(KEY_FAILED, 0) + 1
        val delay = LockoutPolicy.delayAfterFailures(failures)
        prefs.edit {
            putInt(KEY_FAILED, failures)
            if (delay > 0) putLong(KEY_LOCKOUT_UNTIL, System.currentTimeMillis() + delay)
        }
        return if (delay > 0) VerifyResult.LockedOut(delay)
        else VerifyResult.Wrong((LockoutPolicy.FREE_ATTEMPTS - failures).coerceAtLeast(0))
    }

    fun unlock(pin: String): VerifyResult = verify(pin).also {
        if (it is VerifyResult.Success) _isLocked.value = false
    }

    /** Active le verrouillage (ou change le code). */
    fun setPin(pin: String) {
        require(PinHasher.isValidPin(pin))
        val salt = PinHasher.newSalt()
        prefs.edit {
            putString(KEY_SALT, salt)
            putString(KEY_HASH, PinHasher.hash(pin, salt))
            putBoolean(KEY_ENABLED, true)
            putInt(KEY_FAILED, 0)
            putLong(KEY_LOCKOUT_UNTIL, 0L)
        }
        _isEnabled.value = true
        _isLocked.value = false
    }

    fun disable() {
        prefs.edit {
            remove(KEY_SALT)
            remove(KEY_HASH)
            putBoolean(KEY_ENABLED, false)
            putInt(KEY_FAILED, 0)
            putLong(KEY_LOCKOUT_UNTIL, 0L)
        }
        _isEnabled.value = false
        _isLocked.value = false
    }

    fun lockNow() {
        if (_isEnabled.value) _isLocked.value = true
    }

    /**
     * À appeler juste avant d'ouvrir un écran externe à la demande de l'utilisateur
     * (sélecteur de fichier, réglages des voix…) : le retour ne redemandera pas le code.
     */
    fun markLeavingForInternalActivity() {
        internalLeaveRequestedAt = SystemClock.elapsedRealtime()
    }

    fun cancelInternalLeave() {
        internalLeaveRequestedAt = 0L
    }

    fun onAppStopped() {
        val now = SystemClock.elapsedRealtime()
        leftForInternalActivity = now - internalLeaveRequestedAt < INTERNAL_LEAVE_WINDOW_MS
        internalLeaveRequestedAt = 0L
        stoppedAt = now
        if (!leftForInternalActivity) lockNow()
    }

    fun onAppStarted() {
        if (leftForInternalActivity) {
            leftForInternalActivity = false
            // Absence trop longue malgré tout : on reverrouille
            if (SystemClock.elapsedRealtime() - stoppedAt > INTERNAL_MAX_ABSENCE_MS) lockNow()
        }
    }

    companion object {
        private const val FILE_NAME = "vocalpdf_lock"
        private const val KEY_ENABLED = "lock_enabled"
        private const val KEY_SALT = "lock_salt"
        private const val KEY_HASH = "lock_hash"
        private const val KEY_FAILED = "lock_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "lock_lockout_until"
        private const val INTERNAL_LEAVE_WINDOW_MS = 3_000L
        private const val INTERNAL_MAX_ABSENCE_MS = 5 * 60_000L
    }
}
