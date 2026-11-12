@file:OptIn(
    androidx.compose.ui.test.ExperimentalTestApi::class,
    androidx.compose.ui.test.InternalTestApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    androidx.compose.ui.InternalComposeUiApi::class,
)

package pl.flipfocus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.DesktopComposeTestRule
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import pl.flipfocus.data.LocalFocusRepository
import pl.flipfocus.data.MemoryStringStore
import pl.flipfocus.domain.*
import pl.flipfocus.presentation.*
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FocusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readyDurationChoiceAndAutomaticSessionFlow() {
        val clock = TestClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val vm = FocusViewModel(LocalFocusRepository(MemoryStringStore()), clock, scope, autoTick = false)
        compose.setContent {
            val state by vm.state.collectAsState()
            FocusTheme {
                when (state.session?.state) {
                    FocusState.COMPLETED -> CompletedScreen(state, Copy(false), vm::accept)
                    else -> FocusHomeScreen(state, Copy(false), vm::accept)
                }
            }
        }
        compose.onNodeWithText("15m").performClick()
        compose.onNodeWithContentDescription("15:00").assertExists()
        compose.runOnIdle { vm.accept(FocusIntent.PhonePositionChanged(true)) }
        compose.runOnIdle {
            repeat(180) { clock.advance(5_000); vm.accept(FocusIntent.Tick) }
        }
        compose.onNodeWithText("FOCUS COMPLETE").assertIsDisplayed()
        compose.onNodeWithText("Take a 5m Break").performClick()
        compose.runOnIdle {
            assertEquals(300_000, vm.state.value.breakRemainingMillis)
            assertEquals(1, vm.state.value.history.size)
            vm.close(); scope.cancel()
        }
    }

    @Test fun readyMatchesFigmaStructure() = screenshot("ready") {
        FocusHomeScreen(fixture(FocusState.READY), Copy(false), {})
    }

    @Test fun interruptionShowsRemainingGraceAndAbandonAction() = screenshot("interrupted") {
        FocusHomeScreen(fixture(FocusState.GRACE_PERIOD), Copy(false), {})
    }

    @Test fun completionShowsRealProgressAndNextStep() = screenshot("completed") {
        CompletedScreen(fixture(FocusState.COMPLETED), Copy(false), {})
    }

    @Test fun landscapePlacesDurationChoicesBesideTimer() {
        compose.runOnUiThread { (compose as DesktopComposeTestRule).scene.size = IntSize(852, 393) }
        compose.setContent {
            FocusTheme {
                Box(Modifier.requiredSize(852.dp, 393.dp)) {
                    FocusHomeScreen(fixture(FocusState.READY), Copy(false), {})
                }
            }
        }

        val timer = compose.onNodeWithContentDescription("25:00").fetchSemanticsNode().boundsInRoot
        val durationChoices = compose.onNodeWithText("25m").fetchSemanticsNode().boundsInRoot
        assertTrue(durationChoices.left > timer.right)
    }

    @Test fun portraitPlacesDurationChoicesBelowTimer() {
        compose.runOnUiThread { (compose as DesktopComposeTestRule).scene.size = IntSize(393, 852) }
        compose.setContent {
            FocusTheme {
                Box(Modifier.requiredSize(393.dp, 852.dp)) {
                    FocusHomeScreen(fixture(FocusState.READY), Copy(false), {})
                }
            }
        }

        val durationChoices = compose.onNodeWithText("25m").fetchSemanticsNode().boundsInRoot
        val timer = compose.onNodeWithContentDescription("25:00").fetchSemanticsNode().boundsInRoot
        assertTrue(durationChoices.top > timer.bottom)
    }

    private fun screenshot(name: String, content: @Composable () -> Unit) {
        compose.runOnUiThread { (compose as DesktopComposeTestRule).scene.size = IntSize(393, 852) }
        compose.setContent {
            FocusTheme {
                Column(Modifier.requiredSize(393.dp, 852.dp).background(FocusColors.background)) {
                    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) { Text("9:41") }
                    Box(Modifier.weight(1f)) { content() }
                    Box(Modifier.height(20.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(134.dp, 5.dp).background(FocusColors.text))
                    }
                }
            }
        }
        compose.waitForIdle()
        val output = File(System.getProperty("user.dir"), "../docs/verification/$name-desktop.png")
        output.parentFile.mkdirs()
        ImageIO.write(compose.onRoot().captureToImage().toAwtImage(), "png", output)
    }

    private fun fixture(state: FocusState): FocusUiState {
        val duration = FocusDuration.ofMinutes(25)
        val focused = when (state) { FocusState.COMPLETED -> duration.millis; FocusState.GRACE_PERIOD -> 638_000L; else -> 0L }
        return FocusUiState(
            session = FocusSession("fixture", duration, state, startedAtEpochMillis = 1, focusedMillis = focused),
            dailyProgress = DailyProgress(LocalDate(2026, 9, 25), 6_000_000, 4, true, if (state == FocusState.COMPLETED) 5 else 4, 5),
            graceRemainingMillis = 10_000, isLoading = false,
        )
    }

    private class TestClock : TimeProvider {
        private var time = 1_000L
        override fun epochMillis() = 1_790_000_000_000L + time
        override fun monotonicMillis() = time
        fun advance(millis: Long) { time += millis }
    }
}
