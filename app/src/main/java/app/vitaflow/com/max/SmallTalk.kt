package app.vitaflow.com.max

import java.time.LocalTime

/**
 * Instant answers for the most common small talk. "hi" does not need an AI, so it is answered at once, even offline.
 */
object SmallTalk {
    private fun clean(s: String) = s.lowercase().replace(Regex("[,।.!?]"), " ").replace(Regex("\\s+"), " ").trim()

    private val greetings = setOf(
        "hi", "hello", "hey", "hii", "hey max", "hi max", "hello max", "হাই", "হ্যালো", "হেলো", "হাই ম্যাক্স", "হ্যালো ম্যাক্স",
        "সালাম", "আসসালামু আলাইকুম", "assalamu alaikum", "assalamualaikum", "salam", "good morning", "good evening", "শুভ সকাল", "শুভ সন্ধ্যা"
    )
    private val thanks = setOf("thanks", "thank you", "thanks max", "ধন্যবাদ", "থ্যাংকস", "থ্যাঙ্ক ইউ", "শুকরিয়া")
    private val how = setOf("how are you", "কেমন আছো", "কেমন আছ", "কেমন আছেন", "কি খবর", "কী খবর")

    private fun period(): String = when (LocalTime.now().hour) {
        in 5..11 -> "শুভ সকাল"
        in 12..15 -> "শুভ দুপুর"
        in 16..18 -> "শুভ বিকাল"
        in 19..21 -> "শুভ সন্ধ্যা"
        else -> "শুভ রাত্রি"
    }

    fun reply(text: String, name: String): String? {
        val t = clean(text)
        val first = name.trim().substringBefore(' ')
        return when {
            t in greetings -> "${period()}, $first! আজ কী করবেন?"
            t in thanks -> "আপনাকেও ধন্যবাদ! আর কিছু লাগলে বলুন।"
            t in how -> "আমি ভালো আছি, $first! আপনার আজ কেমন যাচ্ছে?"
            else -> null
        }
    }
}
