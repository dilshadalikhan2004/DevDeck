package com.devdeck.app.model

import android.content.Context

class ProjectContextManager(context: Context) {
    private val prefs = context.getSharedPreferences("devdeck_context", Context.MODE_PRIVATE)

    fun addRule(rule: String) {
        val rules = getRules().toMutableSet()
        rules.add(rule)
        prefs.edit().putStringSet("rules", rules).apply()
    }

    fun getRules(): Set<String> {
        return prefs.getStringSet("rules", emptySet()) ?: emptySet()
    }

    fun getFormattedContext(): String {
        val rules = getRules()
        if (rules.isEmpty()) return ""
        return "\nPROJECT RULES & CONTEXT:\n" + rules.joinToString("\n") { "• $it" } + "\n"
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}