package com.deepseek.chat

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("dsprefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#90CAF9"))
            setTypeface(Typeface.DEFAULT_BOLD)
        }

        fun field(existing: String?, hint: String, password: Boolean) = EditText(this).apply {
            setText(existing ?: "")
            this.hint = hint
            setHintTextColor(Color.parseColor("#616161"))
            setTextColor(Color.WHITE)
            inputType = if (password)
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C1C"))
                cornerRadius = 24f
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        root.addView(TextView(this).apply {
            text = "Settings"
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
        })

        root.addView(label("\nNVIDIA API Key").also {
            it.setPadding(0, dp(24), 0, dp(6))
        })
        val keyField = field(prefs.getString("api_key", ""),
            "nvapi-…  from build.nvidia.com", password = true)
        keyField.setTextSize(13f)
        root.addView(keyField)

        root.addView(label("Model").also { it.setPadding(0, dp(24), 0, dp(6)) })
        val modelField = field(prefs.getString("model", NviClient.DEFAULT_MODEL),
            "deepseek-ai/deepseek-v4-flash-0731", password = false)
        modelField.setTextSize(13f)
        root.addView(modelField)

        root.addView(TextView(this).apply {
            text = "Free keys: build.nvidia.com → sign in → Get API Key.\n" +
                   "Thinking mode is always ON with high reasoning effort."
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(24), 0, 0)
        })

        val saveBtn = Button(this).apply {
            text = "Save"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 32f
            }
            setOnClickListener {
                prefs.edit()
                    .putString("api_key", keyField.text.toString().trim())
                    .putString("model", modelField.text.toString().trim()
                        .ifBlank { NviClient.DEFAULT_MODEL })
                    .apply()
                Toast.makeText(this@SettingsActivity,
                    "Saved ✓", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(saveBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(32), 0, 0) })

        setContentView(root)
    }
}
