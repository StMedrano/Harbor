package dev.stmedrano.harbor.parent.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import dev.stmedrano.harbor.parent.family.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.mutableStateOf

class FamilyScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun snapshot() = FamilySnapshot(
        FamilyV1(1, "family", "Family", "UTC", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
        FamilyMemberV1(1, "membership", "family", "parent", "owner", "active", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
        listOf(ChildV1(1, "child", "family", "Child", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")), emptyList(), 1234)

    @Test fun frontendChildSelectorTracksSelectionWithoutEnablingCachedMutation() {
        val first = snapshot()
        val snapshot = first.copy(children = first.children + first.children.first().copy(id = "second", displayName = "Second child"))
        val selected = mutableStateOf("child")
        compose.setContent { HarborTheme { ParentApp {
            FamilyScreen(FamilyState(snapshot, cached = true), listOf(snapshot.family), selected.value,
                onRefresh = {}, onSelectFamily = {}, onSelectChild = { selected.value = it },
                onCreateFamily = {}, onAddChild = {}, onPair = {}, onDevice = {})
        } } }
        compose.onNode(hasText("Child") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Second child") and isSelectable()).performScrollTo().performClick()
        compose.onNode(hasText("Second child") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Child") and isSelectable()).assertIsNotSelected()
        compose.onNodeWithText("Add child").assertIsNotEnabled()
        compose.onNodeWithText("Pair device").assertIsNotEnabled()
    }

    @Test fun cachedFamilyLabelsStalenessAndDisablesMutations() {
        val snapshot = snapshot()
        compose.setContent {
            HarborTheme { ParentApp {
                FamilyScreen(FamilyState(snapshot, cached = true), listOf(snapshot.family), "child",
                    onRefresh = {}, onSelectFamily = {}, onSelectChild = {}, onCreateFamily = {}, onAddChild = {}, onPair = {}, onDevice = {})
            } }
        }
        compose.onNodeWithText("Cached view", substring = true).assertExists()
        compose.onNodeWithText("Add child").assertIsNotEnabled()
        compose.onNodeWithText("Pair device").assertIsNotEnabled()
        compose.onNodeWithText("Create family").assertIsNotEnabled()
    }

    @Test fun deviceViewReportsActualMetadataWithoutClaimingAppliedPolicy() {
        val device = DevicePublicV1(1, "device", "family", "child", "Phone", "standard", "revoked", null,
            "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        compose.setContent { HarborTheme { ParentApp { DeviceScreen(device, onBack = {}) } } }
        compose.onNodeWithText("Status: revoked").assertExists()
        compose.onNodeWithText("Supervision: standard").assertExists()
        compose.onNodeWithText("Last seen: not reported").assertExists()
        compose.onNodeWithText("Applied policy", substring = true).assertDoesNotExist()
    }
}
