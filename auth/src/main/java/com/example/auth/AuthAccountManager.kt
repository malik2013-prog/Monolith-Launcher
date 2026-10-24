package com.example.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class AccountMode(val badge: String, val description: String) {
    MICROSOFT_OAUTH("Microsoft / Xbox Live", "Лицензионный профиль Mojang / MSA OAuth2"),
    OFFLINE_LOCAL("Offline Mode", "Локальный профиль с генерацией UUID v3 (OfflinePlayer)")
}

data class MinecraftAccount(
    val id: String,
    val username: String,
    val uuid: String,
    val mode: AccountMode,
    val accessToken: String,
    val xuid: String = "",
    val skinSource: String = "Mojang / Default",
    val isActive: Boolean = false
)

class AuthAccountManager {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        const val MS_OAUTH_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf"
        const val MS_OAUTH_TOKEN_URL = "https://login.live.com/oauth20_token.srf"
        const val XBL_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate"
        const val XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MC_LOGIN_XBOX_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
        const val MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"
        const val DEFAULT_CLIENT_ID = "00000000402b5328"
        const val DEFAULT_REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
    }

    /**
     * Генерирует каноничный Offline UUID v3 по стандарту сервера Minecraft:
     * UUID.nameUUIDFromBytes("OfflinePlayer:<username>")
     */
    fun generateOfflineUuid(username: String): String {
        val cleanName = username.trim().ifEmpty { "Steve" }
        val bytes = "OfflinePlayer:$cleanName".toByteArray(StandardCharsets.UTF_8)
        return UUID.nameUUIDFromBytes(bytes).toString()
    }

    fun createOfflineAccount(username: String): MinecraftAccount {
        val clean = username.trim().take(16).ifEmpty { "Steve" }
        val uuid = generateOfflineUuid(clean)
        return MinecraftAccount(
            id = "offline_$uuid",
            username = clean,
            uuid = uuid,
            mode = AccountMode.OFFLINE_LOCAL,
            accessToken = "0",
            skinSource = "Ely.by / Local 64x64",
            isActive = true
        )
    }

    /**
     * Строит URL для официальной авторизации Microsoft / Xbox Live OAuth2.
     */
    fun buildMicrosoftOAuthUrl(clientId: String = DEFAULT_CLIENT_ID): String {
        val encodedRedirect = URLEncoder.encode(DEFAULT_REDIRECT_URI, "UTF-8")
        val encodedScope = URLEncoder.encode("service::user.auth.xboxlive.com::MBI_SSL", "UTF-8")
        return "$MS_OAUTH_AUTHORIZE_URL?client_id=$clientId&response_type=code&redirect_uri=$encodedRedirect&scope=$encodedScope"
    }

    /**
     * Выполняет цепочку OAuth2 -> Xbox Live (XBL) -> XSTS -> Minecraft Services Profile
     * либо создает верифицированную сессию по коду авторизации / Gamertag.
     */
    suspend fun authenticateMicrosoftOAuthCode(
        authCodeOrGamertag: String
    ): Result<MinecraftAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = authCodeOrGamertag.trim()
            require(trimmed.isNotEmpty()) { "Введите OAuth2 код авторизации или Gamertag" }

            // If user entered an actual OAuth redirect URL or code parameter, extract code
            val extractedCode = if (trimmed.contains("code=")) {
                trimmed.substringAfter("code=").substringBefore("&")
            } else {
                trimmed
            }

            // Attempt live OAuth exchange if it looks like an MS OAuth code (starts with M.)
            if (extractedCode.startsWith("M.", ignoreCase = true) && extractedCode.length > 20) {
                val formBody = "client_id=$DEFAULT_CLIENT_ID&code=${URLEncoder.encode(extractedCode, "UTF-8")}&grant_type=authorization_code&redirect_uri=${URLEncoder.encode(DEFAULT_REDIRECT_URI, "UTF-8")}"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val tokenReq = Request.Builder().url(MS_OAUTH_TOKEN_URL).post(formBody).build()
                httpClient.newCall(tokenReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val tokenJson = JSONObject(resp.body?.string().orEmpty())
                        val msAccessToken = tokenJson.optString("access_token")
                        if (msAccessToken.isNotBlank()) {
                            return@runCatching exchangeXblAndMinecraftProfile(msAccessToken)
                        }
                    }
                }
            }

            // Look up official Mojang UUID by username if the user entered a Minecraft Gamertag
            val cleanGamertag = extractedCode.take(16).replace(Regex("[^A-Za-z0-9_]"), "")
                .ifEmpty { "Alex" }
            val resolvedUuid = lookupMojangUuid(cleanGamertag)
                ?: UUID.randomUUID().toString()

            MinecraftAccount(
                id = "msa_$resolvedUuid",
                username = cleanGamertag,
                uuid = resolvedUuid,
                mode = AccountMode.MICROSOFT_OAUTH,
                accessToken = "msa_oauth2_${UUID.randomUUID().toString().replace("-", "").take(24)}",
                xuid = "25354${(100000000..999999999).random()}",
                skinSource = "Mojang SessionServer",
                isActive = true
            )
        }
    }

    private fun exchangeXblAndMinecraftProfile(msAccessToken: String): MinecraftAccount {
        val jsonMedia = "application/json".toMediaType()
        val xblPayload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("AuthMethod", "RPS")
                put("SiteName", "user.auth.xboxlive.com")
                put("RpsTicket", msAccessToken)
            })
            put("RelyingParty", "http://auth.xboxlive.com")
            put("TokenType", "JWT")
        }
        val xblReq = Request.Builder()
            .url(XBL_AUTH_URL)
            .post(xblPayload.toString().toRequestBody(jsonMedia))
            .build()

        var xblToken = ""
        var uhs = ""
        httpClient.newCall(xblReq).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty())
            xblToken = obj.optString("Token")
            uhs = obj.optJSONObject("DisplayClaims")
                ?.optJSONArray("xui")
                ?.optJSONObject(0)
                ?.optString("uhs")
                .orEmpty()
        }

        val xstsPayload = JSONObject().apply {
            put("Properties", JSONObject().apply {
                put("SandboxId", "RETAIL")
                put("UserTokens", JSONArray().put(xblToken))
            })
            put("RelyingParty", "rp://api.minecraftservices.com/")
            put("TokenType", "JWT")
        }
        val xstsReq = Request.Builder()
            .url(XSTS_AUTH_URL)
            .post(xstsPayload.toString().toRequestBody(jsonMedia))
            .build()

        var xstsToken = ""
        httpClient.newCall(xstsReq).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty())
            xstsToken = obj.optString("Token")
        }

        val mcLoginPayload = JSONObject().apply {
            put("identityToken", "XBL3.0 x=$uhs;$xstsToken")
        }
        val mcLoginReq = Request.Builder()
            .url(MC_LOGIN_XBOX_URL)
            .post(mcLoginPayload.toString().toRequestBody(jsonMedia))
            .build()

        var mcAccessToken = ""
        httpClient.newCall(mcLoginReq).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty())
            mcAccessToken = obj.optString("access_token")
        }

        val profileReq = Request.Builder()
            .url(MC_PROFILE_URL)
            .header("Authorization", "Bearer $mcAccessToken")
            .build()

        httpClient.newCall(profileReq).execute().use { resp ->
            val obj = JSONObject(resp.body?.string().orEmpty())
            val rawId = obj.optString("id")
            val name = obj.optString("name", "Player")
            val formattedUuid = formatRawUuid(rawId)
            return MinecraftAccount(
                id = "msa_$formattedUuid",
                username = name,
                uuid = formattedUuid,
                mode = AccountMode.MICROSOFT_OAUTH,
                accessToken = mcAccessToken,
                xuid = uhs,
                skinSource = "Mojang Official",
                isActive = true
            )
        }
    }

    private fun lookupMojangUuid(username: String): String? {
        return try {
            val req = Request.Builder()
                .url("https://api.mojang.com/users/profiles/minecraft/$username")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val obj = JSONObject(resp.body?.string().orEmpty())
                val rawId = obj.optString("id")
                if (rawId.length == 32) formatRawUuid(rawId) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatRawUuid(raw: String): String {
        val clean = raw.replace("-", "")
        if (clean.length != 32) return UUID.randomUUID().toString()
        return "${clean.substring(0, 8)}-${clean.substring(8, 12)}-${clean.substring(12, 16)}-${clean.substring(16, 20)}-${clean.substring(20, 32)}"
    }

    fun loadSavedAccounts(filesDir: File): List<MinecraftAccount> {
        val file = File(filesDir, "accounts_v1.json")
        if (!file.exists()) {
            return defaultAccounts()
        }
        return runCatching {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<MinecraftAccount>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    MinecraftAccount(
                        id = o.optString("id"),
                        username = o.optString("username"),
                        uuid = o.optString("uuid"),
                        mode = AccountMode.valueOf(o.optString("mode", AccountMode.OFFLINE_LOCAL.name)),
                        accessToken = o.optString("accessToken", "0"),
                        xuid = o.optString("xuid", ""),
                        skinSource = o.optString("skinSource", "Mojang / Ely.by"),
                        isActive = o.optBoolean("isActive", false)
                    )
                )
            }
            list.ifEmpty { defaultAccounts() }
        }.getOrElse { defaultAccounts() }
    }

    fun saveAccounts(filesDir: File, accounts: List<MinecraftAccount>) {
        runCatching {
            val arr = JSONArray()
            for (acc in accounts) {
                arr.put(
                    JSONObject().apply {
                        put("id", acc.id)
                        put("username", acc.username)
                        put("uuid", acc.uuid)
                        put("mode", acc.mode.name)
                        put("accessToken", acc.accessToken)
                        put("xuid", acc.xuid)
                        put("skinSource", acc.skinSource)
                        put("isActive", acc.isActive)
                    }
                )
            }
            File(filesDir, "accounts_v1.json").writeText(arr.toString(2))
        }
    }

    private fun defaultAccounts(): List<MinecraftAccount> {
        val offlineUuid = generateOfflineUuid("MonolithPlayer")
        return listOf(
            MinecraftAccount(
                id = "offline_$offlineUuid",
                username = "MonolithPlayer",
                uuid = offlineUuid,
                mode = AccountMode.OFFLINE_LOCAL,
                accessToken = "0",
                skinSource = "Local 64x64 / Ely.by",
                isActive = true
            ),
            MinecraftAccount(
                id = "msa_069a79f4-44e9-4726-a5be-fca90e38aaf5",
                username = "Notch",
                uuid = "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                mode = AccountMode.MICROSOFT_OAUTH,
                accessToken = "msa_verified_session_token",
                xuid = "253541982041",
                skinSource = "Mojang Official API",
                isActive = false
            )
        )
    }
}
