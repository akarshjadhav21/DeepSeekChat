package com.deepseek.chat

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class SettingsActivity : Activity() {

    private val prefs by lazy { SecurePrefs.get(this) }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var keyField: EditText
    private lateinit var baseUrlField: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

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

        // ---------- outer frame: scroll area + pinned Save ----------
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(12))
        }
        scroll.addView(form)
        root.addView(scroll)

        form.addView(TextView(this).apply {
            text = "Settings"
            textSize = 22f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
        })

        form.addView(label("\nNVIDIA API Key").also {
            it.setPadding(0, dp(24), 0, dp(6))
        })
        keyField = field(prefs.getString("api_key", ""),
            "nvapi-…  from build.nvidia.com", password = true)
        keyField.setTextSize(13f)
        form.addView(keyField)

        form.addView(label("Model").also { it.setPadding(0, dp(24), 0, dp(6)) })
        val modelField = field(prefs.getString("model", NviClient.DEFAULT_MODEL),
            "deepseek-ai/deepseek-v4-flash-0731", password = false)
        modelField.setTextSize(13f)
        form.addView(modelField)

        val pickBtn = Button(this).apply {
            text = "📋  Show model list"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#37474F"))
                cornerRadius = 32f
            }
        }
        pickBtn.setOnClickListener { loadModels(pickBtn, modelField) }
        form.addView(pickBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(10), 0, 0) })

        form.addView(label("Server URL (relay if NVIDIA is blocked)").also {
            it.setPadding(0, dp(24), 0, dp(6))
        })
        baseUrlField = field(prefs.getString("base_url", NviClient.DEFAULT_BASE),
            NviClient.DEFAULT_BASE, password = false)
        baseUrlField.setTextSize(13f)
        form.addView(baseUrlField)

        form.addView(label("Thinking effort (how long it thinks)").also {
            it.setPadding(0, dp(24), 0, dp(6))
        })
        val effortField = field(prefs.getString("effort", "high"),
            "high / medium / low", password = false)
        effortField.setTextSize(13f)
        form.addView(effortField)

        form.addView(TextView(this).apply {
            text = "— Builder 🔨 —"
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#FFD54F"))
            setPadding(0, dp(32), 0, 0)
        })

        form.addView(label("GitHub Token (repo + workflow scopes)").also {
            it.setPadding(0, dp(12), 0, dp(6))
        })
        val ghTokenField = field(prefs.getString("gh_token", ""),
            "ghp_…  classic PAT with repo+workflow", password = true)
        ghTokenField.setTextSize(13f)
        form.addView(ghTokenField)

        form.addView(label("Target Repos (comma-separated)").also {
            it.setPadding(0, dp(16), 0, dp(6))
        })
        val ghRepoField = field(prefs.getString("gh_repo", ""),
            "username/RepoA, username/RepoB", password = false)
        ghRepoField.setTextSize(13f)
        form.addView(ghRepoField)

        form.addView(TextView(this).apply {
            text = "Free keys: build.nvidia.com → sign in → Get API Key.\n" +
                   "Thinking mode is always ON with high reasoning effort."
            textSize = 12f
            setTextColor(Color.parseColor("#757575"))
            setPadding(0, dp(24), 0, 0)
        })

        // ---------- status + pinned Save button ----------
        statusText = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(8), dp(20), dp(4))
            visibility = android.view.View.GONE
        }
        root.addView(statusText)

        val saveBtn = Button(this).apply {
            text = "💾  SAVE SETTINGS"
            textSize = 17f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = 32f
            }
        }
        root.addView(saveBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(20), dp(10), dp(20), dp(20)) })

        saveBtn.setOnClickListener {
            saveAll(saveBtn,
                key = keyField.text.toString().trim(),
                model = modelField.text.toString().trim(),
                base = baseUrlField.text.toString().trim(),
                effortRaw = effortField.text.toString().trim().lowercase(),
                ghToken = ghTokenField.text.toString().trim(),
                ghRepo = ghRepoField.text.toString().trim())
        }

        setContentView(root)
    }

    private fun showStatus(msg: String, isError: Boolean) {
        statusText.visibility = android.view.View.VISIBLE
        statusText.text = msg
        statusText.setTextColor(
            if (isError) Color.parseColor("#EF5350") else Color.parseColor("#66BB6A"))
    }

    private fun saveAll(saveBtn: Button, key: String, model: String, base: String,
                        effortRaw: String, ghToken: String, ghRepo: String) {
        val effortVal = if (effortRaw in listOf("high", "medium", "low")) effortRaw else "high"
        saveBtn.isEnabled = false
        showStatus("Saving…", isError = false)
        Thread {
            val result = try {
                val ok = prefs.edit()
                    .putString("api_key", key)
                    .putString("model", model.ifBlank { NviClient.DEFAULT_MODEL })
                    .putString("base_url", base.ifBlank { NviClient.DEFAULT_BASE })
                    .putString("effort", effortVal)
                    .putString("gh_token", ghToken)
                    .putString("gh_repo", ghRepo)
                    .commit()
                if (ok && prefs.getString("api_key", "") == key) null
                else IOException("storage did not keep the value")
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                saveBtn.isEnabled = true
                if (result == null) {
                    val masked = if (key.length > 12)
                        key.take(9) + "…" + key.takeLast(4) + " saved"
                    else "saved"
                    showStatus("✓ Key $masked", isError = false)
                    Toast.makeText(this, "Saved ✓", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    showStatus("✗ Save failed: ${result.message}", isError = true)
                    Toast.makeText(this,
                        "Save failed: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun loadModels(pickBtn: Button, modelField: EditText) {
        val base = baseUrlField.text.toString().trim().trimEnd('/')
            .ifBlank { NviClient.DEFAULT_BASE }
        val key = keyField.text.toString().trim()
        if (key.isBlank()) {
            Toast.makeText(this, "Enter your API key first", Toast.LENGTH_SHORT).show()
            return
        }
        pickBtn.isEnabled = false
        pickBtn.text = "⏳  Loading models…"
        Thread {
            val result = try {
                val req = Request.Builder()
                    .url("$base/v1/models")
                    .header("Authorization", "Bearer $key")
                    .build()
                http.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val arr = JSONObject(bodyStr).getJSONArray("data")
                    val ids = ArrayList<String>()
                    for (i in 0 until arr.length()) {
                        val id = arr.getJSONObject(i).optString("id", "")
                        if (id.isNotBlank()) ids.add(id)
                    }
                    if (ids.isEmpty()) throw IOException("empty list")
                    ids.sortedWith(compareBy({ !it.contains("deepseek") }, { it }))
                }
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                pickBtn.isEnabled = true
                pickBtn.text = "📋  Show model list"
                when (result) {
                    is List<*> -> {
                        val models = result.filterIsInstance<String>()
                        val current = modelField.text.toString()
                        val checked = models.indexOf(current).coerceAtLeast(0)
                        AlertDialog.Builder(this)
                            .setTitle("Select model (${models.size}) — DeepSeek first")
                            .setSingleChoiceItems(models.toTypedArray(), checked) { d, which ->
                                modelField.setText(models[which])
                                d.dismiss()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    else -> Toast.makeText(this,
                        "Couldn't load models: ${(result as Exception).message}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
