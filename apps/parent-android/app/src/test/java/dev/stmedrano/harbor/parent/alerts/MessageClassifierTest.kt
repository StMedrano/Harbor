package dev.stmedrano.harbor.parent.alerts

import dev.stmedrano.harbor.parent.alerts.AlertCategory.*
import dev.stmedrano.harbor.parent.alerts.rules.AlertRuleSets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All message text here is synthetic and English-only. A fixture names the text, whether the sender is a saved
 * contact, and the exact set of categories the classifier must return. Negatives assert the empty set so that
 * jokes, school topics and ordinary speech never alert; obfuscation fixtures assert that leetspeak and spacing
 * do not hide a match.
 */
class MessageClassifierTest {
    private val classifier = MessageClassifier()

    private data class Case(val text: String, val savedContact: Boolean, val expected: Set<AlertCategory>)
    private fun pos(text: String, vararg c: AlertCategory) = Case(text, false, c.toSet())
    private fun posSaved(text: String, vararg c: AlertCategory) = Case(text, true, c.toSet())
    private fun neg(text: String, saved: Boolean = false) = Case(text, saved, emptySet())

    // ---- Positive and negative fixtures, grouped by category ----
    private val bullying = listOf(
        pos("nobody likes you, just leave", BULLYING),
        pos("everyone hates you here", BULLYING),
        pos("you are worthless and dumb", BULLYING),
        pos("youre worthless", BULLYING),
        pos("honestly you are ugly", BULLYING),
        pos("just kill yourself already", BULLYING),
        pos("kys", BULLYING),
        neg("you're killing it at the game, nice!"),
        neg("nobody likes waiting in line"),
        neg("that sweater is kind of ugly though"), // comment about a thing, not "you are ugly"
    )
    private val selfHarm = listOf(
        pos("i want to die", SELF_HARM),
        pos("sometimes i want to kill myself", SELF_HARM),
        pos("i might just kill myself", SELF_HARM),
        pos("i hate my life so much", SELF_HARM),
        pos("i want to end my life", SELF_HARM),
        pos("i keep wanting to hurt myself", SELF_HARM),
        neg("this homework is killing me lol"),
        neg("im dying to see the new movie tonight"),
        neg("i hate my haircut"),
    )
    private val adult = listOf(
        pos("send nudes", ADULT_CONTENT),
        pos("send me nudes please", ADULT_CONTENT),
        pos("do you have any nude pics", ADULT_CONTENT),
        pos("send a naked pic", ADULT_CONTENT),
        neg("the art teacher set up a nude figure study"),
        neg("we painted a still life today"),
    )
    private val drugs = listOf(
        pos("can you buy weed for the party", DRUGS_ALCOHOL),
        pos("lets smoke weed after school", DRUGS_ALCOHOL),
        pos("we should get high later", DRUGS_ALCOHOL),
        pos("everyone wants to get drunk friday", DRUGS_ALCOHOL),
        pos("i can sell pills if you want", DRUGS_ALCOHOL),
        neg("we read about the opium wars and prohibition"),
        neg("the kite flew really high today"), // "really high", not "get high"
    )
    private val violence = listOf(
        pos("i will kill you", VIOLENCE_THREAT),
        pos("im going to beat you up after class", VIOLENCE_THREAT),
        pos("ill hurt you if you tell", VIOLENCE_THREAT),
        pos("he said hed bring a knife tomorrow", VIOLENCE_THREAT),
        neg("we watched a boxing match on tv"),
        neg("you are killing it, great job"),
    )
    private val unknownAdult = listOf(
        pos("dont tell your parents about this", UNKNOWN_ADULT_CONTACT),
        pos("this is our secret ok", UNKNOWN_ADULT_CONTACT),
        pos("its our little secret", UNKNOWN_ADULT_CONTACT),
        pos("how old are you really", UNKNOWN_ADULT_CONTACT),
        pos("where do you live", UNKNOWN_ADULT_CONTACT),
        pos("keep this between us", UNKNOWN_ADULT_CONTACT),
        // Same asks from a SAVED contact do not raise the unknown-adult category.
        posSaved("dont tell your parents about this"),
        posSaved("how old are you really"),
        neg("how old is your dog", saved = false),
        neg("dont forget to tell your teacher about the trip"),
    )
    private val obfuscated = listOf(
        pos("k i l l   y o u r s e l f", BULLYING),
        pos("k1ll y0urself", BULLYING),
        pos("s3nd nud3s", ADULT_CONTENT),
        pos("buy w33d tonight", DRUGS_ALCOHOL),
        pos("i w4nt to di3", SELF_HARM),
    )
    private val multi = listOf(
        pos("everyone hates you, just kys", BULLYING),
        pos("i want to die and i will kill you", SELF_HARM, VIOLENCE_THREAT),
    )
    private val benign = listOf(
        neg(""),
        neg("   "),
        neg("see you at practice at five"),
        neg("can you send me the homework notes"),
        neg("great game today, proud of you"),
    )

    // Everyday wordings added in rule set 2, with look-alikes that must stay quiet.
    private val v2 = listOf(
        pos("you are stupid", BULLYING),
        pos("youre a loser", BULLYING),
        pos("i hope you die", BULLYING),
        pos("i hate you", BULLYING),
        pos("no one likes you", BULLYING),
        pos("i wanna die", SELF_HARM),
        pos("i dont want to live anymore", SELF_HARM),
        pos("i hate myself", SELF_HARM),
        pos("i have been cutting myself", SELF_HARM),
        pos("got any nudes", ADULT_CONTENT),
        pos("show me your body", ADULT_CONTENT),
        pos("lets get wasted tonight", DRUGS_ALCOHOL),
        pos("i can sell drugs", DRUGS_ALCOHOL),
        pos("im going to shoot you", VIOLENCE_THREAT),
        pos("bring a gun tomorrow", VIOLENCE_THREAT),
        pos("are you alone right now", UNKNOWN_ADULT_CONTACT),
        pos("delete this message ok", UNKNOWN_ADULT_CONTACT),
        posSaved("are you alone right now"),
        neg("that quiz was so stupid"),
        neg("im so stupid for forgetting my charger"),
        neg("we should go die hard marathon this weekend"),
        neg("my uncle says dont do drugs"),
        neg("i cut myself a slice of cake"),
        neg("a freak accident closed the road"),
        neg("we pulled weeds out of the garden"),
    )

    private fun allCases() = v2 + bullying + selfHarm + adult + drugs + violence + unknownAdult + obfuscated + multi + benign

    @Test fun everyFixtureMapsToExactlyItsExpectedCategories() {
        for (case in allCases()) {
            val result = classifier.classify(case.text, case.savedContact)
            assertEquals("text=<${case.text}> saved=${case.savedContact}", case.expected, result.categories.toSet())
        }
    }

    @Test fun matchedCategoriesAreOrderedAndDistinct() {
        val result = classifier.classify("i want to die and i will kill you", false)
        assertEquals(listOf(SELF_HARM, VIOLENCE_THREAT), result.categories)
        assertEquals(result.categories.distinct(), result.categories)
        // Stable enum order regardless of the order the phrases appear in the text.
        assertEquals(result.categories, classifier.classify("i will kill you and i want to die", false).categories)
    }

    @Test fun reportsTheRuleSetVersionOnEveryClassification() {
        val expected = AlertRuleSets.latest().version
        assertEquals(2, expected)
        for (case in allCases()) assertEquals(expected, classifier.classify(case.text, case.savedContact).ruleSetVersion)
    }

    /**
     * Property: the classifier's output carries none of the input text. The output's only semantic payload is the
     * category names (a fixed vocabulary) and the rule-set version, so that is the surface tested — the structural
     * `Classification(...)` wrapper is not input-derived and is excluded.
     */
    @Test fun outputNeverContainsTheInputText() {
        for (case in allCases().filter { it.text.isNotBlank() }) {
            val result = classifier.classify(case.text, case.savedContact)
            val payload = (result.categories.map { it.name } + result.ruleSetVersion.toString()).joinToString("|").lowercase()
            assertFalse("leaked raw text for <${case.text}>", payload.contains(case.text.lowercase()))
            val vocabulary = AlertCategory.values().flatMap { it.name.lowercase().split("_") }.toSet()
            // Whole words only: "now" inside "unknown" is not a leak, and a word that is itself part of the fixed category vocabulary is not either.
            for (word in case.text.lowercase().split(Regex("\\s+")).filter { it.length >= 3 && it !in vocabulary }) {
                assertFalse("leaked word <$word> for <${case.text}>", payload.split(Regex("[^a-z0-9]+")).contains(word))
            }
        }
    }

    @Test fun selfHarmAndBullyingAreNotConfusedAcrossFirstAndSecondPerson() {
        assertEquals(setOf(SELF_HARM), classifier.classify("i want to kill myself", false).categories.toSet())
        assertEquals(setOf(BULLYING), classifier.classify("you should kill yourself", false).categories.toSet())
    }
}

class AlertRuleSetVersionsTest {
    @Test fun versionOneStillResolvesAndVersionTwoOnlyAddsRules() {
        val v1 = AlertRuleSets.forVersion(1)!!
        val v2 = AlertRuleSets.forVersion(2)!!
        assertTrue(v2.rules.containsAll(v1.rules))
        assertTrue(v2.rules.size > v1.rules.size)
        assertEquals(v2, AlertRuleSets.latest())
    }
    @Test fun anOlderRuleSetStillClassifiesWithItsOwnPhrases() {
        val old = MessageClassifier(AlertRuleSets.forVersion(1)!!)
        assertEquals(setOf(BULLYING), old.classify("just kill yourself already", false).categories.toSet())
        assertTrue(old.classify("you are stupid", false).categories.isEmpty())
        assertEquals(1, old.classify("kys", false).ruleSetVersion)
    }
}
