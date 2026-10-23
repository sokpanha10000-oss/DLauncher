package net.dlauncher.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Microsoft device-code login -> Xbox Live -> XSTS -> Minecraft services.
 * Needs your own Azure app (public client, "consumers"/personal accounts) that Mojang has approved
 * for the Minecraft API; otherwise login_with_xbox answers 403.
 */
class MicrosoftAuth(private val clientId: String, private val http: OkHttpClient = OkHttpClient()) {
    data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val interval: Int, val expiresIn: Int)

    private val base = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private val scope = "XboxLive.signin offline_access"

    suspend fun requestDeviceCode(): DeviceCode {
        val o = form("$base/devicecode", "client_id" to clientId, "scope" to scope)
        return DeviceCode(o.str("user_code"), o.str("verification_uri"), o.str("device_code"),
            o["interval"]?.jsonPrimitive?.intOrNull ?: 5, o["expires_in"]?.jsonPrimitive?.intOrNull ?: 900)
    }

    suspend fun pollForLogin(dc: DeviceCode): Account {
        var interval = dc.interval
        val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val o = form("$base/token", "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId, "device_code" to dc.deviceCode)
            o["access_token"]?.let { return xbox(it.jsonPrimitive.content, o.str("refresh_token")) }
            when (o["error"]?.jsonPrimitive?.contentOrNull) {
                "authorization_pending" -> {}
                "slow_down" -> interval += 5
                else -> error(o.str("error_description"))
            }
        }
        error("Login code expired, try again")
    }

    suspend fun ensureFresh(a: Account): Account {
        if (a.type != "msa" || System.currentTimeMillis() < a.expiresAt - 60_000) return a
        val o = form("$base/token", "grant_type" to "refresh_token", "client_id" to clientId,
            "refresh_token" to a.refreshToken, "scope" to scope)
        return xbox(o.str("access_token"), o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: a.refreshToken)
    }

    private suspend fun xbox(msToken: String, refresh: String): Account {
        val xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", buildJsonObject {
            putJsonObject("Properties") { put("AuthMethod", "RPS"); put("SiteName", "user.auth.xboxlive.com"); put("RpsTicket", "d=$msToken") }
            put("RelyingParty", "http://auth.xboxlive.com"); put("TokenType", "JWT")
        })
        val xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", buildJsonObject {
            putJsonObject("Properties") { put("SandboxId", "RETAIL"); putJsonArray("UserTokens") { add(xbl.str("Token")) } }
            put("RelyingParty", "rp://api.minecraftservices.com/"); put("TokenType", "JWT")
        })
        val uhs = xsts["DisplayClaims"]!!.jsonObject["xui"]!!.jsonArray[0].jsonObject.str("uhs")
        val mc = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", buildJsonObject {
            put("identityToken", "XBL3.0 x=$uhs;${xsts.str("Token")}")
        })
        val token = mc.str("access_token")
        val ttl = mc["expires_in"]?.jsonPrimitive?.longOrNull ?: 86400L
        val prof = get("https://api.minecraftservices.com/minecraft/profile", token)
        if (prof["error"] != null) error("This Microsoft account doesn't own Minecraft: Java Edition")
        return Account(prof.str("name"), prof.str("id"), token, refresh, System.currentTimeMillis() + ttl * 1000, "msa")
    }

    private suspend fun call(req: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { Json.parseToJsonElement(it.body!!.string()).jsonObject }
    }
    private suspend fun form(url: String, vararg p: Pair<String, String>) = call(Request.Builder().url(url)
        .post(FormBody.Builder().apply { p.forEach { add(it.first, it.second) } }.build()).build())
    private suspend fun postJson(url: String, body: JsonObject) = call(Request.Builder().url(url)
        .header("Accept", "application/json").post(body.toString().toRequestBody("application/json".toMediaType())).build())
    private suspend fun get(url: String, token: String) = call(Request.Builder().url(url).header("Authorization", "Bearer $token").build())

    private fun JsonObject.str(k: String): String = this[k]?.jsonPrimitive?.contentOrNull
        ?: error("Missing '$k': ${this["error_description"]?.jsonPrimitive?.contentOrNull ?: this}")
}
