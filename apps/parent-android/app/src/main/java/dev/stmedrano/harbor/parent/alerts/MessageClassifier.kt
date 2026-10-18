package dev.stmedrano.harbor.parent.alerts

import dev.stmedrano.harbor.parent.alerts.rules.AlertRuleSet
import dev.stmedrano.harbor.parent.alerts.rules.AlertRuleSets

/**
 * The ONLY things a classification carries off the message: which categories a rule set matched and which
 * rule-set version decided. There is deliberately no field that could hold the message, a matched span, a
 * sender or an excerpt — the classifier never returns any part of its input.
 */
data class Classification(val categories: List<AlertCategory>, val ruleSetVersion: Int)

/**
 * A pure, on-device, rule-based classifier. It reads a message in memory, decides which concern categories
 * apply, and returns categories plus the rule-set version — never the text. There is no network call, no
 * model, and no state. Normalization folds case, strips apostrophes, undoes common leetspeak and single-letter
 * spacing obfuscation, so "k1ll y0urself" and "k i l l   y o u r s e l f" reach the same rule as "kill yourself",
 * while ordinary speech, school topics and jokes stay below the specific multi-word phrases.
 */
class MessageClassifier(private val ruleSet: AlertRuleSet = AlertRuleSets.latest()) {

    val ruleSetVersion: Int get() = ruleSet.version

    /**
     * @param text the raw message, read into memory and never retained here.
     * @param fromSavedContact whether the sender is already a saved contact; the unknown-adult-contact
     *   category is suppressed when it is true. This is a boolean only — no contact detail enters this class.
     * @return the matched categories in a stable order, with the rule-set version. Empty categories means nothing matched.
     */
    fun classify(text: String, fromSavedContact: Boolean): Classification {
        val tokens = tokenize(text)
        val blobs = spacedOutRuns(tokens)
        val matched = HashSet<AlertCategory>()
        for (r in ruleSet.rules) {
            if (r.category == AlertCategory.UNKNOWN_ADULT_CONTACT && fromSavedContact) continue
            if (r.category in matched) continue
            if (matches(r.words, tokens, blobs)) matched += r.category
        }
        val ordered = AlertCategory.entries.filter { it in matched }
        return Classification(ordered, ruleSet.version)
    }

    private fun matches(words: List<String>, tokens: List<String>, blobs: List<String>): Boolean {
        if (containsConsecutive(tokens, words)) return true
        // Deliberate letter-spacing ("k i l l   y o u r s e l f"): a spaced-out run equal to the whole phrase.
        // Equality (not substring) so a longer phrase cannot also trip a shorter one nested inside it.
        val joined = words.joinToString("")
        return blobs.any { it == joined }
    }

    private fun containsConsecutive(tokens: List<String>, words: List<String>): Boolean {
        if (words.size > tokens.size) return false
        outer@ for (start in 0..tokens.size - words.size) {
            for (i in words.indices) if (tokens[start + i] != words[i]) continue@outer
            return true
        }
        return false
    }

    /** Runs of consecutive single-character tokens, concatenated, kept only when long enough to be deliberate (>= 4 letters). */
    private fun spacedOutRuns(tokens: List<String>): List<String> {
        val runs = ArrayList<String>()
        val current = StringBuilder()
        fun flush() { if (current.length >= 4) runs += current.toString(); current.setLength(0) }
        for (t in tokens) { if (t.length == 1) current.append(t) else flush() }
        flush()
        return runs
    }

    private fun tokenize(text: String): List<String> {
        val sb = StringBuilder()
        for (ch in text.lowercase()) {
            when {
                ch == '\'' || ch == '’' -> { /* drop apostrophes so contractions join: don't -> dont */ }
                ch.isLetter() -> sb.append(ch)
                LEET.containsKey(ch) -> sb.append(LEET.getValue(ch))
                else -> sb.append(' ')
            }
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }.map(::collapseRuns)
    }

    /** Collapses a letter repeated three or more times down to two (yesss -> yess), leaving ordinary doubles alone. */
    private fun collapseRuns(token: String): String {
        if (token.length < 3) return token
        val out = StringBuilder()
        var run = 0
        var prev = '\u0000'
        for (c in token) {
            run = if (c == prev) run + 1 else 1
            prev = c
            if (run <= 2) out.append(c)
        }
        return out.toString()
    }

    private companion object {
        val LEET: Map<Char, Char> = mapOf(
            '0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '8' to 'b', '9' to 'g',
            '@' to 'a', '$' to 's', '!' to 'i', '|' to 'i', '+' to 't', '(' to 'c',
        )
    }
}
