package pl.flipfocus.app

import pl.flipfocus.presentation.FocusScreen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NavigationTest {

    @Test
    fun `should open vm screen when selecting a vm-backed destination`() {
        // Given / When
        val focusAction = destinationAction(AppDestination.FOCUS)
        val historyAction = destinationAction(AppDestination.HISTORY)
        val settingsAction = destinationAction(AppDestination.SETTINGS)

        // Then
        assertEquals(FocusScreen.FOCUS, assertIs<DestinationAction.OpenVmScreen>(focusAction).screen)
        assertEquals(FocusScreen.HISTORY, assertIs<DestinationAction.OpenVmScreen>(historyAction).screen)
        assertEquals(FocusScreen.SETTINGS, assertIs<DestinationAction.OpenVmScreen>(settingsAction).screen)
    }

    @Test
    fun `should show local overlay when selecting clan`() {
        // Given / When
        val clanAction = destinationAction(AppDestination.CLAN)

        // Then
        assertEquals(ExtraScreen.CLAN, assertIs<DestinationAction.ShowExtra>(clanAction).extra)
    }

    @Test
    fun `should derive current destination from vm screen when no overlay is active`() {
        // Given / When / Then
        assertEquals(AppDestination.FOCUS, currentDestination(FocusScreen.FOCUS, extra = null))
        assertEquals(AppDestination.HISTORY, currentDestination(FocusScreen.HISTORY, extra = null))
        assertEquals(AppDestination.SETTINGS, currentDestination(FocusScreen.SETTINGS, extra = null))
    }

    @Test
    fun `should derive current destination from overlay when one is active regardless of vm screen`() {
        // Given / When / Then
        assertEquals(AppDestination.FOCUS, currentDestination(FocusScreen.HISTORY, extra = ExtraScreen.INSIGHTS))
        assertEquals(AppDestination.CLAN, currentDestination(FocusScreen.FOCUS, extra = ExtraScreen.CLAN))
    }

    @Test
    fun `should swipe to next destination when drag exceeds negative threshold`() {
        // Given
        val threshold = 100f

        // When
        val next = AppDestination.FOCUS.swiped(dragDeltaPx = -150f, thresholdPx = threshold)

        // Then
        assertEquals(AppDestination.CLAN, next)
    }

    @Test
    fun `should swipe to previous destination when drag exceeds positive threshold`() {
        // Given
        val threshold = 100f

        // When
        val previous = AppDestination.HISTORY.swiped(dragDeltaPx = 150f, thresholdPx = threshold)

        // Then
        assertEquals(AppDestination.CLAN, previous)
    }

    @Test
    fun `should stay on same destination when drag does not exceed threshold`() {
        // Given
        val threshold = 100f

        // When
        val unchanged = AppDestination.HISTORY.swiped(dragDeltaPx = 40f, thresholdPx = threshold)

        // Then
        assertEquals(AppDestination.HISTORY, unchanged)
    }

    @Test
    fun `should not swipe past the first or last destination`() {
        // Given
        val threshold = 100f

        // When
        val beforeFirst = AppDestination.FOCUS.swiped(dragDeltaPx = 150f, thresholdPx = threshold)
        val afterLast = AppDestination.SETTINGS.swiped(dragDeltaPx = -150f, thresholdPx = threshold)

        // Then
        assertEquals(AppDestination.FOCUS, beforeFirst)
        assertEquals(AppDestination.SETTINGS, afterLast)
    }

    @Test
    fun `should clamp drag progress to a 0 to 1 range`() {
        // Given / When / Then
        assertEquals(0f, swipeProgress(0f, 100f))
        assertEquals(0.5f, swipeProgress(50f, 100f))
        assertEquals(1f, swipeProgress(250f, 100f))
    }
}
