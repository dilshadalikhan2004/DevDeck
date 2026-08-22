package com.devdeck.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

data class HistoryEntry(val cause: String, val location: String, val timestamp: Long)

class DiagnosticHistory(context: Context) {
    private val preferences = context.getSharedPreferences("devdeck", Context.MODE_PRIVATE)

    fun add(result: DiagnosticResult) {
        val entries = JSONArray(preferences.getString("history", "[]"))
        entries.put(0, JSONObject().apply {
            put("cause", result.rootCause)
            put("location", result.location)
            put("timestamp", System.currentTimeMillis())
        })
        while (entries.length() > 6) entries.remove(entries.length() - 1)
        preferences.edit().putString("history", entries.toString()).apply()
    }

    fun summary(): String {
        val entries = JSONArray(preferences.getString("history", "[]"))
        if (entries.length() == 0) return "No diagnoses yet. Run the demo or send a failed command from your laptop."
        return buildString {
            append("RECENT LOCAL DIAGNOSES\n\n")
            for (i in 0 until entries.length()) {
                val item = entries.getJSONObject(i)
                append("• ").append(item.getString("cause")).append("\n")
                append("  ").append(item.getString("location")).append(" · ")
                    .append(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.getLong("timestamp")))).append("\n\n")
            }
        }
    }
}
