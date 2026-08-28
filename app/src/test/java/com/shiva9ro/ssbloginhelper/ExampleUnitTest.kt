package com.shiva9ro.ssbloginhelper

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityWarningTransitionTest {
    @Test
    fun initialPcWarningContinuesToBrowserChrome() {
        assertEquals(
            AutomationPhase.WAITING_FOR_BROWSER_CHROME,
            phaseAfterSecurityWarning(
                currentPhase =
                AutomationPhase.WAITING_AFTER_SSB_LOGIN,
                target = AutomationTarget.PC
            )
        )
    }

    @Test
    fun pcBookmarkWarningResumesPcLoginWait() {
        assertEquals(
            AutomationPhase.WAITING_FOR_PC_LOGIN,
            phaseAfterSecurityWarning(
                currentPhase =
                AutomationPhase.WAITING_FOR_PC_LOGIN,
                target = AutomationTarget.PC
            )
        )
    }

    @Test
    fun initialMobileWarningContinuesToMobilePage() {
        assertEquals(
            AutomationPhase.WAITING_FOR_MOBILE_PAGE,
            phaseAfterSecurityWarning(
                currentPhase =
                AutomationPhase.WAITING_AFTER_SSB_LOGIN,
                target = AutomationTarget.MOBILE
            )
        )
    }

    @Test
    fun initialMailWarningContinuesToBrowserChrome() {
        assertEquals(
            AutomationPhase.WAITING_FOR_BROWSER_CHROME,
            phaseAfterSecurityWarning(
                currentPhase =
                AutomationPhase.WAITING_AFTER_SSB_LOGIN,
                target = AutomationTarget.MAIL
            )
        )
    }

    @Test
    fun warningIsClickedOncePerWindowState() {
        assertEquals(
            true,
            shouldClickSecurityWarning(
                windowStateVersion = 10L,
                clickedAtWindowStateVersion = 9L
            )
        )

        assertEquals(
            false,
            shouldClickSecurityWarning(
                windowStateVersion = 10L,
                clickedAtWindowStateVersion = 10L
            )
        )
    }

    @Test
    fun pcLoginWaitsForCompleteQuietPeriod() {
        assertEquals(
            false,
            hasQuietPeriodElapsed(
                now = 2_499L,
                lastChangeAt = 1_000L,
                quietPeriodMillis = 1_500L
            )
        )

        assertEquals(
            true,
            hasQuietPeriodElapsed(
                now = 2_500L,
                lastChangeAt = 1_000L,
                quietPeriodMillis = 1_500L
            )
        )
    }
}
