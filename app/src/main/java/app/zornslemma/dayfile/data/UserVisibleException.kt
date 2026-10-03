package app.zornslemma.dayfile.data

import androidx.annotation.StringRes

/**
 * Exception used to signal a failure that is safe to surface directly to the user. Carries an
 * Android string resource ID and optional format arguments so the UI can present a localised
 * message without the data layer needing access to UI strings.
 *
 * The [message] parameter (standard Exception detail) is optional and intended for logs/debugging
 * only; it is never shown to the user.
 */
class UserVisibleException(
    @StringRes val resId: Int,
    val args: List<Any> = emptyList(),
    message: String? = null,
) : Exception(message)
