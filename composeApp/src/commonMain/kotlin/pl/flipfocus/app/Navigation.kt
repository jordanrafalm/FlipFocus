package pl.flipfocus.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * Hosts the active destination's content as a finger-tracking pager: the current page follows the drag
 * one-to-one while the neighbouring page slides in beside it. On release the pages settle (using the fling
 * velocity) onto the target or spring back. Tab taps animate the same way. Vertical drags are left alone.
 */
@Composable
fun SwipeableDestinationHost(
    destination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (AppDestination) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(0f) }
    var base by remember { mutableStateOf(destination) }
    var incoming by remember { mutableStateOf<AppDestination?>(null) }
    val settleSpec = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 380f)

    LaunchedEffect(destination) {
        // A previous transition may have been cancelled mid-flight by a rapid tab change, so re-derive from current state.
        if (destination == base) {
            if (offset.value != 0f) offset.animateTo(0f, settleSpec)
            incoming = null
            return@LaunchedEffect
        }
        val order = AppDestination.entries
        val forward = order.indexOf(destination) > order.indexOf(base)
        if (incoming != destination && offset.value != 0f && (offset.value < 0f) != forward) offset.snapTo(0f)
        incoming = destination
        offset.animateTo(if (forward) -widthPx else widthPx, settleSpec)
        base = destination
        incoming = null
        offset.snapTo(0f)
    }

    val dragState = rememberDraggableState { delta ->
        if (widthPx <= 0f || destination != base) return@rememberDraggableState
        val current = offset.value
        val raw = current + delta
        val neighbor = when {
            raw < 0f -> base.nextDestination()
            raw > 0f -> base.previousDestination()
            else -> null
        }
        incoming = neighbor
        val next = if (neighbor == null) current + delta * 0.3f else raw
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            offset.snapTo(next.coerceIn(-widthPx, widthPx))
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStopped = { velocity ->
                    val target = incoming
                    val current = offset.value
                    val commit = target != null && widthPx > 0f && destination == base &&
                        (abs(current) > widthPx * 0.07f || (abs(velocity) > 120f && velocity * current >= 0f && abs(current) > 4f))
                    scope.launch {
                        if (commit && target != null) {
                            val end = if (current < 0f) -widthPx else widthPx
                            offset.animateTo(end, settleSpec, initialVelocity = velocity)
                            onNavigate(target)
                        } else {
                            offset.animateTo(0f, settleSpec, initialVelocity = velocity)
                            if (destination == base) incoming = null
                        }
                    }
                },
            ),
    ) {
        Box(Modifier.fillMaxSize().background(FocusColors.background))
        val pages = listOfNotNull(base, incoming)
        pages.forEach { page ->
            key(page) {
                Box(
                    Modifier.fillMaxSize().offset {
                        val side = if (page == base) 0f else {
                            if (AppDestination.entries.indexOf(page) > AppDestination.entries.indexOf(base)) widthPx else -widthPx
                        }
                        IntOffset((offset.value + side).roundToInt(), 0)
                    },
                ) {
                    content(page)
                }
            }
        }
    }
}
