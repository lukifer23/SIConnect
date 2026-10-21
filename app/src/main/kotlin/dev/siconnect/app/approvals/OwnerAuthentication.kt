package dev.siconnect.app.approvals

import android.os.Looper
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** Retains only a session barrier across rotation, never an approval action or authorization. */
class OwnerAuthenticationSession : ViewModel() {
    var inFlight=false
    var generation=0L
}

/**
 * One instance per Activity, constructed in onCreate. No authentication result is cached.
 * Destruction discards the exact action; configuration recreation must never authenticate a
 * different action with an old prompt's result. Callers must re-read policy/action binding
 * inside onAuthenticated before applying any change.
 */
class OwnerAuthentication(private val activity:FragmentActivity):DefaultLifecycleObserver {
    private data class Action(val generation:Long,val approve:()->Unit,val error:(String)->Unit)
    private data class Verified(val action:Action,val atElapsedMs:Long)
    private val session=ViewModelProvider(activity)[OwnerAuthenticationSession::class.java]
    private var pending:Action?=null
    private var verified:Verified?=null
    private var destroyed=false
    private val authenticators=BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private val prompt=BiometricPrompt(activity,ContextCompat.getMainExecutor(activity),object:BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
            session.inFlight=false
            val action=pending;pending=null
            if(action==null || action.generation!=session.generation || !usable()) return
            verified=Verified(action,SystemClock.elapsedRealtime())
            deliverIfResumed()
        }
        override fun onAuthenticationError(errorCode:Int,errString:CharSequence) {
            session.inFlight=false
            val action=pending;pending=null
            if(action!=null && action.generation==session.generation && usable()) action.error(when(errorCode) {
                BiometricPrompt.ERROR_USER_CANCELED,BiometricPrompt.ERROR_CANCELED,BiometricPrompt.ERROR_NEGATIVE_BUTTON -> "Authentication cancelled. Nothing was approved."
                BiometricPrompt.ERROR_LOCKOUT,BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "Biometric authentication is locked. Use the phone's PIN, pattern, or password, then try again."
                else -> "Owner authentication did not complete: $errString. Nothing was approved."
            })
        }
        // A non-matching biometric scan is nonterminal. Keep the Android prompt open.
    })
    init {activity.lifecycle.addObserver(this)}

    fun authenticate(title:String,onAuthenticated:()->Unit,onError:(String)->Unit) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            activity.runOnUiThread {authenticate(title,onAuthenticated,onError)}
            return
        }
        if(!usable() || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            onError("Open SIConnect and review this action again before authenticating.")
            return
        }
        if(session.inFlight || verified!=null) {
            onError("Finish or cancel the current Android authentication first. If it was interrupted, close and reopen SIConnect.")
            return
        }
        val availability=BiometricManager.from(activity).canAuthenticate(authenticators)
        if(availability!=BiometricManager.BIOMETRIC_SUCCESS) {
            onError(when(availability) {
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Set a screen-lock PIN, pattern, password, or strong biometric in Android Settings, then try again."
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "Android requires a security update before owner authentication is available. Install the update, then try again."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "Android authentication is temporarily unavailable. Unlock the phone and try again."
                else -> "Android cannot securely authenticate the owner (status $availability). Check screen-lock and biometric settings."
            })
            return
        }
        val action=Action(++session.generation,onAuthenticated,onError)
        pending=action;session.inFlight=true
        try {
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title.take(120))
                .setSubtitle("Confirm this SIConnect action")
                .setDescription("Use your strong biometric or phone screen-lock credential. Cancelling leaves the action unchanged.")
                .setAllowedAuthenticators(authenticators).setConfirmationRequired(true).build())
        } catch(e:Exception) {
            pending=null;session.inFlight=false
            onError("Could not open Android authentication (${e.javaClass.simpleName}). Nothing was approved.")
        }
    }

    private fun usable()=!destroyed && !activity.isFinishing && !activity.isDestroyed
    private fun deliverIfResumed() {
        if(!usable() || !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val completed=verified ?: return
        verified=null
        if(completed.action.generation!=session.generation) return
        if(SystemClock.elapsedRealtime()-completed.atElapsedMs>30_000) {
            completed.action.error("Authentication expired while SIConnect was away. Review the action and authenticate again.")
            return
        }
        completed.action.approve()
    }
    override fun onResume(owner:LifecycleOwner) {deliverIfResumed()}
    override fun onDestroy(owner:LifecycleOwner) {
        destroyed=true;pending=null;verified=null
        session.generation++
        // Keep the barrier until Android reports a terminal result. A recreated Activity
        // installs a new callback but has no old action to execute.
        if(session.inFlight) prompt.cancelAuthentication()
        activity.lifecycle.removeObserver(this)
    }
}
