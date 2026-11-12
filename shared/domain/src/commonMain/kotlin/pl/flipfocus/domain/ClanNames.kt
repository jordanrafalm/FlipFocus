package pl.flipfocus.domain

/** Normalizes clan names so "Żabka", "zabka " and "ZABKA!" are the same clan. */
object ClanNames {
    private val polish = mapOf('ą' to 'a', 'ć' to 'c', 'ę' to 'e', 'ł' to 'l', 'ń' to 'n', 'ó' to 'o', 'ś' to 's', 'ź' to 'z', 'ż' to 'z')

    fun key(name: String): String = buildString {
        for (c in name.lowercase()) {
            val m = polish[c] ?: c
            if (m in 'a'..'z' || m in '0'..'9') append(m)
        }
    }
}
