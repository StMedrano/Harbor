package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.random.Random

enum class GreetingAudience { PARENT, CHILD }

/**
 * Map (parent) and Today (child) open with a greeting instead of a fixed title (design system `ScreenHeader`). One is
 * picked by time of day or from the harbor greetings when the screen opens, stays put while it is open, and is never the
 * one shown last. Greetings welcome; they never claim status.
 */
object HarborGreetings {
    private val parent = mapOf(
        "morning" to listOf("Good morning, {name}", "First light over the harbor", "Morning, {name}. Fresh tide today"),
        "afternoon" to listOf("Good afternoon, {name}", "Afternoon on the water", "Welcome back, {name}"),
        "evening" to listOf("Good evening, {name}", "Evening in the harbor", "Lanterns on, {name}"),
        "night" to listOf("Still up, {name}?", "Night watch, {name}", "Quiet hours in the harbor"),
        "any" to listOf("Your safe harbor", "Welcome back to the harbor", "Back in port, {name}", "Your crew, at a glance", "Ahoy, {name}"),
    )
    private val child = mapOf(
        "morning" to listOf("Good morning, {name}", "Morning, {name}"),
        "afternoon" to listOf("Good afternoon, {name}", "Hey {name}"),
        "evening" to listOf("Good evening, {name}", "Hi, {name}"),
        "night" to listOf("Hi, {name}", "Late one, {name}?"),
        "any" to listOf("Hi, {name}", "Welcome back, {name}", "Ahoy, {name}"),
    )
    private val lastShown = mutableMapOf<GreetingAudience, String>()

    fun partOfDay(hour: Int) = when (hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }

    /** The greetings that fit [hour]. Without a name, a parent's greetings drop the ones that need it and a child's drop the name. */
    fun pool(audience: GreetingAudience, hour: Int, name: String?): List<String> {
        val set = if (audience == GreetingAudience.PARENT) parent else child
        val templates = set.getValue(partOfDay(hour)) + set.getValue("any")
        val named = name?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            named != null -> templates.map { it.replace("{name}", named) }
            audience == GreetingAudience.PARENT -> templates.filterNot { "{name}" in it }
            else -> templates.map { it.replace(", {name}", "").replace(" {name}", "").replace("{name}", "") }
        }.distinct()
    }

    /** Picks a greeting, never [avoid] (by default the last one shown to [audience]) unless it is the only one. */
    fun pick(audience: GreetingAudience, hour: Int, name: String?, random: Random = Random.Default,
        avoid: String? = synchronized(lastShown) { lastShown[audience] }): String {
        val all = pool(audience, hour, name)
        val choices = all.filter { it != avoid }.ifEmpty { all }
        val greeting = choices[random.nextInt(choices.size)]
        synchronized(lastShown) { lastShown[audience] = greeting }
        return greeting
    }
}

/** A greeting picked when this screen opens. */
@Composable
fun rememberGreeting(audience: GreetingAudience, name: String? = null): String =
    remember(audience, name) { HarborGreetings.pick(audience, java.time.LocalTime.now().hour, name) }
