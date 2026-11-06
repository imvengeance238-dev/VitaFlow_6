package app.vitaflow.com.ai

import android.util.Base64
import app.vitaflow.com.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val role: String, val text: String)

sealed interface OnlineResult {
    data class Ok(val text: String, val ms: Long, val via: String) : OnlineResult
    data class Err(val message: String, val retryable: Boolean) : OnlineResult
}

/**
 * The online AI. Two ways to reach it, and the app code does not care which one is used:
 *  - "backend": your own Max AI backend (URL + access code). The AI provider key lives there, never in the app.
 *  - "direct": Gemini with a key you typed in the settings (kept encrypted on the phone, not inside the APK).
 */
class OnlineBrain(private val secrets: SecretStore, private val settings: SettingsStore) {
    private data class Config(val backendUrl: String?, val accessCode: String?, val geminiKey: String?, val model: String)

    @Volatile private var cached: Config? = null

    /** Call after the settings were changed. */
    fun invalidate() {
        cached = null
    }

    private suspend fun config(): Config {
        cached?.let { return it }
        val c = Config(
            backendUrl = settings.backendUrl.first().trim().trimEnd('/').ifEmpty { null },
            accessCode = secrets.get("backend_code"),
            geminiKey = secrets.get("gemini_key"),
            model = settings.directModel.first().ifBlank { "gemini-2.5-flash" }
        )
        cached = c
        return c
    }

    /** "backend", "direct" or null when nothing is set up. */
    suspend fun mode(): String? {
        val c = config()
        return when {
            c.backendUrl != null && c.accessCode != null -> "backend"
            c.geminiKey != null -> "direct"
            else -> null
        }
    }

    suspend fun configured(): Boolean = mode() != null

    suspend fun chat(
        system: String,
        turns: List<ChatTurn>,
        json: Boolean = false,
        temperature: Double = 0.4,
        maxTokens: Int = 700,
        imagePaths: List<String> = emptyList()
    ): OnlineResult = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        val c = config()
        val images = imagePaths.mapNotNull { p -> runCatching { File(p).readBytes() }.getOrNull() }
            .map { Base64.encodeToString(it, Base64.NO_WRAP) }
        try {
            when (mode()) {
                "backend" -> viaBackend(c, system, turns, json, temperature, maxTokens, images, t0)
                "direct" -> viaGemini(c, system, turns, json, temperature, maxTokens, images, t0)
                else -> OnlineResult.Err("অনলাইন AI সেট করা নেই", false)
            }
        } catch (e: IOException) {
            OnlineResult.Err("ইন্টারনেট সংযোগ নেই", true)
        } catch (e: JSONException) {
            OnlineResult.Err("AI-এর উত্তর পড়া যায়নি", true)
        }
    }

    private fun post(url: String, headers: Map<String, String>, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8_000
        conn.readTimeout = 40_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to text
    }

    private fun failure(code: Int, body: String): OnlineResult.Err {
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message") ?: o.optString("error")
        }.getOrNull().orEmpty().take(160)
        val msg = when (code) {
            401, 403 -> "key বা access code ঠিক নেই"
            404 -> "model বা address পাওয়া যায়নি"
            429 -> "এই মুহূর্তের সীমা শেষ, একটু পরে আবার চেষ্টা করুন"
            in 500..599 -> "AI সার্ভারে সমস্যা, একটু পরে চেষ্টা করুন"
            else -> "AI অনুরোধ নিল না (কোড $code)"
        }
        return OnlineResult.Err(if (detail.isNotEmpty() && code !in listOf(401, 403, 429)) "$msg: $detail" else msg, code == 429 || code >= 500)
    }

    private fun viaBackend(
        c: Config, system: String, turns: List<ChatTurn>, json: Boolean, temperature: Double, maxTokens: Int,
        images: List<String>, t0: Long
    ): OnlineResult {
        val body = JSONObject()
            .put("system", system)
            .put("messages", JSONArray().also { a -> turns.forEach { a.put(JSONObject().put("role", it.role).put("text", it.text)) } })
            .put("json", json).put("temperature", temperature).put("maxTokens", maxTokens)
            .put("images", JSONArray().also { a -> images.forEach { a.put(JSONObject().put("mime", "image/jpeg").put("data", it)) } })
        val (code, text) = post("${c.backendUrl}/v1/chat", mapOf("Authorization" to "Bearer ${c.accessCode}"), body.toString())
        if (code !in 200..299) return failure(code, text)
        val o = JSONObject(text)
        val answer = o.optString("text")
        if (answer.isBlank()) return OnlineResult.Err("AI খালি উত্তর দিল", true)
        return OnlineResult.Ok(answer, System.currentTimeMillis() - t0, o.optString("provider", "backend"))
    }

    private fun viaGemini(
        c: Config, system: String, turns: List<ChatTurn>, json: Boolean, temperature: Double, maxTokens: Int,
        images: List<String>, t0: Long
    ): OnlineResult {
        val contents = JSONArray()
        turns.forEachIndexed { i, t ->
            val parts = JSONArray().put(JSONObject().put("text", t.text))
            if (i == turns.lastIndex && t.role == "user") {
                images.forEach { parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", it))) }
            }
            contents.put(JSONObject().put("role", if (t.role == "model") "model" else "user").put("parts", parts))
        }
        val gen = JSONObject().put("temperature", temperature).put("maxOutputTokens", maxTokens)
        if (json) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", gen)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${c.model}:generateContent"
        val (code, text) = post(url, mapOf("x-goog-api-key" to c.geminiKey.orEmpty()), body.toString())
        if (code !in 200..299) return failure(code, text)
        val cand = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
            ?: return OnlineResult.Err("AI উত্তর দিল না (নিরাপত্তা ফিল্টার হতে পারে)", false)
        val parts = cand.optJSONObject("content")?.optJSONArray("parts")
        val answer = buildString {
            if (parts != null) for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text"))
        }
        if (answer.isBlank()) return OnlineResult.Err("AI খালি উত্তর দিল", true)
        return OnlineResult.Ok(answer, System.currentTimeMillis() - t0, "gemini")
    }
}
