package pl.flipfocus.platform

/** Invitation links select a duration only; the physical face-down gesture still starts a session. */
fun invitationDuration(url: String?): Int? = url?.let {
    Regex("^flipfocus://session\\?minutes=(15|25)$", RegexOption.IGNORE_CASE)
        .matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
}
