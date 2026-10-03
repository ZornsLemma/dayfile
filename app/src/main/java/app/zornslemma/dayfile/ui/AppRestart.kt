package app.zornslemma.dayfile.ui

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * This superficially looks like there's a hole where the app might kill itself without restarting,
 * but this is in fact safe: startActivity is a synchronous Binder call into system_server, so by
 * the time it returns the launch request is durably recorded by the system and survives the process
 * exit below. (The hole would only exist in the opposite ordering: exit first, then start.)
 * makeRestartActivityTask is used deliberately so the restarted task *replaces* the existing one; a
 * plain launch intent would leave the user able to press Back from the freshly restarted app into
 * the stale pre-restart task.
 */
fun safeRestartApp(context: Context) {
    val packageManager = context.packageManager
    val launchIntent = packageManager.getLaunchIntentForPackage(context.packageName)
    val componentName = launchIntent?.component
    if (componentName == null) {
        // Unreachable for a correctly built APK: MainActivity is exported with a MAIN/LAUNCHER
        // filter, so the package always has exactly one launch component. The old code returned
        // silently here, which was the worst of the three available outcomes - no restart *and*
        // no process exit, leaving the user on the non-dismissable "the app will restart" dialog
        // holding a closed Room graph that crashes the next database-backed screen. Exiting is
        // itself the recovery: the user relaunches from the launcher and Room reopens cleanly.
        Log.e(TAG, "No launch intent for ${context.packageName}; exiting for manual relaunch")
        Runtime.getRuntime().exit(0)
        return
    }
    val restartIntent = Intent.makeRestartActivityTask(componentName)
    context.startActivity(restartIntent)
    Runtime.getRuntime().exit(0)
}

private const val TAG = "AppRestart"
