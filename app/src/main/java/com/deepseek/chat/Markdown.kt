package com.deepseek.chat

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

object Markdown {

    private val FENCE = Regex("```(\\w*)\\n?([\\s\\S]*?)```")
    private val INLINE = Regex("(`[^`\\n]+`)|(\\*\\*[^*\\n]+\\*\\*)|(\\*[^*\\n]+\\*)|(_[^_\\n]+_)")

    private const val CODE_BG = 0xFF0D0D0D.toInt()

    fun render(src: String): CharSequence {
        val out = SpannableStringBuilder()
        var last = 0
        for (m in FENCE.findAll(src)) {
            appendRich(out, src.substring(last, m.range.first))
            appendCode(out, m.groupValues[2].removeSuffix("\n"))
            last = m.range.last + 1
        }
        appendRich(out, src.substring(last))
        return out
    }

    fun codeBlocks(src: String): String =
        FENCE.findAll(src).joinToString("\n\n") { it.groupValues[2] }.trim()

    private fun appendRich(out: SpannableStringBuilder, text: String) {
        for (line in text.split("\n")) {
            val heading = Regex("^#{1,4}\\s+(.*)").find(line)
            var body = line
            if (heading != null) body = heading.groupValues[1]
            renderInline(out, body)
            if (heading != null && body.isNotEmpty()) {
                val s = out.length - body.length
                out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), s, out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(RelativeSizeSpan(1.15f), s, out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append("\n")
            }
            out.append("\n")
        }
        if (out.isNotEmpty()) out.delete(out.length - 1, out.length)
    }

    private fun renderInline(out: SpannableStringBuilder, text: String) {
        var last = 0
        for (m in INLINE.findAll(text)) {
            out.append(text.substring(last, m.range.first))
            val start = out.length
            when {
                m.groupValues[1] != null -> {
                    out.append(m.groupValues[1].trim('`'))
                    out.setSpan(TypefaceSpan("monospace"), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groupValues[2] != null -> {
                    out.append(m.groupValues[2].removePrefix("**").removeSuffix("**"))
                    out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groupValues[3] != null -> {
                    out.append(m.groupValues[3].trim('*'))
                    out.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                m.groupValues[4] != null -> {
                    out.append(m.groupValues[4].trim('_'))
                    out.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            last = m.range.last + 1
        }
        out.append(text.substring(last))
    }

    private fun appendCode(out: SpannableStringBuilder, code: String) {
        if (out.isNotEmpty()) out.append("\n")
        val start = out.length
        out.append(code)
        out.setSpan(TypefaceSpan("monospace"), start, out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(BackgroundColorSpan(CODE_BG), start, out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append("\n")
    }
}
