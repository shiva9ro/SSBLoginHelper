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
}
