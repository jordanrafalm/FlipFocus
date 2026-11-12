@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package pl.flipfocus.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class BottomNavUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun clickingBottomNavItemSwitchesDestination() {
        var current by mutableStateOf(AppDestination.FOCUS)
        compose.setContent {
            FocusTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { Text(current.name) }
                    BottomNavBar(current, Copy(true), onSelect = { current = it })
                }
            }
        }

        compose.onNode(hasText("Statystyki") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(AppDestination.HISTORY, current) }

        compose.onNode(hasText("Profil") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(AppDestination.SETTINGS, current) }

        compose.onNode(hasText("Clan") and hasClickAction()).performClick()
        compose.runOnIdle { assertEquals(AppDestination.CLAN, current) }
    }

    @Test fun tappingActiveBottomNavItemKeepsDestinationUnchanged() {
        var current by mutableStateOf(AppDestination.FOCUS)
        compose.setContent {
            FocusTheme { BottomNavBar(current, Copy(false), onSelect = { current = it }) }
        }

        compose.onNodeWithText("Focus").performClick()
        compose.runOnIdle { assertEquals(AppDestination.FOCUS, current) }
    }

    @Test fun draggingTheScreenMovesItWithTheFinger() {
        var current by mutableStateOf(AppDestination.FOCUS)
        compose.setContent {
            FocusTheme {
                SwipeableDestinationHost(current, { current = it }, Modifier.size(400.dp, 800.dp)) { destination ->
                    Text(destination.name)
                }
            }
        }

        val before = compose.onNodeWithText("FOCUS").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("FOCUS").performTouchInput {
            down(center)
            moveBy(Offset(-120f, 0f))
        }
        val after = compose.onNodeWithText("FOCUS").fetchSemanticsNode().boundsInRoot
        assert(before.left > after.left)
    }

    @Test fun draggingMovesTheWholeScreenPanelWithTheFinger() {
        var current by mutableStateOf(AppDestination.FOCUS)
        compose.setContent {
            FocusTheme {
                SwipeableDestinationHost(current, { current = it }, Modifier.size(400.dp, 800.dp)) { destination ->
                    Text(destination.name)
                }
            }
        }

        val before = compose.onNodeWithText("FOCUS").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("FOCUS").performTouchInput {
            down(center)
            moveBy(Offset(-80f, 0f))
        }
        val after = compose.onNodeWithText("FOCUS").fetchSemanticsNode().boundsInRoot

        assert(before.left > after.left)
    }

    @Test fun swipingContentNavigatesToTheNextDestination() {
        var current by mutableStateOf(AppDestination.FOCUS)
        compose.setContent {
            FocusTheme {
                SwipeableDestinationHost(current, { current = it }, Modifier.size(400.dp, 800.dp)) { destination ->
                    Text(destination.name)
                }
            }
        }

        compose.onRoot().performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(AppDestination.CLAN, current) }
    }

    @Test fun swipingBackNavigatesToThePreviousDestination() {
        var current by mutableStateOf(AppDestination.HISTORY)
        compose.setContent {
            FocusTheme {
                SwipeableDestinationHost(current, { current = it }, Modifier.size(400.dp, 800.dp)) { destination ->
                    Text(destination.name)
                }
            }
        }

        compose.onRoot().performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(AppDestination.CLAN, current) }
    }

    @Test fun swipingPastTheFirstDestinationStaysOnFocus() {
        var current by mutableStateOf(AppDestination.FOCUS)
        compose.setContent {
            FocusTheme {
                SwipeableDestinationHost(current, { current = it }, Modifier.size(400.dp, 800.dp)) { destination ->
                    Text(destination.name)
                }
            }
        }

        compose.onRoot().performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(AppDestination.FOCUS, current) }
    }
}
