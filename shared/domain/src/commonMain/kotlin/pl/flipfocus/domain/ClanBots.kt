package pl.flipfocus.domain

import kotlin.random.Random

/** A person shown in a clan ranking. Bots are simulated teammates until real company rosters exist. */
data class ClanMember(
    val id: String, val name: String, val jobTitle: String, val avatar: String,
    val weeklyMinutes: Int, val isLive: Boolean = false, val liveMinutes: Int = 0, val isBot: Boolean = true, val isSelf: Boolean = false,
)

/** Deterministic simulated coworkers: every company always gets the same two bots. */
object ClanBots {
    private val names = listOf("Kamil", "Ania", "Marek", "Ola", "Tomek", "Zuza", "Piotr", "Magda", "Bartek", "Kasia", "Jakub", "Natalia", "Michał", "Ewa")
    private val titles = listOf("Senior Engineer", "Product Manager", "UX Designer", "Data Analyst", "QA Lead", "DevOps Engineer",
        "Account Manager", "Risk Analyst", "Engineering Manager", "Marketing Specialist")
    private val avatars = listOf("👨🏻", "👩🏻", "🧔🏻", "👩🏼", "👨🏾", "👩🏽", "👨🏼", "🧑🏽")

    fun randomAvatar(): String = avatars.random()

    const val DEFAULT_AVATAR = "🙂"

    /** Every company has two bots; roughly 40% of them get one more and 20% two more (stable per company). */
    fun forCompany(companyId: String): List<ClanMember> {
        val random = Random(companyId.hashCode())
        val roll = Random(companyId.hashCode() xor 0x5bd1e995).nextInt(5)
        val count = 2 + when (roll) { 2, 3 -> 1; 4 -> 2; else -> 0 }
        val pickedNames = names.shuffled(random).take(count)
        return pickedNames.mapIndexed { index, name ->
            val live = index == 0 && random.nextBoolean() || index == 1 && random.nextInt(3) == 0
            ClanMember(
                id = "bot-$companyId-$index", name = name, jobTitle = titles.random(random), avatar = avatars.random(random),
                weeklyMinutes = random.nextInt(180, 900), isLive = live, liveMinutes = if (live) random.nextInt(3, 24) else 0,
            )
        }
    }

    /**
     * Real members plus the simulated bots, sorted by weekly minutes (descending). The local user always
     * appears once, with the freshest local minutes; the user wins ties.
     */
    fun ranking(companyId: String, members: List<ClanMember>, selfName: String, selfTitle: String, selfMinutes: Int, selfAvatar: String = DEFAULT_AVATAR): List<ClanMember> =
        (forCompany(companyId) + members.filterNot { it.isSelf } +
            ClanMember("self", selfName, selfTitle, selfAvatar.ifBlank { DEFAULT_AVATAR }, selfMinutes, isBot = false, isSelf = true))
            .sortedWith(compareByDescending<ClanMember> { it.weeklyMinutes }.thenBy { !it.isSelf })
}
