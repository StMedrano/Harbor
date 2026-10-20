package dev.stmedrano.harbor.parent.legal

import dev.stmedrano.harbor.parent.alerts.MonitoredApps
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class LegalDocsTest {
    private fun asset(doc: LegalDoc): String {
        // Unit tests run with the module directory as the working directory.
        val file = listOf("src/main/assets/${doc.assetPath}", "app/src/main/assets/${doc.assetPath}").map(::File).first { it.exists() }
        return file.readText()
    }

    @Test fun parsesTitlesHeadingsParagraphsAndBullets() {
        val blocks = parseLegal("# T\n\nfirst line\nsecond line\n\n## H\n- a\n- b\n")
        assertEquals(listOf(LegalBlock.Title("T"), LegalBlock.Paragraph("first line second line"), LegalBlock.Heading("H"),
            LegalBlock.Bullet("a"), LegalBlock.Bullet("b")), blocks)
    }
    @Test fun everyDocumentHasATitleAndSections() {
        for (doc in LegalDoc.values()) {
            val blocks = parseLegal(fillLegalPlaceholders(asset(doc)))
            assertTrue(doc.name, blocks.first() is LegalBlock.Title)
            assertTrue(doc.name, blocks.count { it is LegalBlock.Heading } >= 4)
        }
    }
    @Test fun theMonitoredAppListComesFromTheCodeNotFromTheText() {
        val filled = fillLegalPlaceholders(asset(LegalDoc.CHILD_ABOUT))
        assertFalse(filled.contains("{{"))
        MonitoredApps.labels().forEach { assertTrue(it, filled.contains(it)) }
        assertTrue(fillLegalPlaceholders(asset(LegalDoc.PRIVACY)).contains(MonitoredApps.labels().first()))
    }
    @Test fun theDocumentsNeverPromiseWhatHarborDoesNotDo() {
        val child = asset(LegalDoc.CHILD_ABOUT)
        assertTrue(child.contains("Only your parents"))
        assertTrue(child.contains("never records your screen, your camera or your microphone"))
        assertTrue(child.contains("never sends your parents the words of a message"))
        val all = LegalDoc.values().joinToString("\n") { asset(it) }.lowercase()
        // Features Harbor does not have must not be advertised as present.
        listOf("sos button", "geofence", "block apps", "web filter").forEach { assertFalse(it, all.contains(it)) }
    }
}
