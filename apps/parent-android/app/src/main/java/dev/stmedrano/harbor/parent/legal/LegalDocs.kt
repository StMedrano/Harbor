package dev.stmedrano.harbor.parent.legal

import dev.stmedrano.harbor.parent.alerts.MonitoredApps

/**
 * The long explanations live in these files, not on the main screens. Each is plain markdown under `assets/`, so they
 * can be read and edited as documents. Only titles, headings, paragraphs and "- " bullets are used.
 */
enum class LegalDoc(val assetPath: String, val label: String) {
    CHILD_ABOUT("about/child.md", "About Harbor"),
    PARENT_ABOUT("about/parent.md", "About Harbor"),
    PRIVACY("legal/privacy-policy.md", "Privacy policy"),
    TERMS("legal/terms-of-service.md", "Terms of service"),
}

sealed interface LegalBlock {
    data class Title(val text: String) : LegalBlock
    data class Heading(val text: String) : LegalBlock
    data class Paragraph(val text: String) : LegalBlock
    data class Bullet(val text: String) : LegalBlock
}

/** `{{monitored_apps}}` is the real, single list of apps Harbor watches, so the text cannot drift from the code. */
fun fillLegalPlaceholders(markdown: String, apps: List<String> = MonitoredApps.labels()): String =
    markdown.replace("{{monitored_apps}}", apps.joinToString(", "))

fun parseLegal(markdown: String): List<LegalBlock> {
    val blocks = mutableListOf<LegalBlock>()
    val paragraph = StringBuilder()
    fun flush() { if (paragraph.isNotBlank()) blocks += LegalBlock.Paragraph(paragraph.toString().trim()); paragraph.clear() }
    for (raw in markdown.lineSequence()) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flush()
            line.startsWith("## ") -> { flush(); blocks += LegalBlock.Heading(line.removePrefix("## ").trim()) }
            line.startsWith("# ") -> { flush(); blocks += LegalBlock.Title(line.removePrefix("# ").trim()) }
            line.startsWith("- ") -> { flush(); blocks += LegalBlock.Bullet(line.removePrefix("- ").trim()) }
            else -> { if (paragraph.isNotEmpty()) paragraph.append(' '); paragraph.append(line.trim()) }
        }
    }
    flush()
    return blocks
}
