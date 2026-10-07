package dev.stmedrano.harbor.parent.ui

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.auth.*
import dev.stmedrano.harbor.parent.data.*
import dev.stmedrano.harbor.parent.family.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FamilyRouteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val identity = ParentIdentity("synthetic-parent", "synthetic-session")
    private val databaseName = "family-route-test"
    private var database: ParentDatabase? = null

    @After fun cleanup() { database?.close(); context.deleteDatabase(databaseName) }

    @Test fun pairingDialogUsesVercelSurface() {
        compose.setContent { HarborTheme(dark = false) {
            PairingSheet(PairingState("child", PairingCode("123456", "2099-01-01T00:10:00Z")), false, null, {}, {}, {})
        } }
        val pixels = compose.onNode(isDialog()).captureToImage().toPixelMap()
        var surfacePixels = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            if (pixels[x, y] == Color(0xFFFBF8F2)) surfacePixels++
        }
        assertTrue("Dialog should use the existing Vercel light surface", surfacePixels > pixels.width * pixels.height / 2)
    }

    @Test fun largeTextPairingRequiresFreshReadAndBackReturnsToFamily() {
        assertEquals(1.8f, context.resources.configuration.fontScale, 0.01f)
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        val date = "2026-01-01T00:00:00Z"
        val family = FamilyV1(1, "family", "Family", "UTC", date, date)
        val child = ChildV1(1, "child", family.id, "Child", date, date)
        val device = DevicePublicV1(1, "device", family.id, child.id, "Phone", "standard", "active", null, date, date)
        val enrolled = java.util.concurrent.atomic.AtomicBoolean(false)
        val api = object : ParentApi {
            override suspend fun listFamilies() = listOf(family)
            override suspend fun createFamily(name: String, key: String): CreatedFamily = error("not used")
            override suspend fun createChild(request: CreateChildRequest): ChildV1 = error("not used")
            override suspend fun createPairing(childId: String) = PairingCode("123456", "2099-01-01T00:10:00Z")
            override suspend fun readFamily(familyId: String) = FamilySnapshot(family,
                FamilyMemberV1(1, "membership", family.id, identity.userId, "owner", "active", date, date),
                listOf(child), if (enrolled.get()) listOf(device) else emptyList(), 1234)
        }
        context.deleteDatabase(databaseName)
        val dao = ParentDatabase.open(context, databaseName).also { database = it }.familyCache()
        val store = SecureAuthStore(object : AuthValues {
            val values = mutableMapOf<String, String>()
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun clear() = values.clear()
        }, object : AuthCipher {
            override fun encrypt(slot: String, value: ByteArray) = value
            override fun decrypt(slot: String, value: ByteArray) = value
        })
        val repository = FamilyRepository(api, dao) { identity }
        val model = FamilyViewModel(api, repository, PendingChildCreation(api, dao, { identity }),
            PairingModel(api, { identity }), store, { identity })
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.8f)) {
                HarborTheme { ParentApp { FamilyRoute(model, identity) } }
            }
        }
        compose.waitUntil(10000) { !model.state.value.busy && model.state.value.selectedChildId == child.id }
        compose.onNodeWithText("Pair device").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(10000) { model.pairing.state.value.code != null }
        compose.onNodeWithText("123456").assertExists()
        assertFalse(model.pairing.state.value.enrolled)
        compose.onNodeWithText("Enrollment confirmed", substring = true).assertDoesNotExist()
        enrolled.set(true)
        compose.onNodeWithText("Check enrollment").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { model.pairing.state.value.enrolled && !model.state.value.busy }
        compose.onNodeWithText("Enrollment confirmed by a fresh device read.").assertExists()
        saveScreenshot("family-pairing-large-text.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Pair child device").assertDoesNotExist()
        compose.onNodeWithText("Phone · active · standard").performScrollTo().performClick()
        compose.onNodeWithText("Last seen: not reported").performScrollTo().assertIsDisplayed()
        saveScreenshot("family-device-large-text.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("Refresh family").performScrollTo().assertIsDisplayed()
        saveScreenshot("family-large-text.png")
    }

    private fun saveScreenshot(name: String) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.getExternalFilesDir(null), name).outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
    }
}
