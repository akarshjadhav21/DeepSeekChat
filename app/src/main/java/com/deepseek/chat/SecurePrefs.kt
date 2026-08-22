package com.deepseek.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object SecurePrefs {

    fun get(ctx: Context): SharedPreferences {
        migrate(ctx)
        return try {
            create(ctx)
        } catch (_: Exception) {
            // Corrupt keystore/pref file (happens after updates or restores).
            // Wipe the unreadable crypto state and retry once before falling back.
            heal(ctx)
            try {
                create(ctx)
            } catch (_: Exception) {
                ctx.getSharedPreferences("dsprefs", Context.MODE_PRIVATE)
            }
        }
    }

    private fun create(ctx: Context): SharedPreferences =
        EncryptedSharedPreferences.create(
            "dsprefs_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    private fun heal(ctx: Context) {
        try {
            ctx.deleteSharedPreferences("dsprefs_secure")
        } catch (_: Exception) {
        }
        try {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            ks.deleteEntry("_androidx_security_master_key_")
        } catch (_: Exception) {
        }
    }

    private fun migrate(ctx: Context) {
        try {
            val plain = ctx.getSharedPreferences("dsprefs", Context.MODE_PRIVATE)
            val key = plain.getString("api_key", null)
            if (key.isNullOrBlank()) return
            val secure = create(ctx)
            if (secure.getString("api_key", "").isNullOrBlank()) {
                secure.edit()
                    .putString("api_key", key)
                    .putString("model", plain.getString("model", null))
                    .putString("effort", plain.getString("effort", null))
                    .apply()
            }
            plain.edit().remove("api_key").apply()
        } catch (_: Exception) {
        }
    }
}
