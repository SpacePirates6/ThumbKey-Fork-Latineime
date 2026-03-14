package com.dessalines.thumbkey.prediction

/**
 * Simple emoji keyword lookup for ThumbKey prediction.
 *
 * Maps common words to relevant emoji suggestions. When the user types a word
 * that matches a keyword, the corresponding emoji is offered alongside text suggestions.
 *
 * This is a lightweight implementation compared to FutoBoard's full emoji dictionary.
 * Can be expanded or replaced with a data file in the future.
 */
object EmojiSuggestions {

    /**
     * Returns emoji suggestions for a typed word, or empty list if none match.
     * At most 2 emoji are returned to avoid overwhelming the suggestion bar.
     */
    fun getEmojiForWord(word: String): List<String> {
        val lc = word.lowercase().trim()
        return EMOJI_MAP[lc]?.take(2) ?: emptyList()
    }

    /**
     * Check if a word has any emoji associations.
     */
    fun hasEmoji(word: String): Boolean = word.lowercase().trim() in EMOJI_MAP

    // Common emoji-word associations
    private val EMOJI_MAP = mapOf(
        // Emotions
        "happy" to listOf("😊", "😄", "🙂"),
        "sad" to listOf("😢", "😞", "🥺"),
        "angry" to listOf("😠", "😡", "🤬"),
        "love" to listOf("❤️", "😍", "🥰"),
        "heart" to listOf("❤️", "💕", "💖"),
        "laugh" to listOf("😂", "🤣", "😆"),
        "cry" to listOf("😭", "😢", "🥺"),
        "cool" to listOf("😎", "🆒", "❄️"),
        "sick" to listOf("🤒", "🤢", "😷"),
        "tired" to listOf("😴", "🥱", "😪"),
        "surprise" to listOf("😲", "😮", "🤯"),
        "surprised" to listOf("😲", "😮", "🤯"),
        "confused" to listOf("😕", "🤔", "😐"),
        "shy" to listOf("🙈", "😳", "☺️"),
        "scared" to listOf("😨", "😱", "🫣"),
        "nervous" to listOf("😰", "😬", "🫣"),
        "excited" to listOf("🥳", "🤩", "🎉"),
        "bored" to listOf("😑", "🥱", "😐"),
        "think" to listOf("🤔", "💭", "🧐"),
        "thinking" to listOf("🤔", "💭", "🧐"),
        "wink" to listOf("😉", "😜"),
        "hug" to listOf("🤗", "🫂"),
        "kiss" to listOf("😘", "💋"),

        // Greetings / Common
        "hello" to listOf("👋", "🙋"),
        "hi" to listOf("👋", "🙋"),
        "bye" to listOf("👋", "✌️"),
        "thanks" to listOf("🙏", "👍"),
        "thank" to listOf("🙏", "👍"),
        "please" to listOf("🙏", "🥺"),
        "sorry" to listOf("😔", "🙇"),
        "congrats" to listOf("🎉", "🥳"),
        "congratulations" to listOf("🎉", "🥳"),
        "yes" to listOf("✅", "👍"),
        "no" to listOf("❌", "👎"),
        "ok" to listOf("👌", "✅"),
        "okay" to listOf("👌", "✅"),

        // Objects / Activities
        "phone" to listOf("📱", "☎️"),
        "music" to listOf("🎵", "🎶"),
        "movie" to listOf("🎬", "🍿"),
        "book" to listOf("📚", "📖"),
        "car" to listOf("🚗", "🏎️"),
        "house" to listOf("🏠", "🏡"),
        "home" to listOf("🏠", "🏡"),
        "food" to listOf("🍕", "🍔"),
        "pizza" to listOf("🍕"),
        "coffee" to listOf("☕", "🫖"),
        "tea" to listOf("🍵", "🫖"),
        "beer" to listOf("🍺", "🍻"),
        "wine" to listOf("🍷", "🥂"),
        "cake" to listOf("🎂", "🍰"),
        "money" to listOf("💰", "💵"),
        "fire" to listOf("🔥", "🧯"),
        "water" to listOf("💧", "🌊"),
        "rain" to listOf("🌧️", "☔"),
        "snow" to listOf("❄️", "🌨️"),
        "sun" to listOf("☀️", "🌞"),
        "moon" to listOf("🌙", "🌕"),
        "star" to listOf("⭐", "🌟"),
        "flower" to listOf("🌸", "🌺"),
        "tree" to listOf("🌳", "🌲"),
        "dog" to listOf("🐕", "🐶"),
        "cat" to listOf("🐈", "🐱"),
        "bird" to listOf("🐦", "🦅"),
        "fish" to listOf("🐟", "🐠"),
        "gift" to listOf("🎁", "🎀"),
        "party" to listOf("🎉", "🥳"),
        "time" to listOf("⏰", "🕐"),
        "sleep" to listOf("😴", "💤"),
        "work" to listOf("💼", "🏢"),
        "run" to listOf("🏃", "🏃‍♂️"),
        "drive" to listOf("🚗", "🏎️"),
        "fly" to listOf("✈️", "🛩️"),
        "travel" to listOf("✈️", "🌍"),
        "beach" to listOf("🏖️", "🌊"),
        "mountain" to listOf("⛰️", "🏔️"),
        "game" to listOf("🎮", "🕹️"),
        "win" to listOf("🏆", "🥇"),
        "lose" to listOf("😞", "👎"),

        // Gestures
        "thumbsup" to listOf("👍"),
        "thumbs" to listOf("👍", "👎"),
        "clap" to listOf("👏"),
        "wave" to listOf("👋"),
        "pray" to listOf("🙏"),
        "peace" to listOf("✌️", "☮️"),
        "fist" to listOf("✊", "👊"),
        "point" to listOf("👉", "☝️"),
        "muscle" to listOf("💪"),
        "strong" to listOf("💪", "🦾"),

        // Weather
        "hot" to listOf("🔥", "🥵"),
        "cold" to listOf("🥶", "❄️"),
        "warm" to listOf("☀️", "🌡️"),
        "storm" to listOf("⛈️", "🌩️"),
        "wind" to listOf("💨", "🌬️"),

        // Special
        "lol" to listOf("😂", "🤣"),
        "omg" to listOf("😱", "🤯"),
        "wtf" to listOf("🤬", "😤"),
        "brb" to listOf("⏳", "👋"),
        "idk" to listOf("🤷", "🤔"),
        "imo" to listOf("💭", "🤔"),
        "tbh" to listOf("💯", "🫡"),
        "fyi" to listOf("ℹ️", "📝"),
        "asap" to listOf("⚡", "🏃"),
    )
}
