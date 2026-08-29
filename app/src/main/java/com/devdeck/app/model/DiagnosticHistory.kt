package com.devdeck.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class IncidentStatus {
    DETECTED,
    DIAGNOSED,
    REPAIR_SENT,
    SOLVED,
    FAILED,
    SUPERSEDED
}

data class HistoryItem(
    val incidentId: String,
    val timestamp: Long,
    val errorFile: String,
    val errorLine: Int,
    val errorText: String,
    val rootCause: String,
    val repairCode: String?,
    val diffText: String?,
    val patchType: PatchType,
    var status: IncidentStatus
)

class DiagnosticHistory(private val context: Context) {
    private val historyFile: File
        get() = File(context.filesDir, "incidents_history.json")

    @Synchronized
    fun addOrUpdateIncident(
        incidentId: String,
        errorFile: String,
        errorLine: Int,
        errorText: String,
        result: DiagnosticResult,
        status: IncidentStatus = IncidentStatus.DIAGNOSED
    ) {
        val items = loadAll().toMutableList()
        val existingIndex = items.indexOfFirst { it.incidentId == incidentId }
        val newItem = HistoryItem(
            incidentId = incidentId,
            timestamp = System.currentTimeMillis(),
            errorFile = errorFile,
            errorLine = errorLine,
            errorText = errorText,
            rootCause = result.rootCause,
            repairCode = result.repairCode,
            diffText = result.diffText,
            patchType = result.patchType,
            status = status
        )
        if (existingIndex >= 0) {
            items[existingIndex] = newItem
        } else {
            items.add(0, newItem)
        }
        // Retain up to 200 incidents
        if (items.size > 200) {
            items.removeAt(items.size - 1)
        }
        saveAll(items)
    }

    @Synchronized
    fun updateStatus(incidentId: String?, status: IncidentStatus) {
        val items = loadAll().toMutableList()
        if (incidentId.isNullOrBlank()) {
            if (items.isNotEmpty()) {
                items[0].status = status
                saveAll(items)
            }
            return
        }
        val index = items.indexOfFirst { it.incidentId == incidentId }
        if (index >= 0) {
            items[index].status = status
            saveAll(items)
        }
    }

    @Synchronized
    fun markLatestSolved() {
        val items = loadAll().toMutableList()
        if (items.isNotEmpty()) {
            items[0].status = IncidentStatus.SOLVED
            saveAll(items)
        }
    }

    @Synchronized
    fun getLatest(): HistoryItem? {
        val items = loadAll()
        return items.firstOrNull()
    }

    @Synchronized
    fun loadAll(): List<HistoryItem> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(historyFile.readText(Charsets.UTF_8))
            val list = mutableListOf<HistoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        incidentId = obj.optString("incident_id", "inc_$i"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        errorFile = obj.optString("error_file", "unknown"),
                        errorLine = obj.optInt("error_line", 0),
                        errorText = obj.optString("error_text", ""),
                        rootCause = obj.optString("root_cause", ""),
                        repairCode = obj.optString("repair_code", "").takeIf { it.isNotEmpty() },
                        diffText = obj.optString("diff_text", "").takeIf { it.isNotEmpty() },
                        patchType = if (obj.optString("patch_type") == "DIFF") PatchType.DIFF else PatchType.SINGLE_LINE,
                        status = try { IncidentStatus.valueOf(obj.optString("status", "DIAGNOSED")) } catch (_: Exception) { IncidentStatus.DIAGNOSED }
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAll(items: List<HistoryItem>) {
        try {
            val array = JSONArray()
            for (item in items) {
                val obj = JSONObject().apply {
                    put("incident_id", item.incidentId)
                    put("timestamp", item.timestamp)
                    put("error_file", item.errorFile)
                    put("error_line", item.errorLine)
                    put("error_text", item.errorText)
                    put("root_cause", item.rootCause)
                    put("repair_code", item.repairCode)
                    put("diff_text", item.diffText)
                    put("patch_type", item.patchType.name)
                    put("status", item.status.name)
                }
                array.put(obj)
            }
            historyFile.writeText(array.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun summary(): String {
        val items = loadAll()
        if (items.isEmpty()) return "No debugging incidents recorded yet.\n\nRun the demo or execute a command through DevDeck CLI on your laptop to capture and record incidents."
        return buildString {
            append("TOTAL INCIDENTS RECORDED: ${items.size}\n\n")
            for (item in items) {
                val statusBadge = when (item.status) {
                    IncidentStatus.SOLVED -> "✅ [SOLVED]"
                    IncidentStatus.REPAIR_SENT -> "🚀 [PATCH SENT]"
                    IncidentStatus.FAILED -> "❌ [FAILED]"
                    IncidentStatus.SUPERSEDED -> "↺ [SUPERSEDED]"
                    IncidentStatus.DIAGNOSED -> "🔍 [DIAGNOSED]"
                    IncidentStatus.DETECTED -> "⚠️ [DETECTED]"
                }
                val timeStr = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.timestamp))
                append("$statusBadge ${item.errorFile}:${item.errorLine}\n")
                append("• Cause: ${item.rootCause}\n")
                if (item.repairCode != null) {
                    append("• Fix: ${item.repairCode}\n")
                }
                append("• Time: $timeStr\n\n")
                append("─────────────────────────\n\n")
            }
        }
    }

    fun clear() {
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }
}

