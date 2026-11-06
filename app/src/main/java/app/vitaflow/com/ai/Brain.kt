package app.vitaflow.com.ai

import app.vitaflow.com.data.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * The one place the rest of the app asks questions. It uses the online AI when it is set up (answers in a couple of
 * seconds) and falls back to the model on the phone only if that is allowed and installed.
 */
class Brain(
    private val online: OnlineBrain,
    private val local: LlmEngine,
    private val settings: SettingsStore
) {
    suspend fun hasOnline(): Boolean = online.configured()
    fun localReady(): Boolean = local.isReady()
    suspend fun isReady(): Boolean = hasOnline() || localReady()

    /** True when answers do not need a slow model load. */
    suspend fun isLoaded(): Boolean = hasOnline() || local.isLoaded()

    private suspend fun mayUseLocal(): Boolean = local.isReady() && (settings.localFallback.first() || !hasOnline())

    private suspend fun <T> run(
        onlineCall: suspend () -> OnlineResult,
        mapOnline: (String) -> T,
        localCall: suspend () -> T
    ): T {
        if (hasOnline()) {
            when (val r = onlineCall()) {
                is OnlineResult.Ok -> return mapOnline(r.text)
                is OnlineResult.Err -> if (!mayUseLocal()) throw IllegalStateException(r.message)
            }
        }
        if (!local.isReady()) throw IllegalStateException("AI পাওয়া যায়নি। সেটিংস থেকে অনলাইন AI সেট করুন।")
        return localCall()
    }

    suspend fun complete(system: String, user: String, temperature: Double = 0.3, json: Boolean = false, maxTokens: Int = 700): String =
        run(
            { online.chat(system, listOf(ChatTurn("user", user)), json, temperature, maxTokens) },
            { it },
            { local.complete(system, user, temperature) }
        )

    /** For the intent reader: same instruction every time, so the phone model can keep it ready between messages. */
    suspend fun session(key: String, system: String, user: String, temperature: Double = 0.1): String =
        run(
            { online.chat(system, listOf(ChatTurn("user", user)), json = true, temperature = temperature, maxTokens = 600) },
            { it },
            { local.completeSession(key, system, user, temperature) }
        )

    suspend fun stream(system: String, user: String, temperature: Double = 0.6, onText: (String) -> Unit): String =
        run(
            { online.chat(system, listOf(ChatTurn("user", user)), json = false, temperature = temperature, maxTokens = 600) },
            { text -> onText(text); text },
            { local.stream(system, user, temperature, onText) }
        )

    suspend fun multimodal(system: String, text: String, imagePaths: List<String>, temperature: Double = 0.3, onText: (String) -> Unit): String =
        run(
            { online.chat(system, listOf(ChatTurn("user", text)), json = false, temperature = temperature, maxTokens = 700, imagePaths = imagePaths) },
            { t -> onText(t); t },
            { local.askMultimodal(system, text, imagePaths, temperature, onText) }
        )

    /** For the settings test button. */
    suspend fun test(): String {
        if (!hasOnline()) return "অনলাইন AI সেট করা নেই।"
        return when (val r = online.chat("Reply with the single word OK.", listOf(ChatTurn("user", "ping")), maxTokens = 10)) {
            is OnlineResult.Ok -> "কাজ করছে ✓ (${r.via}, ${r.ms} ms)"
            is OnlineResult.Err -> r.message
        }
    }

    fun invalidate() = online.invalidate()
}
