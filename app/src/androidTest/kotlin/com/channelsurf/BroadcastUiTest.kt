package com.channelsurf

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level acceptance checks for broadcast mode. Runs against the real app with the bundled lineup
 * (no media), so it is deterministic and needs no network.
 */
@RunWith(AndroidJUnit4::class)
class BroadcastUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitForBoot() {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText("CHANNEL SURFER", substring = false).fetchSemanticsNodes().isEmpty()
        }
        // First run shows the archival-content notice.
        val ok = rule.onAllNodesWithText("OK").fetchSemanticsNodes()
        if (ok.isNotEmpty()) rule.onAllNodesWithText("OK")[0].performClick()
        rule.waitForIdle()
        // Emulators usually report a metered cellular network: accept the one-time mobile data notice.
        val cont = rule.onAllNodesWithText("CONTINUE").fetchSemanticsNodes()
        if (cont.isNotEmpty()) rule.onAllNodesWithText("CONTINUE")[0].performClick()
        rule.waitForIdle()
    }

    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        rule.waitForIdle()
    }

    @Test
    fun noTransportControlsExistAnywhereOnTheTvSurface() {
        waitForBoot()
        val forbidden = listOf("pause", "play", "seek", "rewind", "fast forward", "skip", "restart", "replay", "next episode", "previous episode", "scrub")
        forbidden.forEach { word ->
            val matcher = SemanticsMatcher("description or text mentions '$word'") { node ->
                val descs = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
                (descs + texts).any { Regex("\\b$word\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            }
            rule.onAllNodes(matcher, useUnmergedTree = true).assertCountEquals(0)
        }
        // No progress slider exists either (read-only progress is a plain box, not a ProgressBarRangeInfo/SetProgress).
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun emptyCatalogShowsSetupStateInsteadOfCrashing() {
        waitForBoot()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("WELCOME TO CHANNEL SURFER")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun guideOpensFromRemoteKeyAndBackDismissesIt() {
        waitForBoot()
        key(KeyEvent.KEYCODE_G)
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Close guide")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("ON NOW")).fetchSemanticsNodes().let { check(it.isNotEmpty()) { "guide should open at the current time" } }
        Espresso.pressBack()
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Close guide")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun mediaTransportKeysAreSwallowed() {
        waitForBoot()
        key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        rule.waitUntil(3_000) { rule.onAllNodes(hasText("LIVE TV", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun numericEntryTunesChannelAfterTimeout() {
        waitForBoot()
        key(KeyEvent.KEYCODE_0)
        key(KeyEvent.KEYCODE_5)
        // Commits after 1.5 s of inactivity; the banner then shows channel 05.
        rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Live television, channel 05", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun remoteChannelUpAndLastChannelWork() {
        waitForBoot()
        rule.onNodeWithContentDescription("Channel up").performClick()
        rule.waitForIdle()
        val afterUp = currentChannelDescription()
        rule.onNodeWithContentDescription("Previous channel").performClick()
        rule.waitForIdle()
        check(currentChannelDescription() != afterUp) { "LAST should return to the previous channel" }
    }

    private fun currentChannelDescription(): String =
        rule.onAllNodes(hasContentDescription("Live television, channel", substring = true)).fetchSemanticsNodes()
            .first().config[SemanticsProperties.ContentDescription].first()
}
