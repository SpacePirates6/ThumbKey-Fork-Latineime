package com.dessalines.thumbkey.prediction

object OffensiveWordFilter {

    @Volatile
    var enabled: Boolean = true

    private val offensiveWords: Set<String> = setOf(
        "ass",
        "asshole",
        "ballsack",
        "bastard",
        "bitch",
        "blowjob",
        "bullshit",
        "clitoris",
        "cock",
        "cocksucker",
        "crap",
        "cum",
        "cunt",
        "damn",
        "dick",
        "dildo",
        "dumbass",
        "dyke",
        "fag",
        "faggot",
        "faggots",
        "fatass",
        "fck",
        "felching",
        "fellate",
        "fellatio",
        "flange",
        "fuck",
        "fucked",
        "fucker",
        "fucking",
        "fvck",
        "goddamn",
        "hell",
        "homo",
        "jackass",
        "jerk",
        "jerkoff",
        "jizz",
        "kike",
        "labia",
        "motherfucker",
        "motherfucking",
        "muff",
        "nigga",
        "nigger",
        "niggers",
        "penis",
        "piss",
        "pissed",
        "prick",
        "pussy",
        "retard",
        "retarded",
        "scrotum",
        "shit",
        "shitty",
        "slut",
        "smegma",
        "spic",
        "spunk",
        "tit",
        "tits",
        "turd",
        "twat",
        "vagina",
        "wank",
        "whore",
        "wtf"
    )

    fun isOffensive(word: String): Boolean {
        if (!enabled) return false
        return word.lowercase() in offensiveWords
    }

    fun filterSuggestions(suggestions: List<String>): List<String> {
        if (!enabled) return suggestions
        return suggestions.filter { !isOffensive(it) }
    }

    fun <T> filterSuggestions(suggestions: List<T>, wordExtractor: (T) -> String): List<T> {
        if (!enabled) return suggestions
        return suggestions.filter { !isOffensive(wordExtractor(it)) }
    }
}
