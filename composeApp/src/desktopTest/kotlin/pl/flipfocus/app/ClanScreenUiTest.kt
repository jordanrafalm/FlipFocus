@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package pl.flipfocus.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import pl.flipfocus.domain.*
import pl.flipfocus.presentation.SocialUiState

/** Post-company-selection ranking, Figma node 2:1055: podium + friends-style rows use real CompanyRankingEntry data. */
class ClanScreenUiTest {
    @get:Rule val compose = createComposeRule()

    private fun entry(userId: String, nickname: String, minutes: Int) =
        CompanyRankingEntry(userId, nickname, null, StatsRange(minutes, 1), StatsRange(minutes * 4, 4))

    private val profile = SocialProfile(userId = "me", nickname = "Ja", companyId = "acme", companyName = "Acme",
        companyRankingOptIn = true)
    private val state = SocialUiState(
        connection = SocialConnection("firebase", profile), isLoading = false,
        companyRanking = listOf(entry("a", "Ania", 665), entry("k", "Kamil", 860), entry("m", "Marek", 570), entry("me", "Ja", 465)),
    )

    @Test fun podiumAndRankingRowsRenderRealCompanyEntries() {
        compose.setContent { FocusTheme { ClanScreen(state, Copy(false), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText("Kamil").assertExists()
        compose.onNodeWithText("Ania").assertExists()
        compose.onNodeWithText("Marek").assertExists()
        compose.onNodeWithText("You").assertExists()
    }

    @Test fun stickyOwnCardShowsRankAndTotal() {
        compose.setContent { FocusTheme { ClanScreen(state, Copy(false), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText("#4  You").assertExists()
    }
}

/** Company picker (Figma node 53:104): header, headline, search, row selection and sticky CTA. */
class ClanScreenCompanyPickerUiTest {
    @get:Rule val compose = createComposeRule()

    private val pickerProfile = SocialProfile(userId = "me", nickname = "Ja")
    private val companies = LocalCompanyDirectory.all.take(2)
    private val pickerState = SocialUiState(
        connection = SocialConnection("firebase", pickerProfile), isLoading = false, companies = companies,
    )

    @Test fun showsHeaderHeadlineAndSearch() {
        compose.setContent { FocusTheme { ClanScreen(pickerState, Copy(false), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText("Choose your clan").assertExists()
        compose.onNodeWithText("Focus is better together.").assertExists()
        compose.onNodeWithText("Search companies").assertExists()
        compose.onNodeWithText(companies[0].name).assertExists()
        compose.onNodeWithText(companies[1].name).assertExists()
    }

    @Test fun selectingRowShowsStickyCtaThatTriggersJoinFlowForThatCompany() {
        var selected: Company? = null
        val target = companies[1]
        compose.setContent { FocusTheme { ClanScreen(pickerState, Copy(false), {}, {}, {}, {}, {}, { selected = it }, {}, {}, {}, {}) } }

        compose.onNodeWithText("Join").assertDoesNotExist()
        compose.onNodeWithText(target.name).performClick()
        compose.onNodeWithText("Join").assertExists()

        compose.onNodeWithText("Join").performClick()
        assert(selected == target) { "expected $target, got $selected" }
    }

    @Test fun switchingRowSelectionUpdatesStickyCta() {
        compose.setContent { FocusTheme { ClanScreen(pickerState, Copy(false), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        compose.onNodeWithText(companies[0].name).performClick()
        compose.onNodeWithText(companies[1].name).performClick()

        compose.onAllNodesWithText(companies[1].name).assertCountEquals(2)
    }
}
