package com.deepseek.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

object SecurePrefs {

    fun get(ctx: Context): SharedPreferences {
        migrate(ctx)
        return try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                ctx,
                "dsprefs_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            ctx.getSharedPreferences("dsprefs", Context.MODE_PRIVATE)
        }
    }

    private fun migrate(ctx: Context) {
        try {
            val plain = ctx.getSharedPreferences("dsprefs", Context.MODE_PRIVATE)
            val key = plain.getString("api_key", null)
            if (key.isNullOrBlank()) return
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val secure = EncryptedSharedPreferences.create(
                ctx,
                "dsprefs_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
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
