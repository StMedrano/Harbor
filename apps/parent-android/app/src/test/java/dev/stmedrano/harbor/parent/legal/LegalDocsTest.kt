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
    @Test fun everyDocumentNamesWhoToContact() {
        LegalDoc.values().forEach { doc ->
            val text = asset(doc)
            assertTrue(doc.name, text.contains("Stalin Medrano") && text.contains("stalinmedrano@mytechtactics.com"))
        }
    }
    @Test fun theFamilyMapIsDisclosedToChildrenAndParentsInEveryDocument() {
        val child = asset(LegalDoc.CHILD_ABOUT)
        assertTrue(child.contains("The other children in your family can see your latest place"))
        assertTrue(child.contains("A parent can turn the family map off for children"))
        val parent = asset(LegalDoc.PARENT_ABOUT)
        assertTrue(parent.contains("Share my location is off by default"))
        assertTrue(parent.contains("Children can see the family map is on by default"))
        assertTrue(parent.contains("removed after 24 hours"))
        assertTrue(asset(LegalDoc.PRIVACY).contains("including the other children"))
        assertTrue(asset(LegalDoc.PRIVACY).contains("after 24 hours"))
        assertTrue(asset(LegalDoc.TERMS).contains("family map"))
        // A parent who shares is seen by the whole family, the other parents included. The documents promised the
        // older, narrower rule for a release after it changed, so both halves are held here.
        assertTrue(parent.contains("everyone in your family sees it"))
        assertTrue(asset(LegalDoc.PRIVACY).contains("the other parents included"))
        val all = LegalDoc.values().joinToString("\n") { asset(it) }.lowercase()
        listOf("does not show it to other parents", "shared place to other parents", "other parents never see")
            .forEach { assertFalse(it, all.contains(it)) }
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
