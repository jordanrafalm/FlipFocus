package pl.flipfocus.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.flipfocus.domain.ClanBots
import pl.flipfocus.domain.ClanMember
import pl.flipfocus.domain.Company

private val Gold = Color(0xFFF5C84B)
private val Silver = Color(0xFFC9CDD2)
private val Bronze = Color(0xFFC08A5A)

private fun minutesLabel(minutes: Int) = if (minutes >= 60) "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m" else "${minutes}m"

/** Clan detail: weekly podium and ranking of the active company, with simulated coworkers (bots). */
@Composable
fun ClanHub(
    clans: List<Company>, active: Company, members: List<ClanMember>, selfName: String, selfTitle: String, selfAvatar: String, selfMinutes: Int, copy: Copy,
    onSelectClan: (Company) -> Unit, onAddClan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranking = remember(active.id, members, selfName, selfTitle, selfAvatar, selfMinutes) { ClanBots.ranking(active.id, members, selfName, selfTitle, selfMinutes, selfAvatar) }
    val live = ranking.filter { it.isLive }
    val selfIndex = ranking.indexOfFirst { it.isSelf }
    val podium = ranking.take(3)
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Focus Clan", color = FocusColors.text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            ClanSwitcher(clans, active, copy, onSelectClan, onAddClan)
            IconButton(onAddClan, modifier = Modifier.size(40.dp).border(1.dp, FocusColors.border, CircleShape)
                .semantics { contentDescription = copy.text("Dołącz do kolejnego clanu", "Join another clan") }) {
                Text("+", color = FocusColors.text, style = MaterialTheme.typography.headlineSmall)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            LiveBanner(live, copy)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionLabel(copy.text("TOP 3 TYGODNIA", "WEEKLY TOP 3"))
                Text(copy.text("Pon–Ndz", "Mon–Sun"), style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                // Visual order: #2, #1, #3
                listOf(1, 0, 2).forEach { index ->
                    podium.getOrNull(index)?.let { PodiumSlot(it, index + 1, copy, Modifier.weight(1f)) }
                }
            }
            if (ranking.size > 3) {
                SectionLabel(copy.text("RANKING FIRMY", "COMPANY RANKING"))
                Column {
                    ranking.drop(3).forEachIndexed { i, member ->
                        if (i > 0) HorizontalDivider(color = FocusColors.border)
                        RankRow(member, i + 4, copy)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        val self = ranking[selfIndex]
        val ahead = ranking.getOrNull(selfIndex - 1)
        Row(Modifier.padding(vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(FocusColors.surface)
            .border(BorderStroke(1.dp, FocusColors.green.copy(alpha = .7f)), RoundedCornerShape(24.dp)).padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${self.avatar} #${selfIndex + 1} ${copy.text("Ty", "You")}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (ahead == null) copy.text("Jesteś na czele clanu ${active.name}!", "You lead ${active.name}!")
                    else copy.text("${minutesLabel(ahead.weeklyMinutes - self.weeklyMinutes + 1)} do ${ahead.name} — awansujesz na #$selfIndex!",
                        "${minutesLabel(ahead.weeklyMinutes - self.weeklyMinutes + 1)} behind ${ahead.name} to reach #$selfIndex!"),
                    style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
            }
            Text(minutesLabel(self.weeklyMinutes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = FocusColors.green)
        }
    }
}

@Composable
private fun ClanSwitcher(clans: List<Company>, active: Company, copy: Copy, onSelect: (Company) -> Unit, onShowAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 14.dp),
            modifier = Modifier.widthIn(max = 170.dp).heightIn(min = 40.dp)) {
            Text(active.name + " ⌄", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(open, { open = false }) {
            clans.forEach { clan ->
                DropdownMenuItem(
                    text = { Text((if (clan.id == active.id) "✓ " else "") + clan.name) },
                    onClick = { open = false; onSelect(clan) })
            }
            HorizontalDivider(color = FocusColors.border)
            DropdownMenuItem(
                text = { Text(copy.text("Lista wszystkich", "All companies"), color = FocusColors.green, fontWeight = FontWeight.SemiBold) },
                onClick = { open = false; onShowAll() })
        }
    }
}

@Composable
private fun LiveBanner(live: List<ClanMember>, copy: Copy) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().clip(shape).background(FocusColors.green.copy(alpha = .10f))
        .border(1.dp, FocusColors.green.copy(alpha = .45f), shape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(FocusColors.green))
        Text(
            if (live.isEmpty()) copy.text("Nikt się teraz nie skupia. Bądź pierwszy!", "Nobody is focusing right now. Be the first!")
            else copy.text("${live.size} ${if (live.size == 1) "osoba skupia się" else "osoby skupiają się"} teraz!", "${live.size} ${if (live.size == 1) "teammate is" else "teammates are"} focusing right now!"),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
            live.forEach { Box(Modifier.size(28.dp).clip(CircleShape).background(FocusColors.surface).border(1.dp, FocusColors.border, CircleShape), contentAlignment = Alignment.Center) { Text(it.avatar, fontSize = 14.sp) } }
        }
    }
}

@Composable
private fun PodiumSlot(member: ClanMember, rank: Int, copy: Copy, modifier: Modifier = Modifier) {
    val color = when (rank) { 1 -> Gold; 2 -> Silver; else -> Bronze }
    val size = if (rank == 1) 76.dp else 60.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (rank == 1) "♛" else " ", color = color, fontSize = 18.sp)
        Box(Modifier.size(size).clip(CircleShape).background(FocusColors.surface).border(3.dp, color, CircleShape), contentAlignment = Alignment.Center) {
            Text(member.avatar, fontSize = if (rank == 1) 34.sp else 26.sp)
        }
        Text("#$rank", color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Text(if (member.isSelf) copy.text("Ty", "You") else member.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        if (member.jobTitle.isNotBlank()) Text(member.jobTitle, style = MaterialTheme.typography.labelSmall, color = FocusColors.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(minutesLabel(member.weeklyMinutes), style = MaterialTheme.typography.bodyMedium, color = FocusColors.muted)
    }
}

@Composable
private fun RankRow(member: ClanMember, rank: Int, copy: Copy) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("#$rank", color = FocusColors.muted, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(FocusColors.surface), contentAlignment = Alignment.Center) { Text(member.avatar, fontSize = 20.sp) }
        Column(Modifier.weight(1f)) {
            Text(if (member.isSelf) copy.text("Ty", "You") else member.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (member.jobTitle.isNotBlank()) Text(member.jobTitle, style = MaterialTheme.typography.labelSmall, color = FocusColors.muted)
            if (member.isLive) Text("LIVE · ${member.liveMinutes}m", style = MaterialTheme.typography.labelSmall, color = FocusColors.green)
        }
        Text(minutesLabel(member.weeklyMinutes), fontWeight = FontWeight.Bold)
    }
}
