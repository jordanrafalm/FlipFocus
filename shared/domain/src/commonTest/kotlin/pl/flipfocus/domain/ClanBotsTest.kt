package pl.flipfocus.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClanBotsTest {
    @Test fun everyCompanyHasTwoToFourStableBots() {
        val bots = ClanBots.forCompany("apple")
        assertTrue(bots.size in 2..4)
        assertTrue(LocalCompanyDirectory.all.any { ClanBots.forCompany(it.id).size > 2 })
        assertNotEquals(bots[0].name, bots[1].name)
        assertEquals(bots, ClanBots.forCompany("apple"))
    }

    @Test fun rankingIncludesUserSortedByMinutes() {
        val ranking = ClanBots.ranking("apple", emptyList(), "You", "", 100_000)
        assertEquals(ClanBots.forCompany("apple").size + 1, ranking.size)
        assertEquals("self", ranking.first().id)
    }
}
