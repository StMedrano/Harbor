package dev.stmedrano.harbor.parent.checkin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.ui.ChildCheckInUi
import dev.stmedrano.harbor.parent.ui.ChildDashboard
import dev.stmedrano.harbor.parent.ui.CheckInScreen
import dev.stmedrano.harbor.parent.ui.HarborTheme
import dev.stmedrano.harbor.parent.ui.ParentCheckInScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class CheckInUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val id = "5b8d2c40-7e19-4a63-9f05-1c3e6a8b2d74"
    private fun ago(minutes: Long) = Instant.now().minusSeconds(minutes * 60).toString().replace(Regex("\\.\\d+Z$"), "Z")
    private fun ahead(hours: Long) = Instant.now().plusSeconds(hours * 3600).toString().replace(Regex("\\.\\d+Z$"), "Z")
    private val pending = CheckInPending(id, ago(5), ahead(23))
    private val recent = listOf(
        CheckInRecent(id, ago(60 * 26), ago(60 * 26 - 3), CheckInPreset.OK, located = true),
        CheckInRecent(id, ago(60 * 50), ago(60 * 50 - 2), CheckInPreset.LATE, located = false),
    )
    private fun childUi(state: CheckInUiState, answered: MutableList<CheckInPreset> = mutableListOf()) = ChildCheckInUi(state, { answered += it }, {})

    @Test fun childAnswersAnOpenRequestWithOnlyAPreset() {
        val answered = mutableListOf<CheckInPreset>()
        compose.setContent { HarborTheme { CheckInScreen(childUi(CheckInUiState(loaded = true, pending = pending), answered)) } }
        compose.onNodeWithText("A parent asked you to check in").assertExists()
        compose.onNodeWithText("I’m okay").performClick()
        compose.onNodeWithText("Running late").performClick()
        assertEquals(listOf(CheckInPreset.OK, CheckInPreset.LATE), answered)
        compose.onNodeWithText("SOS").assertDoesNotExist()
        compose.onNodeWithText("Emergency").assertDoesNotExist()
    }

    @Test fun nothingOpenOffersNoAnswerAndListsEarlierCheckIns() {
        compose.setContent { HarborTheme { CheckInScreen(childUi(CheckInUiState(loaded = true, recent = recent))) } }
        compose.onNodeWithText("Nothing to answer right now").assertExists()
        compose.onNodeWithText("I’m okay").assertDoesNotExist()
        compose.onNodeWithText("Earlier").assertExists()
        compose.onNodeWithText("Checked in, I’m okay").assertExists()
        compose.onNodeWithText("Checked in, running late").assertExists()
        compose.onNodeWithText("Place sent").assertExists()
    }

    @Test fun answeringIsDisabledWhileSendingAndProblemsAreExplained() {
        compose.setContent { HarborTheme { CheckInScreen(childUi(CheckInUiState(loaded = true, pending = pending, sending = true))) } }
        compose.onNodeWithText("I’m okay").assertIsNotEnabled()
        compose.onNodeWithText("Running late").assertIsNotEnabled()
        compose.onNodeWithText("Sending…").assertExists()
    }

    @Test fun failedAndEndedAnswersSaySoPlainly() {
        compose.setContent { HarborTheme { CheckInScreen(childUi(CheckInUiState(loaded = true, pending = pending, problem = CheckInProblem.COULD_NOT_SEND))) } }
        compose.onNodeWithText("Your answer was not sent. Check your connection and try again.").assertExists()
    }

    @Test fun anOpenRequestShowsOnTodayAndOpensTheCheckInTab() {
        var tab: String? = null
        compose.setContent {
            HarborTheme {
                ChildDashboard(ChildSyncState.Stale(1000), ChildBinding("device-a", "family-a", "child-a"), {}, {},
                    checkIn = childUi(CheckInUiState(loaded = true, pending = pending)), tab = "Today", onSelectTab = { tab = it })
            }
        }
        compose.onNodeWithText("A parent asked you to check in").assertExists()
        compose.onNodeWithText("Check in").performClick()
        assertEquals("Check in", tab)
    }

    @Test fun todayHasNoBannerWhenNothingIsOpen() {
        compose.setContent {
            HarborTheme {
                ChildDashboard(ChildSyncState.Stale(1000), ChildBinding("device-a", "family-a", "child-a"), {}, {},
                    checkIn = childUi(CheckInUiState(loaded = true)), tab = "Today")
            }
        }
        compose.onNodeWithText("A parent asked you to check in").assertDoesNotExist()
    }

    private fun entry(status: CheckInStatus, requestedMinAgo: Long, reply: CheckInEntryReply? = null) =
        CheckInEntry(id, ago(requestedMinAgo), ahead(23), status, reply)

    @Test fun parentCanAskWhenNothingIsOpenAndReadsTheLatestAnswer() {
        var asked = 0
        val reply = CheckInEntryReply(ago(90), CheckInPreset.LATE, CheckInFix(ago(95), 41.878114, -87.629798, 18))
        compose.setContent { HarborTheme { ParentCheckInScreen(CheckInViewState(CheckInViewStatus.READY, listOf(entry(CheckInStatus.ANSWERED, 100, reply))), "Maya") { asked++ } } }
        compose.onNodeWithText("Maya is running late").assertExists()
        compose.onNodeWithText("Ask Maya to check in", substring = false).performClick()
        assertEquals(1, asked)
    }

    @Test fun parentSeesWaitingInsteadOfAskingTwice() {
        compose.setContent { HarborTheme { ParentCheckInScreen(CheckInViewState(CheckInViewStatus.READY, listOf(entry(CheckInStatus.PENDING, 5))), "Maya") {} } }
        compose.onNodeWithText("Waiting for Maya", substring = true).assertExists()
        compose.onNodeWithText("Ask Maya to check in").assertDoesNotExist()
    }

    @Test fun parentWithoutAccessSeesNoPreviousAnswer() {
        compose.setContent { HarborTheme { ParentCheckInScreen(CheckInViewState(CheckInViewStatus.ACCESS_LOST), "Maya") {} } }
        compose.onNodeWithText("You no longer have access to this device's check-ins.").assertExists()
        compose.onNodeWithText("Maya is okay").assertDoesNotExist()
    }
}
