package pl.flipfocus.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SparkCompanySharingTest {
    @Test
    fun bundledCompanyDirectoryHasStableUniqueIds() {
        val companies = LocalCompanyDirectory.all

        assertTrue(companies.isNotEmpty())
        assertEquals(companies.size, companies.map { it.id }.toSet().size)
        assertTrue(companies.all { it.id.isNotBlank() && it.name.isNotBlank() })
        assertNotNull(companies.firstOrNull { it.id == "apple" })
    }

    @Test
    fun companySearchIsCaseInsensitiveAndLocal() {
        assertEquals(listOf(Company("nvidia", "NVIDIA")), LocalCompanyDirectory.search(" nVid "))
        assertEquals(LocalCompanyDirectory.all.sortedBy { it.name }, LocalCompanyDirectory.search(""))
    }

    @Test
    fun emptyQueryRespectsAnExplicitBrowseLimit() {
        val browsed = LocalCompanyDirectory.search("", limit = 5)
        assertEquals(5, browsed.size)
        assertEquals(browsed, browsed.sortedBy { it.name })
    }

    @Test
    fun companyRankingOptInDefaultsToFalseAndIsSeparateFromRoomRankingOptIn() {
        val profile = SocialProfile(userId = "user-1", nickname = "Ada", rankingOptIn = true)
        assertTrue(profile.rankingOptIn)
        assertFalse(profile.companyRankingOptIn)
    }

    @Test
    fun companyRankingEntryCarriesBothRangesAndOptionalTitle() {
        val entry = CompanyRankingEntry(
            userId = "user-1", nickname = "Ada", jobTitle = null,
            last7Days = StatsRange(50, 2), allTime = StatsRange(125, 5),
        )
        assertEquals(50, entry.last7Days.completedMinutes)
        assertEquals(125, entry.allTime.completedMinutes)
    }


    @Test
    fun companyDashboardCarriesPersonalStatsAndDefersCompanyAggregate() {
        val profile = SocialProfile(
            userId = "user-1",
            nickname = "Ada",
            companyId = "apple",
            companyName = "Apple",
            jobTitle = "Engineer",
        )
        val personalStats = PersonalStats(
            last7Days = StatsRange(completedMinutes = 50, completedSessions = 2),
            allTime = StatsRange(completedMinutes = 125, completedSessions = 5),
        )

        val dashboard = SocialDashboard(
            profile = profile,
            rooms = emptyList(),
            nudges = emptyList(),
            personalStats = personalStats,
            companyAggregateDeferred = true,
        )

        assertTrue(dashboard.companyAggregateDeferred)
        assertEquals(personalStats, dashboard.personalStats)
        assertFalse(dashboard.rooms.any { it.aggregate.visible })
    }
}