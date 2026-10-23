package net.dlauncher.auth

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class Account(
    val name: String,
    val uuid: String,
    val accessToken: String = "0",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val type: String = "offline", // "msa" or "offline"
)

fun offlineUuid(name: String): UUID = UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray())

/** App-private storage. For production, wrap with Android Keystore / EncryptedSharedPreferences. */
class AccountStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("account", Context.MODE_PRIVATE)
    fun load(): Account? = prefs.getString("a", null)?.let { runCatching { Json.decodeFromString<Account>(it) }.getOrNull() }
    fun save(a: Account) = prefs.edit().putString("a", Json.encodeToString(a)).apply()
    fun clear() = prefs.edit().clear().apply()
}
