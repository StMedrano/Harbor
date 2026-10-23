package dev.stmedrano.harbor.parent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import kotlin.random.Random

/** The Harbor Family design system's rules that can be checked without a screen. */
class HarborDesignTest {
    private fun contrast(fg: Color, bg: Color): Double {
        val (a, b) = listOf(fg.luminance(), bg.luminance()).sortedDescending()
        return (a + 0.05) / (b + 0.05)
    }

    @Test fun everyVerifiedPairPassesInBothThemes() {
        for (t in listOf(NightHarbor, Daybreak)) {
            // Tints sit on cards, so they are measured over s2, as the design system's table is.
            fun on(c: Color) = c.compositeOver(t.s2)
            val pairs = mapOf(
                "ink on s2" to contrast(t.ink, t.s2), "ink2 on s2" to contrast(t.ink2, t.s2), "ink3 on s2" to contrast(t.ink3, t.s2),
                "acc on s2" to contrast(t.acc, t.s2), "bi on brand" to contrast(t.bi, t.brand), "dan on s2" to contrast(t.dan, t.s2),
                "onDan on dan" to contrast(t.onDan, t.dan), "warni on warns" to contrast(t.warni, on(t.warns)),
                "toast ink on toast" to contrast(t.toastInk, t.toastBg), "toast detail on toast" to contrast(t.toastSub, t.toastBg),
                "dan on dans" to contrast(t.dan, on(t.dans)), "acc on accs" to contrast(t.acc, on(t.accs)),
            )
            pairs.forEach { (pair, ratio) -> assertTrue("$pair is ${"%.2f".format(ratio)} in ${if (t.night) "Night Harbor" else "Daybreak"}", ratio >= 4.5) }
        }
        // The design system's own table, to the hundredth: the tokens were copied exactly.
        assertEquals(11.76, contrast(NightHarbor.ink, NightHarbor.s2), 0.01)
        assertEquals(5.31, contrast(Daybreak.ink2, Daybreak.s2), 0.01)
    }

    @Test fun crewColoursCarryDeepWaterInitialsAndAreNeverStatusColours() {
        (HarborKid.colors + HarborParentColor).forEach { assertTrue(contrast(NightHarbor.onColor, it) >= 7.9) }
        for (t in listOf(NightHarbor, Daybreak)) HarborKid.colors.forEach { kid -> assertFalse(kid in setOf(t.warn, t.dan, t.acc, t.brand)) }
        assertEquals(HarborKid.color(0), HarborKid.color(6))
        assertEquals(HarborKid.color(5), HarborKid.color(-1))
    }

    @Test fun parentGreetingsWithoutANameNeverShowAPlaceholder() {
        for (hour in 0..23) {
            val pool = HarborGreetings.pool(GreetingAudience.PARENT, hour, null)
            assertTrue(pool.size >= 2)
            assertTrue(pool.none { "{name}" in it || it.endsWith(",") })
        }
        assertTrue("Good morning, Alex" in HarborGreetings.pool(GreetingAudience.PARENT, 8, "Alex"))
        assertTrue("Evening in the harbor" in HarborGreetings.pool(GreetingAudience.PARENT, 19, null))
    }

    @Test fun childGreetingsDropTheNameCleanly() {
        val pool = HarborGreetings.pool(GreetingAudience.CHILD, 8, null)
        assertTrue("Good morning" in pool && "Morning" in pool && "Hi" in pool)
        assertTrue(pool.none { "{name}" in it || it.contains(", ") || it.endsWith(" ") })
        assertTrue("Late one?" in HarborGreetings.pool(GreetingAudience.CHILD, 23, null))
        assertEquals(pool.size, pool.distinct().size)
    }

    @Test fun aGreetingIsNeverTheOneShownLast() {
        var last: String? = null
        val random = Random(7)
        repeat(50) {
            val next = HarborGreetings.pick(GreetingAudience.PARENT, 10, null, random)
            assertNotEquals(last, next)
            last = next
        }
        assertEquals("afternoon", HarborGreetings.partOfDay(12))
        assertEquals("night", HarborGreetings.partOfDay(4))
    }

    @After fun clearToast() { HarborToasts.current.value?.let(HarborToasts::dismiss) }

    @Test fun aRoutineToastNeverReplacesAMoreImportantOne() {
        val urgent = HarborToastMessage(HarborToastTone.URGENT, "Leo sent an SOS")
        assertTrue(HarborToasts.show(urgent))
        assertFalse(HarborToasts.show(HarborToastMessage(HarborToastTone.SUCCESS, "Check-in request sent")))
        assertSame(urgent, HarborToasts.current.value)
        val second = HarborToastMessage(HarborToastTone.URGENT, "Maya sent an SOS")
        assertTrue(HarborToasts.show(second))
        HarborToasts.dismiss(urgent) // only the toast that is showing can be dismissed
        assertSame(second, HarborToasts.current.value)
        HarborToasts.dismiss(second)
        assertNull(HarborToasts.current.value)
        assertNull(HarborToastTone.URGENT.showMs)
        assertEquals(6_000L, HarborToastTone.REVIEW.showMs)
    }
}
