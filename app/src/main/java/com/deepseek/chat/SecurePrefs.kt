package com.deepseek.chat

import android.content.Context
import android.content.SharedPreferences

object SecurePrefs {

    // Plain local storage — encrypted prefs (AndroidKeyStore) proved unreliable
    // on some devices: corruption caused silent save failures/data loss.
    // One-time best-effort import of anything still readable in the old store.
    fun get(ctx: Context): SharedPreferences {
        val plain = ctx.getSharedPreferences("dsprefs", Context.MODE_PRIVATE)
        migrateFromLegacy(ctx, plain)
        return plain
    }

    private fun migrateFromLegacy(ctx: Context, plain: SharedPreferences) {
        try {
            if (plain.getBoolean("legacy_imported", false)) return
            val secure = androidx.security.crypto.EncryptedSharedPreferences.create(
                "dsprefs_secure",
                androidx.security.crypto.MasterKeys.getOrCreate(
                    androidx.security.crypto.MasterKeys.AES256_GCM_SPEC),
                ctx,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val keys = listOf("api_key", "model", "effort", "base_url", "gh_token", "gh_repo")
            val edit = plain.edit()
            for (k in keys) {
                val v = try { secure.getString(k, null) } catch (_: Exception) { null }
                if (!v.isNullOrBlank() && plain.getString(k, "").isNullOrBlank()) {
                    edit.putString(k, v)
                }
            }
            edit.putBoolean("legacy_imported", true)
            edit.apply()
        } catch (_: Exception) {
            // Old unreadable store — nothing to rescue, continue plain.
            plain.edit().putBoolean("legacy_imported", true).apply()
        }
    }
}
