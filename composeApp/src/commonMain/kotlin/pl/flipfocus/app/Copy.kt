package pl.flipfocus.app

import pl.flipfocus.domain.AppLanguage

/** Both languages share the same state vocabulary and non-punitive interruption copy. */
data class Copy(val polish: Boolean) {
    fun text(pl: String, en: String): String = if (polish) pl else en
    val custom get() = text("Własny", "Custom")
    val history get() = text("Historia", "History")
    val settings get() = text("Ustawienia", "Settings")
    val done get() = text("Gotowe", "Done")
    val back get() = text("Wróć", "Back")
    val cancel get() = text("Anuluj", "Cancel")
    val newSession get() = text("Następna sesja", "Next session")
    val giveUp get() = text("Zakończ sesję", "Give up session")
    val left get() = text("POZOSTAŁO", "LEFT")
    val focusComplete get() = text("SKUPIENIE UKOŃCZONE", "FOCUS COMPLETE")
    val totalToday get() = text("Dzisiaj łącznie:", "Total Today:")
    val streakKept get() = text("Twoja passa:", "Your Streak:")
    val breakAction get() = text("Zrób 5 minut przerwy", "Take a 5m Break")
    fun startPrompt(secondsLeft: Int) = text(
        "Odłóż telefon ekranem w dół w ciągu $secondsLeft s, aby rozpocząć skupienie.",
        "Flip your phone screen-down within $secondsLeft s to start focus.",
    )
    fun days(value: Int) = if (polish) "$value ${if (value == 1) "dzień" else "dni"}" else "$value ${if (value == 1) "Day" else "Days"}"
    fun completed(minutes: Int) = text("$minutes minut\ngłębokiej pracy!", "$minutes Minutes of\nDeep Work Done!")
    fun time(millis: Long): String {
        val minutes = (millis / 60_000).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
    companion object {
        fun from(language: AppLanguage, systemLanguage: String) = Copy(
            language == AppLanguage.PL || language == AppLanguage.SYSTEM && systemLanguage.startsWith("pl"),
        )
    }
}

fun countdown(millis: Long): String {
    val seconds = ((millis.coerceAtLeast(0) + 999) / 1_000)
    return "${seconds / 60}".padStart(2, '0') + ":" + "${seconds % 60}".padStart(2, '0')
}
