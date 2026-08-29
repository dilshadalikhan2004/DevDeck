package com.devdeck.app.model

import org.json.JSONObject

data class ContextReceiptItem(
    val file: String,
    val lineStart: Int,
    val lineEnd: Int,
    val symbols: List<String>,
    val estimatedTokens: Int,
    val reasons: List<String>
) {
    companion object {
        fun fromJson(json: JSONObject): ContextReceiptItem {
            val symbolsArray = json.optJSONArray("symbols")
            val symbols = mutableListOf<String>()
            if (symbolsArray != null) {
                for (i in 0 until symbolsArray.length()) {
                    symbols.add(symbolsArray.getString(i))
                }
            }

            val reasonsArray = json.optJSONArray("reasons")
            val reasons = mutableListOf<String>()
            if (reasonsArray != null) {
                for (i in 0 until reasonsArray.length()) {
                    reasons.add(reasonsArray.getString(i))
                }
            }

            return ContextReceiptItem(
                file = json.optString("file", "unknown"),
                lineStart = json.optInt("line_start", 0),
                lineEnd = json.optInt("line_end", 0),
                symbols = symbols,
                estimatedTokens = json.optInt("estimated_tokens", 0),
                reasons = reasons
            )
        }
    }
}

data class ContextReceipt(
    val items: List<ContextReceiptItem>,
    val totalFiles: Int,
    val totalSymbols: Int,
    val totalTokens: Int
) {
    companion object {
        fun fromJson(json: JSONObject): ContextReceipt {
            val itemsArray = json.optJSONArray("items")
            val items = mutableListOf<ContextReceiptItem>()
            if (itemsArray != null) {
                for (i in 0 until itemsArray.length()) {
                    items.add(ContextReceiptItem.fromJson(itemsArray.getJSONObject(i)))
                }
            }

            return ContextReceipt(
                items = items,
                totalFiles = json.optInt("total_files", 0),
                totalSymbols = json.optInt("total_symbols", 0),
                totalTokens = json.optInt("total_tokens", 0)
            )
        }
    }
}
