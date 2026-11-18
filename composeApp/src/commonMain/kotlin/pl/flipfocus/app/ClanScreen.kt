package pl.flipfocus.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pl.flipfocus.domain.*
import pl.flipfocus.presentation.*

private enum class ClanDialog { COMPANY, CREATE_COMPANY, CREATE, ACCOUNT, ROOM, ROOMS, DELETE_ACCOUNT, LEAVE, CLOSE, DISCONNECT, REPORT }

/** The Firebase-backed company directory needs no user-facing service address. */
private const val FIREBASE_ENDPOINT = "firebase"

/** Connected data only. The Figma sample people are confined to test fixtures. */
@Composable
fun ClanRoute(
    viewModel: SocialViewModel, copy: Copy, onBack: () -> Unit, onShare: (String) -> Unit,
    onGoogleSignIn: suspend () -> pl.flipfocus.platform.GoogleSignInResult? = { null },
) {
    val state by viewModel.state.collectAsState()
    var dialog by remember { mutableStateOf<ClanDialog?>(null) }
    var selectedCompany by remember { mutableStateOf<Company?>(null) }
    var showCompanyPicker by remember { mutableStateOf(false) }
    val room = state.selectedRoom
    ClanScreen(state, copy, viewModel::accept, onBack = {
            if (showCompanyPicker) showCompanyPicker = false else onBack()
        }, companyPickerOpen = showCompanyPicker,
        onCompanies = { showCompanyPicker = true; dialog = null },
        onRooms = { dialog = ClanDialog.ROOMS }, onInvite = {
            if (room?.inviteCode != null) onShare(copy.text(
                "Dołącz do clanu ${room.name} w FlipFocus. Kod: ${room.inviteCode}. Usługa: ${state.connection?.endpoint}",
                "Join clan ${room.name} in FlipFocus. Code: ${room.inviteCode}. Service: ${state.connection?.endpoint}"))
            else dialog = ClanDialog.CREATE
        }, onConnect = { dialog = ClanDialog.CREATE_COMPANY },
        onSelectCompany = { company ->
            if (state.connection?.profile?.companyMemberships.orEmpty().any { it.id == company.id }) {
                viewModel.accept(SocialIntent.SelectCompany(company.id))
                showCompanyPicker = false
            } else {
                selectedCompany = company
                dialog = ClanDialog.COMPANY
            }
        },
        onCreate = { dialog = ClanDialog.CREATE },
        onAccount = { dialog = ClanDialog.ACCOUNT }, onRoomSettings = { dialog = ClanDialog.ROOM })

    when (dialog) {
        ClanDialog.COMPANY -> selectedCompany?.let { company -> CompanyJoinDialog(copy, state.isBusy, company,
            onJoin = { nickname, jobTitle, consent, rankingOptIn ->
                viewModel.accept(SocialIntent.ConnectCompany(FIREBASE_ENDPOINT, company.id, nickname, jobTitle, consent, rankingOptIn))
                dialog = null; selectedCompany = null; showCompanyPicker = false
            }, onDismiss = { dialog = null; selectedCompany = null }) }
        ClanDialog.CREATE_COMPANY -> CreateCompanyDialog(copy, state.isBusy, { dialog = null }) { name ->
            viewModel.accept(SocialIntent.CreateCompany(name))
            dialog = null
        }
        ClanDialog.CREATE -> CreateRoomDialog(copy, { dialog = null }) { viewModel.accept(it); dialog = null }
        ClanDialog.ROOMS -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(copy.text("Twoje clany", "Your clans")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                state.rooms.forEach { item -> TextButton({ viewModel.accept(SocialIntent.SelectRoom(item.id)); dialog = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(item.name) } }
                TextButton({ dialog = ClanDialog.CREATE }) { Text(copy.text("+ Utwórz clan", "+ Create a clan")) }
                TextButton({ showCompanyPicker = true; dialog = null }) {
                    Text(copy.text("Zmień firmę lub dołącz do kolejnej", "Switch company or join another"))
                }
                TextButton({ dialog = ClanDialog.ACCOUNT }) { Text(copy.text("Konto i prywatność", "Account and privacy")) }
            } }, confirmButton = { TextButton({ dialog = null }) { Text(copy.done) } })
        ClanDialog.ACCOUNT -> state.connection?.let { connection -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(copy.text("Konto i prywatność", "Account and privacy")) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(connection.profile.nickname, style = MaterialTheme.typography.titleMedium)
                Text(connection.endpoint, style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
                if (connection.profile.companyId != null) FocusCard {
                    Text(copy.text("Firma: ${connection.profile.companyName}", "Company: ${connection.profile.companyName}"), style = MaterialTheme.typography.bodyMedium)
                    Text(copy.text("Każda ukończona sesja jest wysyłana do statystyk wszystkich Twoich firm.", "Every completed session is uploaded to all of your companies' statistics."),
                        style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
                    TextButton({ viewModel.accept(SocialIntent.LeaveCompany) }) { Text(copy.text("Opuść firmę i wyłącz udostępnianie", "Leave company and stop sharing"), color = FocusColors.amber) }
                }
                TextButton({ dialog = ClanDialog.DISCONNECT }) { Text(copy.text("Odłącz konto na urządzeniu", "Disconnect on this device")) }
                TextButton({ dialog = ClanDialog.DELETE_ACCOUNT }) { Text(copy.text("Usuń konto", "Delete account"), color = FocusColors.amber) }
            } }, confirmButton = { TextButton({ dialog = null }) { Text(copy.done) } }) }
        ClanDialog.ROOM -> room?.let { selected -> AlertDialog(
            onDismissRequest = { dialog = null }, title = { Text(selected.name) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(selected.privacyText, style = MaterialTheme.typography.bodyMedium, color = FocusColors.muted)
                val profile = state.connection?.profile
                if (selected.kind == RoomKind.FRIENDS && profile != null) {
                    SettingsToggle("LIVE", copy.text("Udostępniaj tylko aktualny status w tym clanie.", "Share only your current status in this clan."),
                        selected.liveOptIn && profile.liveOptIn, onChange = { enabled ->
                            if (enabled && !profile.liveOptIn) viewModel.accept(SocialIntent.UpdateProfile(profile.copy(liveOptIn = true)))
                            viewModel.accept(SocialIntent.UpdateRoomPreferences(selected.id, enabled, selected.rankingOptIn))
                        })
                    SettingsToggle(copy.text("Ranking tygodniowy", "Weekly ranking"),
                        copy.text("Pokaż pseudonim i sumę minut w tym clanie.", "Show your nickname and total minutes in this clan."),
                        selected.rankingOptIn && profile.rankingOptIn, onChange = { enabled ->
                            if (enabled && !profile.rankingOptIn) viewModel.accept(SocialIntent.UpdateProfile(profile.copy(rankingOptIn = true)))
                            viewModel.accept(SocialIntent.UpdateRoomPreferences(selected.id, selected.liveOptIn, enabled))
                        })
                }
                TextButton({ dialog = ClanDialog.REPORT }) { Text(copy.text("Zgłoś nadużycie", "Report abuse")) }
                if (selected.role == RoomRole.OWNER) TextButton({ dialog = ClanDialog.CLOSE }) { Text(copy.text("Zamknij clan", "Close clan"), color = FocusColors.amber) }
                else TextButton({ dialog = ClanDialog.LEAVE }) { Text(copy.text("Opuść clan", "Leave clan"), color = FocusColors.amber) }
            } }, confirmButton = { TextButton({ dialog = null }) { Text(copy.done) } }) }
        ClanDialog.REPORT -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text(copy.text("Zgłoś nadużycie", "Report abuse")) },
            text = { Column { SocialReportReason.entries.forEach { reason ->
                TextButton({ room?.let { viewModel.accept(SocialIntent.ReportRoom(it.id, reason)) }; dialog = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when (reason) { SocialReportReason.SPAM -> "Spam"; SocialReportReason.HARASSMENT -> copy.text("Nękanie", "Harassment"); SocialReportReason.PRIVACY -> copy.text("Prywatność", "Privacy"); SocialReportReason.OTHER -> copy.text("Inny problem", "Other concern") })
                }
            } } }, confirmButton = { TextButton({ dialog = null }) { Text(copy.cancel) } })
        ClanDialog.DELETE_ACCOUNT, ClanDialog.LEAVE, ClanDialog.CLOSE, ClanDialog.DISCONNECT -> {
            val action = dialog
            AlertDialog(onDismissRequest = { dialog = null }, title = { Text(when (action) {
                ClanDialog.DELETE_ACCOUNT -> copy.text("Usunąć konto?", "Delete account?")
                ClanDialog.CLOSE -> copy.text("Zamknąć clan?", "Close clan?")
                ClanDialog.DISCONNECT -> copy.text("Odłączyć konto?", "Disconnect account?")
                else -> copy.text("Opuścić clan?", "Leave clan?")
            }) }, text = { Text(if (action == ClanDialog.DISCONNECT && state.pendingSyncCount > 0)
                copy.text("Masz ${state.pendingSyncCount} niewysłanych wpisów (minuty/punkty). Odłączenie teraz je utraci — jeśli to możliwe, poczekaj na połączenie z siecią i spróbuj ponownie.",
                    "You have ${state.pendingSyncCount} unsynced entries (minutes/points). Disconnecting now will lose them — if possible, wait for a network connection and retry first.")
                else copy.text("Ta zmiana dotyczy społeczności. Twoja lokalna historia i passa pozostaną zachowane. Usunięcie konta i zamknięcie clanu są trwałe.",
                "This affects the community. Your local history and streak stay safe. Account deletion and clan closure are permanent.")) },
                confirmButton = { TextButton({
                    when (action) {
                        ClanDialog.DELETE_ACCOUNT -> viewModel.accept(SocialIntent.DeleteAccount)
                        ClanDialog.CLOSE -> room?.let { viewModel.accept(SocialIntent.CloseRoom(it.id)) }
                        ClanDialog.DISCONNECT -> viewModel.accept(SocialIntent.Disconnect)
                        else -> room?.let { viewModel.accept(SocialIntent.LeaveRoom(it.id)) }
                    }; dialog = null
                }) { Text(copy.text("Potwierdź", "Confirm")) } },
                dismissButton = { TextButton({ dialog = null }) { Text(copy.cancel) } })
        }
        null -> Unit
    }
    state.invitation?.let { invitation ->
        AlertDialog(onDismissRequest = { viewModel.accept(SocialIntent.DismissInvitation) }, title = { Text(invitation.name) },
            text = { Text(invitation.privacyText + "\n\n" + copy.text("Dołączając, zgadzasz się na te zasady. Status LIVE i ranking pozostają domyślnie wyłączone.",
                "Joining accepts these rules. LIVE and ranking stay off by default.")) },
            confirmButton = { TextButton({ viewModel.accept(SocialIntent.JoinInvitation(true)) }, enabled = !state.isBusy) { Text(copy.text("Akceptuję i dołączam", "Accept and join")) } },
            dismissButton = { TextButton({ viewModel.accept(SocialIntent.DismissInvitation) }) { Text(copy.cancel) } })
    }
}

/** https://www.figma.com/design/Uvw2EdJMOEZ5XV7JagGpmd/Untitled?node-id=2-1055 */
@Composable
fun ClanScreen(
    state: SocialUiState, copy: Copy, onIntent: (SocialIntent) -> Unit, onBack: () -> Unit,
    onRooms: () -> Unit, onInvite: () -> Unit, onConnect: () -> Unit, onSelectCompany: (Company) -> Unit, onCreate: () -> Unit,
    onAccount: () -> Unit, onRoomSettings: () -> Unit, modifier: Modifier = Modifier,
    companyPickerOpen: Boolean = false, onCompanies: () -> Unit = {},
) {
    val room = state.selectedRoom
    val profile = state.connection?.profile
    val pickerMode = profile?.companyId == null || companyPickerOpen
    var companyRange by remember { mutableStateOf(StatsRangeSelector.LAST_7_DAYS) }
    val companySorted = state.companyRanking.sortedByDescending { companyPoints(it, companyRange) }
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pickerMode) {
                IconButton(onBack, modifier = Modifier.size(40.dp).border(1.dp, FocusColors.border, CircleShape)
                    .semantics { contentDescription = copy.text("Wstecz", "Back") }) {
                    Text("←", color = FocusColors.text, style = MaterialTheme.typography.headlineSmall)
                }
                Text(copy.text(if (companyPickerOpen) "Twoje firmy" else "Wybór clanu",
                    if (companyPickerOpen) "Your companies" else "Choose your clan"),
                    color = FocusColors.text, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onConnect, modifier = Modifier.size(40.dp).border(1.dp, FocusColors.border, CircleShape)
                    .semantics { contentDescription = copy.text("Utwórz firmę", "Create company") }) {
                    Text("+", color = FocusColors.text, style = MaterialTheme.typography.headlineSmall)
                }
            } else {
                Text("Focus Clan", color = FocusColors.text, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                OutlinedButton(onRooms, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.widthIn(max = 150.dp).heightIn(min = 40.dp)) {
                    Text((room?.name ?: copy.text("Clany", "Clans")) + " ⌄", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                IconButton(onInvite, modifier = Modifier.size(40.dp).border(1.dp, FocusColors.border, CircleShape)
                    .semantics { contentDescription = copy.text("Zaproś znajomego", "Invite a friend") }) { Text("+", style = MaterialTheme.typography.headlineSmall) }
            }
        }
        if (state.isLoading) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        val errorCode = state.errorCode
        if (errorCode != null) {
            Text(socialMessage(errorCode, copy), color = FocusColors.amber, style = MaterialTheme.typography.bodyMedium)
            TextButton({ onIntent(SocialIntent.Refresh) }, enabled = !state.isBusy) { Text(copy.text("Spróbuj ponownie", "Try again")) }
        }
        if (pickerMode) {
            var companyQuery by remember { mutableStateOf("") }
            var highlighted by remember { mutableStateOf<Company?>(null) }
            LaunchedEffect(Unit) { onIntent(SocialIntent.SearchCompanies(FIREBASE_ENDPOINT, "")) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Box(Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.matchParentSize().align(Alignment.TopCenter)
                                .background(Brush.radialGradient(listOf(FocusColors.green.copy(alpha = 0.20f), Color.Transparent))),
                        )
                        Column(Modifier.padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(copy.text("Skupienie jest lepsze wspólnie.", "Focus is better together."), style = MaterialTheme.typography.headlineLarge)
                            Text(copy.text("Wybierz firmę, aby dołączyć do jej prywatnego rankingu skupienia. Konto jest opcjonalne, a historia sesji zostaje na Twoim urządzeniu.",
                                "Choose your company to join its private focus ranking. An account is optional and your session history stays on your device."),
                                color = FocusColors.muted)
                        }
                    }
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(copy.text("FIRMY W KATEGORII „PRACA”", "COMPANIES IN THE “WORK” CATEGORY"))
                    Text(copy.text("${state.companies.size} dostępnych", "${state.companies.size} available"),
                        style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
                } }
                item { OutlinedTextField(companyQuery, { companyQuery = it.take(80); onIntent(SocialIntent.SearchCompanies(FIREBASE_ENDPOINT, it)) },
                    label = { Text(copy.text("Szukaj firmy", "Search companies")) }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) }
                if (state.isBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.companies.isEmpty() && !state.isBusy) item {
                    Text(copy.text("Nie znaleziono firm. Użyj +, aby utworzyć nową.", "No companies found. Use + to create one."),
                        style = MaterialTheme.typography.bodyMedium, color = FocusColors.muted)
                }
                items(state.companies, key = { it.id }) { company ->
                    CompanyPickerCard(company, company.id == highlighted?.id,
                        profile?.companyMemberships.orEmpty().any { it.id == company.id }, copy) { highlighted = company }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
            highlighted?.let { company ->
                Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(FocusColors.green)
                        .clickable { onSelectCompany(company) }.padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(copy.text(
                                if (profile?.companyMemberships.orEmpty().any { it.id == company.id }) "Przejdź do" else "Dołącz do",
                                if (profile?.companyMemberships.orEmpty().any { it.id == company.id }) "Switch to" else "Join",
                            ), style = MaterialTheme.typography.labelSmall, color = FocusColors.onGreen.copy(alpha = .7f))
                            Text(company.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FocusColors.onGreen)
                        }
                        Text(
                            if (profile?.companyMemberships.orEmpty().any { it.id == company.id }) "✓" else "→",
                            style = MaterialTheme.typography.headlineSmall, color = FocusColors.onGreen,
                        )
                    }
                    Text(copy.text("Możesz opuścić firmę w dowolnym momencie w Koncie i prywatności.", "You can leave the company anytime in Account and privacy."),
                        style = MaterialTheme.typography.labelSmall, color = FocusColors.muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            return@Column
        }
        if (room == null) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Spacer(Modifier.height(24.dp))
                if (profile.companyId != null) {
                    PersonalCompanyCard(profile.companyName, state.personalStats, copy)
                    CompanyRankingSection(profile, companySorted, companyRange, { companyRange = it }, onIntent, copy)
                }
                TextButton(onAccount) { Text(copy.text("Konto i prywatność", "Account and privacy")) }
            }
            val ownCompanyIndex = companySorted.indexOfFirst { it.userId == profile.userId }
            if (profile.companyRankingOptIn && ownCompanyIndex >= 0) {
                val entry = companySorted[ownCompanyIndex]
                val points = companyPoints(entry, companyRange)
                FocusCard(Modifier.padding(vertical = 8.dp).border(1.dp, FocusColors.green.copy(alpha = .4f), RoundedCornerShape(24.dp))) {
                    StatRow("#${ownCompanyIndex + 1}  " + copy.text("Ty", "You"), copy.time(points * 60_000L), FocusColors.green)
                    val previous = companySorted.getOrNull(ownCompanyIndex - 1)
                    if (previous != null) Text(copy.text("${copy.time((companyPoints(previous, companyRange) - points).coerceAtLeast(0) * 60_000L)} do kolejnego miejsca. W swoim tempie.",
                        "${copy.time((companyPoints(previous, companyRange) - points).coerceAtLeast(0) * 60_000L)} to the next place. At your own pace."),
                        style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
                }
            }
            return@Column
        }
        val ownIndex = room.ranking.indexOfFirst { it.userId == profile.userId }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (profile.companyId != null) {
                PersonalCompanyCard(profile.companyName, state.personalStats, copy)
                CompanyRankingSection(profile, companySorted, companyRange, { companyRange = it }, onIntent, copy)
            }
            if (room.kind == RoomKind.FRIENDS) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(FocusColors.greenSurface)
                    .border(1.dp, FocusColors.green.copy(alpha = .33f), RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(8.dp).background(FocusColors.green, CircleShape))
                    Text(if (room.live.isEmpty()) copy.text("Spokojnie. Każdy ma swój rytm.", "Quiet here. Everyone has their own rhythm.")
                        else copy.text("${room.live.size} os. skupia się właśnie teraz", "${room.live.size} people are focusing right now!"),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    room.live.take(3).forEachIndexed { index, member ->
                        Avatar(member.nickname, FocusColors.green, Modifier.size(28.dp).offset(x = (-8 * index).dp))
                    }
                }
                if (room.ranking.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SectionLabel(copy.text("TOP 3 TYGODNIA", "WEEKLY TOP 3"))
                        Text(copy.text("Pon–Niedz", "Mon–Sun"), style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 142.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                        listOf(1, 0, 2).forEach { index ->
                            val entry = room.ranking.getOrNull(index)
                            if (entry == null) Spacer(Modifier.weight(1f)) else PodiumPlace(entry.nickname, entry.completedMinutes, index + 1, copy, Modifier.weight(1f))
                        }
                    }
                    SectionLabel(copy.text("RANKING ZNAJOMYCH", "FRIENDS RANKING"))
                    room.ranking.drop(3).forEachIndexed { index, entry ->
                        RankingRow(entry.nickname, entry.completedMinutes, index + 4, entry.userId == profile.userId, room.live.any { it.userId == entry.userId }, copy,
                            enabled = !state.isBusy)
                        HorizontalDivider(color = FocusColors.border)
                    }
                } else FocusCard {
                    Text(copy.text("Wspólnie, na własnych zasadach", "Together, on your own terms"), style = MaterialTheme.typography.titleMedium)
                    Text(copy.text("Ranking jest dobrowolny. Włącz go w ustawieniach clanu, jeśli pomaga Ci w rytuale.",
                        "Ranking is optional. Enable it in clan settings if it helps your ritual."), style = MaterialTheme.typography.bodyMedium, color = FocusColors.muted)
                }
            }
            FocusCard {
                Text(copy.text("WSPÓLNY CEL", "SHARED GOAL"), style = MaterialTheme.typography.labelSmall, color = FocusColors.muted)
                Spacer(Modifier.height(8.dp))
                if (room.aggregate.visible) {
                    StatRow(copy.time((room.aggregate.completedMinutes ?: 0) * 60_000L), copy.time(room.goalMinutes * 60_000L), FocusColors.green)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { ((room.aggregate.completedMinutes ?: 0).toFloat() / room.goalMinutes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                } else Text(copy.text("Wynik pojawi się po zakończeniu tygodnia, gdy co najmniej 3 osoby wniosą swój wkład.",
                    "Results appear after the week ends, once at least 3 people have contributed."), style = MaterialTheme.typography.bodyMedium, color = FocusColors.muted)
                Text(copy.text("Tydzień od ", "Week of ") + room.week, style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onRoomSettings, enabled = !state.isBusy) { Text(copy.text("Zasady clanu", "Clan settings")) }
                TextButton({ onIntent(SocialIntent.Refresh) }, enabled = !state.isBusy) { Text(copy.text("Odśwież", "Refresh")) }
            }
        }
        if (ownIndex >= 0) {
            val entry = room.ranking[ownIndex]
            FocusCard(Modifier.padding(vertical = 8.dp).border(1.dp, FocusColors.green.copy(alpha = .4f), RoundedCornerShape(24.dp))) {
                StatRow("#${ownIndex + 1}  " + copy.text("Ty", "You"), copy.time(entry.completedMinutes * 60_000L), FocusColors.green)
                val previous = room.ranking.getOrNull(ownIndex - 1)
                if (previous != null) Text(copy.text("${copy.time((previous.completedMinutes - entry.completedMinutes).coerceAtLeast(0) * 60_000L)} do kolejnego miejsca. W swoim tempie.",
                    "${copy.time((previous.completedMinutes - entry.completedMinutes).coerceAtLeast(0) * 60_000L)} to the next place. At your own pace."),
                    style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
            }
        }
    }
}

/**
 * One row in the company picker (Figma 53:104). No fabricated member counts, avatars or LIVE
 * activity: the Spark plan has no server-side company roster/presence, so the subtitle stays a
 * static, data-honest descriptor instead of inventing pseudo-activity.
 */
@Composable
private fun CompanyPickerCard(company: Company, selected: Boolean, joined: Boolean, copy: Copy, onClick: () -> Unit) {
    FocusCard(Modifier.heightIn(min = 132.dp).clickable(onClick = onClick)
        .then(if (selected) Modifier.border(1.dp, FocusColors.green, RoundedCornerShape(24.dp)) else Modifier)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(company.name, if (selected) FocusColors.green else FocusColors.border, Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(company.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(copy.text(
                    if (joined) "Jesteś członkiem tej firmy" else "Prywatny ranking skupienia pracowników",
                    if (joined) "You are a member of this company" else "A private focus ranking for employees",
                ),
                    style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
            }
            if (selected) Text("✓", color = FocusColors.green, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = FocusColors.border)
        Spacer(Modifier.height(12.dp))
        Text(copy.text("Dołączysz do rankingu na własnych zasadach", "You'll join the ranking on your own terms"),
            style = MaterialTheme.typography.labelSmall, color = FocusColors.muted)
    }
}

@Composable
private fun PersonalCompanyCard(companyName: String?, personal: PersonalStats?, copy: Copy) {
    FocusCard {
        Text(copy.text("TWOJA FIRMA", "YOUR COMPANY"), style = MaterialTheme.typography.labelSmall, color = FocusColors.muted)
        Text(companyName ?: "", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (personal != null) {
            StatRow(copy.text("Ty (7 dni)", "You (7 days)"), copy.time(personal.last7Days.completedMinutes * 60_000L), FocusColors.green)
            StatRow(copy.text("Ty (cały okres)", "You (all-time)"), copy.time(personal.allTime.completedMinutes * 60_000L), FocusColors.green)
        }
    }
}

private enum class StatsRangeSelector { LAST_7_DAYS, ALL_TIME }

private fun companyPoints(entry: CompanyRankingEntry, range: StatsRangeSelector): Int =
    (if (range == StatsRangeSelector.LAST_7_DAYS) entry.last7Days else entry.allTime).completedMinutes

/** Self-reported (Spark plan, no server verification): only coworkers who opted in appear. Figma node 2:1055. */
@Composable
private fun CompanyRankingSection(
    profile: SocialProfile, sorted: List<CompanyRankingEntry>, range: StatsRangeSelector,
    onRangeChange: (StatsRangeSelector) -> Unit, onIntent: (SocialIntent) -> Unit, copy: Copy,
) {
    FocusCard {
        Text(copy.text("RANKING FIRMY", "COMPANY RANKING"), style = MaterialTheme.typography.labelSmall, color = FocusColors.muted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(range == StatsRangeSelector.LAST_7_DAYS, { onRangeChange(StatsRangeSelector.LAST_7_DAYS) },
                label = { Text(copy.text("7 dni", "7 days")) }, modifier = Modifier.heightIn(min = 40.dp))
            FilterChip(range == StatsRangeSelector.ALL_TIME, { onRangeChange(StatsRangeSelector.ALL_TIME) },
                label = { Text(copy.text("Cały okres", "All-time")) }, modifier = Modifier.heightIn(min = 40.dp))
        }
        Spacer(Modifier.height(8.dp))
        SettingsToggle(copy.text("Pokaż mnie w rankingu firmy", "Show me in the company ranking"),
            copy.text("Współpracownicy z tej samej firmy zobaczą Twój pseudonim, opcjonalne stanowisko i punkty. Wyniki są deklarowane przez urządzenie i nie są weryfikowane przez serwer.",
                "Coworkers at this same company will see your nickname, optional title and points. Scores are self-reported by the device and not server-verified."),
            profile.companyRankingOptIn, onChange = { onIntent(SocialIntent.UpdateProfile(profile.copy(companyRankingOptIn = it))) })
        Spacer(Modifier.height(8.dp))
        when {
            !profile.companyRankingOptIn -> Text(copy.text("Włącz powyżej, aby zobaczyć i pojawić się w rankingu.", "Enable above to see and appear in the ranking."),
                style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
            sorted.isEmpty() -> Text(copy.text("Jeszcze nikt nie dołączył do rankingu tej firmy.", "No one has joined this company's ranking yet."),
                style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
            else -> {
                val rangeLabel = if (range == StatsRangeSelector.LAST_7_DAYS) copy.text("Pon–Niedz", "Mon–Sun") else copy.text("Cały okres", "All-time")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionLabel(copy.text("TOP 3", "TOP 3"))
                    Text(rangeLabel, style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 142.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    listOf(1, 0, 2).forEach { index ->
                        val entry = sorted.getOrNull(index)
                        if (entry == null) Spacer(Modifier.weight(1f))
                        else PodiumPlace(entry.nickname, companyPoints(entry, range), index + 1, copy, Modifier.weight(1f))
                    }
                }
                if (sorted.size > 3) {
                    Spacer(Modifier.height(4.dp))
                    val rest = sorted.drop(3)
                    rest.forEachIndexed { index, entry ->
                        RankingRow(entry.nickname, companyPoints(entry, range), index + 4, entry.userId == profile.userId, live = false, copy,
                            subtitle = entry.jobTitle?.takeIf { it.isNotBlank() })
                        if (index != rest.lastIndex) HorizontalDivider(color = FocusColors.border)
                    }
                }
            }
        }
    }
}

@Composable
private fun PodiumPlace(nickname: String, minutes: Int, rank: Int, copy: Copy, modifier: Modifier = Modifier) {
    val color = when (rank) { 1 -> FocusColors.gold; 2 -> FocusColors.silver; else -> FocusColors.bronze }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (rank == 1) "♛" else "•", color = color, style = MaterialTheme.typography.titleLarge)
        Avatar(nickname, color, Modifier.size(if (rank == 1) 58.dp else 46.dp))
        Text("#$rank", style = MaterialTheme.typography.labelSmall, color = color)
        Text(nickname, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
        Text(copy.time(minutes * 60_000L), style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
    }
}

@Composable
private fun Avatar(name: String, color: Color = FocusColors.border, modifier: Modifier = Modifier) {
    Box(modifier.clip(CircleShape).background(FocusColors.raised).border(2.dp, color, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = FocusColors.text)
    }
}

@Composable
private fun RankingRow(
    nickname: String, minutes: Int, rank: Int, own: Boolean, live: Boolean, copy: Copy,
    onNudge: (() -> Unit)? = null, enabled: Boolean = true, subtitle: String? = null, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("#$rank", style = MaterialTheme.typography.bodySmall, color = FocusColors.muted)
        Avatar(nickname, modifier = Modifier.size(36.dp))
        Column(Modifier.weight(1f)) {
            Text(if (own) copy.text("Ty", "You") else nickname, style = MaterialTheme.typography.titleMedium)
            if (live) Text("LIVE", style = MaterialTheme.typography.labelSmall, color = FocusColors.green)
            else subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = FocusColors.muted) }
        }
        Text(copy.time(minutes * 60_000L), style = MaterialTheme.typography.bodyMedium)
        if (onNudge != null && !own) IconButton(onNudge, enabled = enabled, modifier = Modifier.semantics { contentDescription = copy.text("Zaproś $nickname", "Nudge $nickname") }) { Text("⚡") }
    }
}

@Composable
private fun CreateCompanyDialog(copy: Copy, busy: Boolean, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(copy.text("Utwórz firmę", "Create a company")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(copy.text("Podaj nazwę widoczną dla innych użytkowników. Nowa firma zostanie dodana do Twojego konta.",
                "Enter the name other users will see. The new company will be added to your account."),
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(copy.text("Nazwa firmy", "Company name")) },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        } }, confirmButton = { TextButton({ onCreate(name.trim()) },
            enabled = name.trim().length >= 2 && !busy) { Text(copy.text("Utwórz", "Create")) } },
        dismissButton = { TextButton(onDismiss) { Text(copy.cancel) } })
}

@Composable
private fun CreateRoomDialog(copy: Copy, onDismiss: () -> Unit, onCreate: (SocialIntent.CreateRoom) -> Unit) {
    var name by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(copy.text("Nowy clan", "New clan")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text(copy.text("Nazwa clanu", "Clan name")) }, singleLine = true)
            Text(copy.text("Prywatny clan do wspólnego skupienia. Jako właściciel możesz zamknąć go w dowolnym momencie.",
                "A private clan for focusing together. As its owner, you can close it anytime."), style = MaterialTheme.typography.bodyMedium)
            ConsentRow(consent, { consent = it }, copy.text("Akceptuję udostępnianie zbiorczych wyników. Historia i dokładne godziny sesji pozostają prywatne.",
                "I accept sharing aggregate results. History and exact session times remain private."))
        } }, confirmButton = { TextButton({ onCreate(SocialIntent.CreateRoom(name, RoomKind.FRIENDS, 500, 25, consent)) },
            enabled = name.isNotBlank() && consent) { Text(copy.text("Utwórz", "Create")) } },
        dismissButton = { TextButton(onDismiss) { Text(copy.cancel) } })
}

@Composable
private fun CompanyJoinDialog(
    copy: Copy, busy: Boolean, company: Company,
    onJoin: (nickname: String, jobTitle: String, consent: Boolean, rankingOptIn: Boolean) -> Unit, onDismiss: () -> Unit,
) {
    var nickname by remember { mutableStateOf("") }
    var jobTitle by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var rankingOptIn by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(copy.text("Dołącz do ${company.name}", "Join ${company.name}")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(copy.text(
                "Po dołączeniu każda ukończona sesja (minuty i punkty) będzie automatycznie wysyłana do statystyk tej firmy, dopóki jej nie opuścisz w Koncie i prywatności. Surowe dane Twoich sesji pozostają prywatne — widoczne wyłącznie dla Ciebie.",
                "Once you join, every completed session (minutes and points) is automatically uploaded to this company's statistics until you leave it in Account and privacy. Your raw session data stays private — visible only to you."),
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(nickname, { nickname = it.take(40) }, label = { Text(copy.text("Login / pseudonim (wymagane)", "Login / nickname (required)")) }, singleLine = true)
            OutlinedTextField(jobTitle, { jobTitle = it.take(80) }, label = { Text(copy.text("Stanowisko (opcjonalnie)", "Job title (optional)")) }, singleLine = true)
            ConsentRow(consent, { consent = it }, copy.text("Akceptuję warunki udostępniania opisane powyżej.", "I accept the sharing terms described above."))
            ConsentRow(rankingOptIn, { rankingOptIn = it }, copy.text(
                "Pokaż mnie w rankingu tej firmy: pseudonim, opcjonalne stanowisko i punkty będą widoczne dla współpracowników z tej samej firmy. Można wyłączyć w dowolnym momencie. Wyniki są deklarowane przez urządzenie, bez weryfikacji serwera.",
                "Show me in this company's ranking: nickname, optional title and points become visible to coworkers at the same company. You can turn this off anytime. Scores are self-reported by the device, not server-verified."))
        } },
        confirmButton = { TextButton({ onJoin(nickname.trim(), jobTitle.trim(), consent, rankingOptIn) },
            enabled = nickname.isNotBlank() && consent && !busy) { Text(copy.text("Dołącz", "Join")) } },
        dismissButton = { TextButton(onDismiss) { Text(copy.cancel) } })
}

@Composable
private fun ConsentRow(checked: Boolean, onChange: (Boolean) -> Unit, text: String) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, modifier = Modifier.semantics { contentDescription = text })
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

fun socialMessage(code: String, copy: Copy): String = when (code) {
    "company_not_found" -> copy.text("Nie znaleziono wybranej firmy.", "The selected company was not found.")
    "company_not_selected" -> copy.text("Najpierw wybierz firmę.", "Choose a company first.")
    "invalid_job_title" -> copy.text("Wpisz prawidłowe stanowisko.", "Enter a valid job title.")
    "invalid_company_name" -> copy.text("Nazwa firmy musi mieć od 2 do 80 znaków.", "Company name must be between 2 and 80 characters.")
    "invalid_endpoint" -> copy.text("Sprawdź adres usługi. Połączenie wymaga HTTPS.", "Check the service address. HTTPS is required.")
    "privacy_required" -> copy.text("Najpierw zapoznaj się z zasadami i zaakceptuj je.", "Please read and accept the privacy rules first.")
    "unauthorized", "invalid_token" -> copy.text("Połącz ponownie konto, aby kontynuować.", "Reconnect your account to continue.")
    "quiet_hours", "rate_limited", "nudge_declined", "nudges_disabled", "nudge_not_allowed" -> copy.text("Ta osoba nie przyjmuje teraz zaproszeń. Daj jej przestrzeń.", "This person isn't receiving nudges now. Give them some space.")
    "room_closed" -> copy.text("Ten clan jest zamknięty.", "This clan is closed.")
    "invalid_invitation", "not_found" -> copy.text("Nie znaleziono aktywnego zaproszenia lub clanu.", "No active invitation or clan was found.")
    "report_submitted" -> copy.text("Zgłoszenie zostało zapisane dla administratora usługi.", "Your report was saved for the service administrator.")
    "nudge_sent" -> copy.text("Zaproszenie wysłane.", "Nudge sent.")
    "account_deleted" -> copy.text("Konto usunięte. Lokalna historia została zachowana.", "Account deleted. Local history is safe.")
    "credential_already_in_use" -> copy.text("Ten e-mail jest już połączony z innym kontem.", "This email is already linked to another account.")
    "not_supported" -> copy.text("Ta funkcja nie jest jeszcze dostępna.", "This feature isn't available yet.")
    "auth_provider_disabled" -> copy.text("Logowanie anonimowe nie jest włączone dla tej usługi. Poproś administratora o włączenie go w konsoli Firebase (Authentication → Sign-in method → Anonymous).", "Anonymous sign-in isn't enabled for this service. Ask an administrator to enable it in the Firebase console (Authentication → Sign-in method → Anonymous).")
    "auth_user_disabled" -> copy.text("To konto zostało zablokowane przez administratora.", "This account has been disabled by an administrator.")
    "auth_rate_limited" -> copy.text("Zbyt wiele prób logowania. Odczekaj chwilę i spróbuj ponownie.", "Too many sign-in attempts. Wait a moment and try again.")
    "auth_error" -> copy.text("Nie udało się zweryfikować konta. Spróbuj ponownie później.", "We couldn't verify your account. Try again later.")
    "permission_denied" -> copy.text("Brak uprawnień do tej operacji. Reguły bezpieczeństwa Firestore mogą wymagać wdrożenia.", "You don't have permission for this action. The Firestore security rules may need to be deployed.")
    "failed_precondition" -> copy.text("Usługa wymaga dodatkowej konfiguracji (np. indeksu Firestore). Spróbuj ponownie później.", "The service needs extra configuration (e.g. a Firestore index). Try again later.")
    "unauthenticated" -> copy.text("Sesja wygasła. Połącz się ponownie.", "Your session expired. Reconnect to continue.")
    "unavailable" -> copy.text("Usługa jest chwilowo niedostępna. Sprawdź połączenie i spróbuj ponownie.", "The service is temporarily unavailable. Check your connection and try again.")
    else -> copy.text("Nie udało się wykonać tej czynności. Sprawdź połączenie i spróbuj ponownie.", "That action couldn't be completed. Check your connection and try again.")
}
