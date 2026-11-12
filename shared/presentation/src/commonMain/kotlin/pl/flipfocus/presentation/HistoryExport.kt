package pl.flipfocus.presentation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pl.flipfocus.domain.FocusHistoryEntry

/** Export is explicit and local; no analytics or sharing occurs until the UI handles the effect. */
object HistoryExport {
    private val exportJson = Json { prettyPrint = true }
    fun format(history: List<FocusHistoryEntry>, format: ExportFormat): String = when (format) {
        ExportFormat.JSON -> exportJson.encodeToString(history.map(ExportEntry::from))
        ExportFormat.CSV -> buildString {
            append("id,duration_minutes,focused_millis,started_at_epoch_millis,ended_at_epoch_millis,outcome,end_reason\r\n")
            history.forEach {
                append(listOf(it.id, it.durationMinutes.toString(), it.focusedMillis.toString(),
                    it.startedAtEpochMillis.toString(), it.endedAtEpochMillis.toString(), it.outcome.name,
                    it.endReason.name).joinToString(",", transform = ::csvCell))
                append("\r\n")
            }
        }
    }

    private fun csvCell(value: String): String {
        // Protect spreadsheet users from formula evaluation in imported identifiers.
        val safe = if (value.firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}

@Serializable
private data class ExportEntry(
    val id: String, val durationMinutes: Int, val focusedMillis: Long,
    val startedAtEpochMillis: Long, val endedAtEpochMillis: Long,
    val outcome: String, val endReason: String,
) {
    companion object {
        fun from(entry: FocusHistoryEntry) = ExportEntry(entry.id, entry.durationMinutes, entry.focusedMillis,
            entry.startedAtEpochMillis, entry.endedAtEpochMillis, entry.outcome.name, entry.endReason.name)
    }
}
