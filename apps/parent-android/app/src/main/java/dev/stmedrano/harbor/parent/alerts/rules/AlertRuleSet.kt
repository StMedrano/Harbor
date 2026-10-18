package dev.stmedrano.harbor.parent.alerts.rules

import dev.stmedrano.harbor.parent.alerts.AlertCategory

/**
 * One reviewable rule: a category and the normalized word sequence that signals it. Words are already
 * lowercase, contraction-joined (no apostrophes) and leet-free, because the classifier normalizes the
 * message the same way before matching. A rule carries no message text and no example text.
 */
data class AlertRule(val category: AlertCategory, val words: List<String>) {
    init {
        require(words.isNotEmpty() && words.all { it.isNotEmpty() && it.all(Char::isLetter) }) { "Rule words must be non-empty letters" }
    }
}

/** A versioned, immutable rule set. The version travels with every alert so a parent view can show which rules decided. */
data class AlertRuleSet(val version: Int, val rules: List<AlertRule>) {
    init { require(version >= 1) { "Rule set version must be >= 1" } }
}

private fun rule(category: AlertCategory, vararg phrase: String) = AlertRule(category, phrase.toList())

/** Registry of rule-set versions. Rule text is synthetic, English-only, and deliberately conservative. */
object AlertRuleSets {
    /**
     * Version 1. Phrases are specific enough that everyday speech, school topics and jokes do not trip them:
     * self-harm is first person, bullying and threats are second person, and the grooming category only
     * applies to messages from someone who is not a saved contact (the classifier enforces that).
     */
    val V1: AlertRuleSet = AlertRuleSet(
        version = 1,
        rules = listOf(
            // Bullying / harassment — aimed at the other person.
            rule(AlertCategory.BULLYING, "nobody", "likes", "you"),
            rule(AlertCategory.BULLYING, "everyone", "hates", "you"),
            rule(AlertCategory.BULLYING, "you", "are", "worthless"),
            rule(AlertCategory.BULLYING, "youre", "worthless"),
            rule(AlertCategory.BULLYING, "you", "are", "ugly"),
            rule(AlertCategory.BULLYING, "youre", "ugly"),
            rule(AlertCategory.BULLYING, "kill", "yourself"),
            rule(AlertCategory.BULLYING, "kys"),
            // Self-harm / wellbeing — first person.
            rule(AlertCategory.SELF_HARM, "i", "want", "to", "die"),
            rule(AlertCategory.SELF_HARM, "i", "want", "to", "kill", "myself"),
            rule(AlertCategory.SELF_HARM, "kill", "myself"),
            rule(AlertCategory.SELF_HARM, "i", "hate", "my", "life"),
            rule(AlertCategory.SELF_HARM, "end", "my", "life"),
            rule(AlertCategory.SELF_HARM, "hurt", "myself"),
            // Adult content.
            rule(AlertCategory.ADULT_CONTENT, "send", "nudes"),
            rule(AlertCategory.ADULT_CONTENT, "send", "me", "nudes"),
            rule(AlertCategory.ADULT_CONTENT, "nude", "pics"),
            rule(AlertCategory.ADULT_CONTENT, "naked", "pic"),
            rule(AlertCategory.ADULT_CONTENT, "naked", "pics"),
            // Drugs / alcohol.
            rule(AlertCategory.DRUGS_ALCOHOL, "buy", "weed"),
            rule(AlertCategory.DRUGS_ALCOHOL, "smoke", "weed"),
            rule(AlertCategory.DRUGS_ALCOHOL, "get", "high"),
            rule(AlertCategory.DRUGS_ALCOHOL, "get", "drunk"),
            rule(AlertCategory.DRUGS_ALCOHOL, "sell", "pills"),
            rule(AlertCategory.DRUGS_ALCOHOL, "selling", "pills"),
            // Violence / threats — aimed at the other person.
            rule(AlertCategory.VIOLENCE_THREAT, "kill", "you"),
            rule(AlertCategory.VIOLENCE_THREAT, "beat", "you", "up"),
            rule(AlertCategory.VIOLENCE_THREAT, "hurt", "you"),
            rule(AlertCategory.VIOLENCE_THREAT, "bring", "a", "knife"),
            // Contact from an unknown adult — grooming-style asks. Only fires for non-saved contacts.
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "dont", "tell", "your", "parents"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "this", "is", "our", "secret"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "our", "little", "secret"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "how", "old", "are", "you"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "where", "do", "you", "live"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "keep", "this", "between", "us"),
        ),
    )

    private val byVersion = listOf(V1).associateBy { it.version }

    fun latest(): AlertRuleSet = V1
    fun forVersion(version: Int): AlertRuleSet? = byVersion[version]
}
