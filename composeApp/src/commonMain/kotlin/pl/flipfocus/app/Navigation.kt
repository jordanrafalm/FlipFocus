package pl.flipfocus.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import pl.flipfocus.presentation.FocusScreen

/** Overlay destinations with no VM-tracked state (safe local UI concern only, never session truth). */
enum class ExtraScreen { INSIGHTS, CLAN }

/** Unified bottom-nav / swipe destinations. Declaration order drives swipe direction and tab layout. */
enum class AppDestination { FOCUS, CLAN, HISTORY, SETTINGS }

fun AppDestination.icon(): ImageVector = when (this) {
    AppDestination.FOCUS -> Icons.Outlined.AccessTime
    AppDestination.CLAN -> Icons.Outlined.Groups
    AppDestination.HISTORY -> Icons.Outlined.BarChart
    AppDestination.SETTINGS -> Icons.Outlined.Person
}

fun AppDestination.label(copy: Copy): String = when (this) {
    AppDestination.FOCUS -> "Focus"
    AppDestination.CLAN -> "Clan"
    AppDestination.HISTORY -> copy.text("Statystyki", "Statistics")
    AppDestination.SETTINGS -> copy.text("Profil", "Profile")
}

/** Where selecting a destination should go: the VM (single source of truth for FOCUS/HISTORY/SETTINGS) or a local overlay. */
sealed interface DestinationAction {
    data class OpenVmScreen(val screen: FocusScreen) : DestinationAction
    data class ShowExtra(val extra: ExtraScreen) : DestinationAction
}

fun destinationAction(destination: AppDestination): DestinationAction = when (destination) {
    AppDestination.FOCUS -> DestinationAction.OpenVmScreen(FocusScreen.FOCUS)
    AppDestination.CLAN -> DestinationAction.ShowExtra(ExtraScreen.CLAN)
    AppDestination.HISTORY -> DestinationAction.OpenVmScreen(FocusScreen.HISTORY)
    AppDestination.SETTINGS -> DestinationAction.OpenVmScreen(FocusScreen.SETTINGS)
}

/** Inverse of [destinationAction]: derives the active tab from the VM screen plus any local overlay. */
fun currentDestination(screen: FocusScreen, extra: ExtraScreen?): AppDestination = when (extra) {
    ExtraScreen.INSIGHTS -> AppDestination.FOCUS
    ExtraScreen.CLAN -> AppDestination.CLAN
    null -> when (screen) {
        FocusScreen.FOCUS -> AppDestination.FOCUS
        FocusScreen.HISTORY -> AppDestination.HISTORY
        FocusScreen.SETTINGS -> AppDestination.SETTINGS
    }
}

private val bottomNavFloatMotionSpec = tween<Float>(durationMillis = 420, easing = FastOutSlowInEasing)

internal fun swipeProgress(dragDeltaPx: Float, thresholdPx: Float): Float =
    (abs(dragDeltaPx) / thresholdPx).coerceIn(0f, 1f)

/** Resolves a horizontal drag into the next/previous destination once past [thresholdPx]; a no-op at either end. */
fun AppDestination.nextDestination(): AppDestination? = AppDestination.entries.getOrNull(AppDestination.entries.indexOf(this) + 1)
fun AppDestination.previousDestination(): AppDestination? = AppDestination.entries.getOrNull(AppDestination.entries.indexOf(this) - 1)

fun AppDestination.swiped(dragDeltaPx: Float, thresholdPx: Float): AppDestination {
    val order = AppDestination.entries
    val index = order.indexOf(this)
    return when {
        dragDeltaPx <= -thresholdPx && index < order.lastIndex -> order[index + 1]
        dragDeltaPx >= thresholdPx && index > 0 -> order[index - 1]
        else -> this
    }
}

/** Floating glass nav bar, figma node 10:3. A green glow travels from the previously selected tab to the newly tapped one. */
@Composable
fun BottomNavBar(current: AppDestination, copy: Copy, onSelect: (AppDestination) -> Unit, modifier: Modifier = Modifier) {
    val entries = AppDestination.entries
    val currentIndex = entries.indexOf(current)
    val glowPosition = remember { Animatable(currentIndex.toFloat()) }
    LaunchedEffect(currentIndex) {
        glowPosition.animateTo(currentIndex.toFloat(), bottomNavFloatMotionSpec)
    }
    Box(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(24.dp)).background(FocusColors.navSurface)
            .border(1.dp, FocusColors.navBorder, RoundedCornerShape(24.dp)),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
            val slotWidth = maxWidth / entries.size
            Box(
                Modifier.offset(x = slotWidth * glowPosition.value).width(slotWidth).height(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(64.dp).background(
                        Brush.radialGradient(listOf(FocusColors.green.copy(alpha = 0.32f), FocusColors.green.copy(alpha = 0f))),
                        CircleShape,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                entries.forEach { destination ->
                    val isSelected = destination == current
                    val tint by androidx.compose.animation.animateColorAsState(if (isSelected) FocusColors.green else FocusColors.muted)
                    Column(
                        Modifier.width(72.dp).height(52.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = { onSelect(destination) })
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                selected = isSelected
                                contentDescription = destination.label(copy)
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(destination.icon(), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                        Text(
                            destination.label(copy), style = MaterialTheme.typography.bodySmall,
                            color = tint,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hosts destinations with native pager gestures, so consecutive swipes are never blocked by the
 * bottom-navigation animation or by external state taking a frame to catch up.
 */
@Composable
fun SwipeableDestinationHost(
    destination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (AppDestination) -> Unit,
) {
    val entries = AppDestination.entries
    val pagerState = rememberPagerState(
        initialPage = entries.indexOf(destination),
        pageCount = { entries.size },
    )

    LaunchedEffect(destination) {
        val page = entries.indexOf(destination)
        if (pagerState.currentPage != page || pagerState.targetPage != page) {
            pagerState.animateScrollToPage(page)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val settled = entries[pagerState.settledPage]
        if (settled != destination) onNavigate(settled)
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().background(FocusColors.background),
        beyondViewportPageCount = 1,
    ) { page ->
        Box(Modifier.fillMaxSize()) {
            content(entries[page])
        }
    }
}
