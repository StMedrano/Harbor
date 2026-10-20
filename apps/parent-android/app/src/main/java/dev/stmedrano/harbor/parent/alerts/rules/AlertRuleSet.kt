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

    /**
     * Version 2 keeps every version 1 phrase and adds the everyday wordings a child or an adult stranger actually
     * types: more insults aimed at "you", more first-person self-harm wording, threats with weapons, drug and
     * alcohol asks, and the usual asks from an unknown adult. Phrases stay multi-word and directed ("you are
     * stupid", not "stupid") so ordinary talk, school topics and jokes still do not alert.
     */
    val V2: AlertRuleSet = AlertRuleSet(
        version = 2,
        rules = V1.rules + listOf(
            // Bullying / harassment.
            rule(AlertCategory.BULLYING, "no", "one", "likes", "you"),
            rule(AlertCategory.BULLYING, "you", "are", "stupid"),
            rule(AlertCategory.BULLYING, "youre", "stupid"),
            rule(AlertCategory.BULLYING, "you", "are", "a", "loser"),
            rule(AlertCategory.BULLYING, "youre", "a", "loser"),
            rule(AlertCategory.BULLYING, "you", "are", "pathetic"),
            rule(AlertCategory.BULLYING, "youre", "pathetic"),
            rule(AlertCategory.BULLYING, "you", "are", "fat"),
            rule(AlertCategory.BULLYING, "youre", "fat"),
            rule(AlertCategory.BULLYING, "you", "are", "a", "freak"),
            rule(AlertCategory.BULLYING, "youre", "a", "freak"),
            rule(AlertCategory.BULLYING, "hope", "you", "die"),
            rule(AlertCategory.BULLYING, "i", "hate", "you"),
            rule(AlertCategory.BULLYING, "you", "should", "just", "die"),
            // Self-harm / wellbeing.
            rule(AlertCategory.SELF_HARM, "i", "wanna", "die"),
            rule(AlertCategory.SELF_HARM, "i", "want", "to", "be", "dead"),
            rule(AlertCategory.SELF_HARM, "i", "wish", "i", "was", "dead"),
            rule(AlertCategory.SELF_HARM, "i", "wish", "i", "were", "dead"),
            rule(AlertCategory.SELF_HARM, "i", "dont", "want", "to", "live"),
            rule(AlertCategory.SELF_HARM, "i", "dont", "want", "to", "be", "here"),
            rule(AlertCategory.SELF_HARM, "i", "want", "to", "disappear"),
            rule(AlertCategory.SELF_HARM, "i", "want", "to", "hurt", "myself"),
            rule(AlertCategory.SELF_HARM, "cutting", "myself"),
            rule(AlertCategory.SELF_HARM, "im", "suicidal"),
            rule(AlertCategory.SELF_HARM, "i", "am", "suicidal"),
            rule(AlertCategory.SELF_HARM, "thinking", "about", "suicide"),
            rule(AlertCategory.SELF_HARM, "going", "to", "kill", "myself"),
            rule(AlertCategory.SELF_HARM, "better", "off", "without", "me"),
            rule(AlertCategory.SELF_HARM, "i", "hate", "myself"),
            // Adult content.
            rule(AlertCategory.ADULT_CONTENT, "nudes"),
            rule(AlertCategory.ADULT_CONTENT, "send", "a", "nude"),
            rule(AlertCategory.ADULT_CONTENT, "send", "me", "a", "nude"),
            rule(AlertCategory.ADULT_CONTENT, "send", "me", "pics", "of", "your", "body"),
            rule(AlertCategory.ADULT_CONTENT, "show", "me", "your", "body"),
            rule(AlertCategory.ADULT_CONTENT, "dick", "pic"),
            rule(AlertCategory.ADULT_CONTENT, "dick", "pics"),
            rule(AlertCategory.ADULT_CONTENT, "sext", "me"),
            // Drugs / alcohol.
            rule(AlertCategory.DRUGS_ALCOHOL, "buy", "drugs"),
            rule(AlertCategory.DRUGS_ALCOHOL, "sell", "drugs"),
            rule(AlertCategory.DRUGS_ALCOHOL, "sell", "weed"),
            rule(AlertCategory.DRUGS_ALCOHOL, "got", "weed"),
            rule(AlertCategory.DRUGS_ALCOHOL, "get", "weed"),
            rule(AlertCategory.DRUGS_ALCOHOL, "get", "wasted"),
            rule(AlertCategory.DRUGS_ALCOHOL, "get", "stoned"),
            rule(AlertCategory.DRUGS_ALCOHOL, "do", "cocaine"),
            rule(AlertCategory.DRUGS_ALCOHOL, "take", "molly"),
            rule(AlertCategory.DRUGS_ALCOHOL, "buy", "alcohol"),
            rule(AlertCategory.DRUGS_ALCOHOL, "buy", "vodka"),
            rule(AlertCategory.DRUGS_ALCOHOL, "steal", "alcohol"),
            // Violence / threats.
            rule(AlertCategory.VIOLENCE_THREAT, "shoot", "you"),
            rule(AlertCategory.VIOLENCE_THREAT, "stab", "you"),
            rule(AlertCategory.VIOLENCE_THREAT, "bring", "a", "gun"),
            rule(AlertCategory.VIOLENCE_THREAT, "going", "to", "kill", "everyone"),
            rule(AlertCategory.VIOLENCE_THREAT, "shoot", "up", "the", "school"),
            rule(AlertCategory.VIOLENCE_THREAT, "bomb", "the", "school"),
            // Contact from an unknown adult. Only fires for non-saved contacts.
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "dont", "tell", "anyone"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "are", "you", "alone"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "are", "your", "parents", "home"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "delete", "this", "message"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "delete", "these", "messages"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "meet", "me", "alone"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "whats", "your", "address"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "do", "you", "have", "a", "boyfriend"),
            rule(AlertCategory.UNKNOWN_ADULT_CONTACT, "do", "you", "have", "a", "girlfriend"),
        ),
    )

    private val byVersion = listOf(V1, V2).associateBy { it.version }

    fun latest(): AlertRuleSet = V2
    fun forVersion(version: Int): AlertRuleSet? = byVersion[version]
}
