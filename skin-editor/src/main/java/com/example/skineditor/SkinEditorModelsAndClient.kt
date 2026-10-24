package com.example.skineditor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class SkinFormat(val width: Int, val height: Int, val label: String) {
    MODERN_64X64(64, 64, "64×64 (Modern + Overlay)"),
    LEGACY_64X32(64, 32, "64×32 (Legacy Classic/Alpha/Beta)")
}

enum class SkinTool(val label: String) {
    PENCIL("Карандаш"),
    EYEDROPPER("Пипетка"),
    FILL("Заливка"),
    ERASER("Ластик")
}

enum class SkinProviderSource(val label: String) {
    MOJANG_OFFICIAL("Mojang Official API"),
    ELY_BY("Ely.by SkinSystem API")
}

data class SkinFetchResult(
    val username: String,
    val provider: SkinProviderSource,
    val skinBitmapPixels: IntArray,
    val format: SkinFormat,
    val hasCape: Boolean,
    val capeUrl: String? = null,
    val statusMessage: String
)

class MojangAndElySkinClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Загружает скин и плащ игрока через официальный Mojang SessionServer или публичный API Ely.by.
     */
    suspend fun fetchPlayerSkin(
        username: String,
        provider: SkinProviderSource
    ): SkinFetchResult = withContext(Dispatchers.IO) {
        val cleanName = username.trim().ifEmpty { "Steve" }
        try {
            when (provider) {
                SkinProviderSource.ELY_BY -> fetchFromElyBy(cleanName)
                SkinProviderSource.MOJANG_OFFICIAL -> fetchFromMojang(cleanName)
            }
        } catch (e: Exception) {
            // Fallback to deterministic procedural skin if offline or player not found
            val fallbackPixels = ProceduralAiSkinSynthesizer.generateFromPrompt(
                prompt = "Player $cleanName emerald monolith adventurer",
                format = SkinFormat.MODERN_64X64
            )
            SkinFetchResult(
                username = cleanName,
                provider = provider,
                skinBitmapPixels = fallbackPixels,
                format = SkinFormat.MODERN_64X64,
                hasCape = false,
                statusMessage = "Офлайн/Резервный профиль для $cleanName (сгенерирован локальный UV-скин)"
            )
        }
    }

    private fun fetchFromElyBy(username: String): SkinFetchResult {
        val texturesReq = Request.Builder()
            .url("https://skinsystem.ely.by/textures/$username")
            .header("User-Agent", "MonolithVanillaLauncher/1.0")
            .build()

        httpClient.newCall(texturesReq).execute().use { resp ->
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string().orEmpty())
                val skinObj = json.optJSONObject("SKIN")
                val capeObj = json.optJSONObject("CAPE")
                val skinUrl = skinObj?.optString("url").orEmpty()
                val capeUrl = capeObj?.optString("url")

                if (skinUrl.isNotBlank()) {
                    val decoded = downloadSkinBitmapPixels(skinUrl)
                    if (decoded != null) {
                        return SkinFetchResult(
                            username = username,
                            provider = SkinProviderSource.ELY_BY,
                            skinBitmapPixels = decoded.first,
                            format = decoded.second,
                            hasCape = !capeUrl.isNullOrBlank(),
                            capeUrl = capeUrl,
                            statusMessage = "Успешно загружен скин Ely.by для $username" +
                                if (!capeUrl.isNullOrBlank()) " (+ Плащ Ely.by)" else ""
                        )
                    }
                }
            }
        }

        // Direct Ely.by skin PNG endpoint fallback
        val directUrl = "https://skinsystem.ely.by/skins/$username.png"
        val directDecoded = downloadSkinBitmapPixels(directUrl)
        if (directDecoded != null) {
            return SkinFetchResult(
                username = username,
                provider = SkinProviderSource.ELY_BY,
                skinBitmapPixels = directDecoded.first,
                format = directDecoded.second,
                hasCape = false,
                statusMessage = "Загружен PNG-скин Ely.by ($username)"
            )
        }

        throw IllegalStateException("Профиль $username не найден в Ely.by")
    }

    private fun fetchFromMojang(username: String): SkinFetchResult {
        val uuidReq = Request.Builder()
            .url("https://api.mojang.com/users/profiles/minecraft/$username")
            .build()

        var rawUuid = ""
        httpClient.newCall(uuidReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Игрок $username не найден в Mojang")
            val obj = JSONObject(resp.body?.string().orEmpty())
            rawUuid = obj.optString("id")
        }

        val profileReq = Request.Builder()
            .url("https://sessionserver.mojang.com/session/minecraft/profile/$rawUuid")
            .build()

        httpClient.newCall(profileReq).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Ошибка чтения профиля SessionServer")
            val obj = JSONObject(resp.body?.string().orEmpty())
            val props = obj.optJSONArray("properties")
            if (props != null && props.length() > 0) {
                val base64Val = props.getJSONObject(0).optString("value")
                val decodedJson = String(Base64.decode(base64Val, Base64.DEFAULT))
                val texObj = JSONObject(decodedJson).optJSONObject("textures")
                val skinUrl = texObj?.optJSONObject("SKIN")?.optString("url").orEmpty()
                val capeUrl = texObj?.optJSONObject("CAPE")?.optString("url")

                if (skinUrl.isNotBlank()) {
                    val decoded = downloadSkinBitmapPixels(skinUrl)
                    if (decoded != null) {
                        return SkinFetchResult(
                            username = username,
                            provider = SkinProviderSource.MOJANG_OFFICIAL,
                            skinBitmapPixels = decoded.first,
                            format = decoded.second,
                            hasCape = !capeUrl.isNullOrBlank(),
                            capeUrl = capeUrl,
                            statusMessage = "Загружен официальный скин Mojang ($username)"
                        )
                    }
                }
            }
        }
        throw IllegalStateException("Текстура скина Mojang недоступна")
    }

    private fun downloadSkinBitmapPixels(url: String): Pair<IntArray, SkinFormat>? {
        val sanitizedUrl = if (url.startsWith("http://")) {
            "https://" + url.removePrefix("http://")
        } else {
            url
        }
        val req = Request.Builder().url(sanitizedUrl).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val fmt = if (bmp.height <= 32) SkinFormat.LEGACY_64X32 else SkinFormat.MODERN_64X64
            val scaled = if (bmp.width != fmt.width || bmp.height != fmt.height) {
                Bitmap.createScaledBitmap(bmp, fmt.width, fmt.height, false)
            } else {
                bmp
            }
            val pixels = IntArray(fmt.width * fmt.height)
            scaled.getPixels(pixels, 0, fmt.width, 0, 0, fmt.width, fmt.height)
            return pixels to fmt
        }
    }

    fun exportSkinToPng(
        filesDir: File,
        fileName: String,
        pixels: IntArray,
        format: SkinFormat
    ): File {
        val skinDir = File(filesDir, "skins").apply { mkdirs() }
        val targetFile = File(skinDir, if (fileName.endsWith(".png")) fileName else "$fileName.png")
        val bmp = Bitmap.createBitmap(format.width, format.height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, format.width, 0, 0, format.width, format.height)
        FileOutputStream(targetFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return targetFile
    }
}

/**
 * Встроенная утилита генерации текстуры скина 64x64 / 64x32 по текстовому описанию игрока
 * и цветовым параметрам (AI Skin Generator).
 */
object ProceduralAiSkinSynthesizer {

    fun generateFromPrompt(
        prompt: String,
        format: SkinFormat = SkinFormat.MODERN_64X64,
        overridePrimaryHex: Int? = null,
        overrideAccentHex: Int? = null
    ): IntArray {
        val lower = prompt.lowercase()
        val seed = abs(prompt.hashCode())

        val skinTone = when {
            lower.contains("зомби") || lower.contains("zombie") || lower.contains("орк") -> 0xFF5D9966.toInt()
            lower.contains("эндер") || lower.contains("ender") || lower.contains("тень") -> 0xFF22252A.toInt()
            lower.contains("робот") || lower.contains("кибер") || lower.contains("cyber") -> 0xFF9BA8B5.toInt()
            else -> listOf(
                0xFFE6B89C.toInt(),
                0xFFD49E7A.toInt(),
                0xFFF1C6A7.toInt(),
                0xFFB57C58.toInt()
            )[seed % 4]
        }

        val primaryColor = overridePrimaryHex ?: when {
            lower.contains("изумруд") || lower.contains("emerald") || lower.contains("лес") -> 0xFF28A755.toInt()
            lower.contains("редстоун") || lower.contains("redstone") || lower.contains("огонь") || lower.contains("красн") -> 0xFFC93B3B.toInt()
            lower.contains("алмаз") || lower.contains("diamond") || lower.contains("лед") || lower.contains("кибер") -> 0xFF2EB8D1.toInt()
            lower.contains("золот") || lower.contains("gold") || lower.contains("корол") -> 0xFFE0B136.toInt()
            lower.contains("обсидиан") || lower.contains("незерит") || lower.contains("рыцар") -> 0xFF2B2D38.toInt()
            else -> listOf(
                0xFF2E5B88.toInt(),
                0xFF2E8B57.toInt(),
                0xFF6B3FA0.toInt(),
                0xFF3A4752.toInt()
            )[(seed / 3) % 4]
        }

        val accentColor = overrideAccentHex ?: when {
            lower.contains("визор") || lower.contains("неон") || lower.contains("изумруд") -> 0xFF38C76A.toInt()
            lower.contains("золот") || lower.contains("маг") -> 0xFFFFD84D.toInt()
            lower.contains("лава") || lower.contains("редстоун") -> 0xFFFF5E36.toInt()
            else -> 0xFF5CE08A.toInt()
        }

        val hairOrHelmetColor = when {
            lower.contains("рыцар") || lower.contains("шлем") || lower.contains("брон") -> 0xFF1E242B.toInt()
            lower.contains("блонд") || lower.contains("золот") -> 0xFFD9B24C.toInt()
            lower.contains("сед") || lower.contains("снег") -> 0xFFD8E0E8.toInt()
            else -> 0xFF2C221E.toInt()
        }

        val pantsColor = 0xFF242C35.toInt()
        val bootsColor = 0xFF161B20.toInt()

        val pixels = IntArray(format.width * format.height) { 0x00000000 }

        fun setRect(x0: Int, y0: Int, w: Int, h: Int, color: Int) {
            for (y in y0 until (y0 + h).coerceAtMost(format.height)) {
                for (x in x0 until (x0 + w).coerceAtMost(format.width)) {
                    val noise = if (((x + y + seed) % 5) == 0) -0x00080808 else 0
                    pixels[y * format.width + x] = (color + noise) or (0xFF shl 24)
                }
            }
        }

        // 1. Base Head (UV: x=0..31, y=0..15)
        setRect(8, 0, 8, 8, hairOrHelmetColor) // Top
        setRect(16, 0, 8, 8, skinTone) // Bottom
        setRect(0, 8, 8, 8, skinTone) // Right
        setRect(8, 8, 8, 8, skinTone) // Front Face
        setRect(16, 8, 8, 8, skinTone) // Left
        setRect(24, 8, 8, 8, hairOrHelmetColor) // Back

        // Hairline + Eyes + Mouth on Front Face (8..15, 8..15)
        setRect(8, 8, 8, 2, hairOrHelmetColor)
        // Eyes
        setRect(9, 12, 2, 1, 0xFFFFFFFF.toInt())
        setRect(13, 12, 2, 1, 0xFFFFFFFF.toInt())
        setRect(10, 12, 1, 1, accentColor)
        setRect(13, 12, 1, 1, accentColor)
        // Visor if cyber/knight requested
        if (lower.contains("визор") || lower.contains("кибер") || lower.contains("рыцар")) {
            setRect(9, 11, 6, 2, accentColor)
        }

        // 2. Hat / Outer Head Overlay (UV: x=32..63, y=0..15)
        setRect(40, 0, 8, 8, hairOrHelmetColor)
        setRect(40, 8, 8, 3, hairOrHelmetColor)
        setRect(32, 8, 8, 4, hairOrHelmetColor)
        setRect(48, 8, 8, 4, hairOrHelmetColor)
        setRect(56, 8, 8, 6, hairOrHelmetColor)

        // 3. Torso (UV: x=16..39, y=16..31)
        setRect(20, 16, 8, 4, primaryColor)
        setRect(16, 20, 24, 12, primaryColor)
        // Chest emblem / core
        setRect(22, 22, 4, 4, accentColor)
        setRect(20, 30, 8, 2, 0xFF1A2026.toInt()) // Belt

        // 4. Right Leg (UV: x=0..15, y=16..31)
        setRect(4, 16, 4, 4, pantsColor)
        setRect(0, 20, 16, 9, pantsColor)
        setRect(0, 29, 16, 3, bootsColor)

        // 5. Right Arm (UV: x=40..55, y=16..31)
        setRect(44, 16, 4, 4, primaryColor)
        setRect(40, 20, 16, 9, primaryColor)
        setRect(40, 29, 16, 3, skinTone)

        if (format == SkinFormat.MODERN_64X64) {
            // 6. Left Leg (UV: x=16..31, y=48..63)
            setRect(20, 48, 4, 4, pantsColor)
            setRect(16, 52, 16, 9, pantsColor)
            setRect(16, 61, 16, 3, bootsColor)

            // 7. Left Arm (UV: x=32..47, y=48..63)
            setRect(36, 48, 4, 4, primaryColor)
            setRect(32, 52, 16, 9, primaryColor)
            setRect(32, 61, 16, 3, skinTone)

            // 8. Jacket Overlay (UV: x=16..39, y=32..47)
            setRect(20, 36, 2, 10, accentColor)
            setRect(26, 36, 2, 10, accentColor)
        }

        return pixels
    }
}
