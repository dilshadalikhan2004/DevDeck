package com.devdeck.app.ui

import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import com.devdeck.app.R
import java.util.regex.Pattern

object SyntaxHighlighter {

    private val KEYWORDS = Pattern.compile(
        "\\b(def|fun|val|var|class|if|else|return|import|from|while|for|in|try|except|catch|finally|null|None|True|False|bool|int|str|async|await|suspend)\\b"
    )
    private val STRINGS = Pattern.compile("\"\"\"[\\s\\S]*?\"\"\"|\"[^\"]*\"|'[^']*'")
    private val COMMENTS = Pattern.compile("#.*|//.*|/\\*[\\s\\S]*?\\*/")
    private val FUNCTIONS = Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*(?=\\()\\b")

    fun highlight(context: Context, text: String): SpannableString {
        val spannable = SpannableString(text)
        
        // Colors
        val keywordColor = ContextCompat.getColor(context, R.color.syntax_keyword)
        val stringColor = ContextCompat.getColor(context, R.color.syntax_string)
        val commentColor = ContextCompat.getColor(context, R.color.color_code_mist)
        val functionColor = ContextCompat.getColor(context, R.color.syntax_function)

        applyPattern(spannable, KEYWORDS, keywordColor)
        applyPattern(spannable, FUNCTIONS, functionColor)
        applyPattern(spannable, STRINGS, stringColor)
        applyPattern(spannable, COMMENTS, commentColor)

        return spannable
    }

    private fun applyPattern(spannable: Spannable, pattern: Pattern, color: Int) {
        val matcher = pattern.matcher(spannable)
        while (matcher.find()) {
            spannable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }
}