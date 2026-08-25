package com.shiva9ro.ssbloginhelper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 現在待っている画面または処理段階。
 */
internal enum class AutomationPhase {
    START,
    WAITING_AFTER_SSB_LOGIN,
    WAITING_FOR_MOBILE_PAGE,
    WAITING_FOR_MOBILE_HOME,
    WAITING_FOR_MOBILE_BOOKMARK,
    WAITING_FOR_BROWSER_CHROME,
    WAITING_FOR_BOOKMARK_ROOT,
    WAITING_FOR_PC_BOOKMARK,
    WAITING_FOR_PC_LOGIN,
    WAITING_FOR_PC_PORTAL,
    WAITING_FOR_MAIL_BOOKMARK,
    WAITING_FOR_MAIL_LOGIN,
    WAITING_FOR_MAIL_INBOX
}

/**
 * 証明書警告を閉じた後に再開する処理段階を返す。
 *
 * SSBPro本体ログイン直後の警告だけは、選択された目標に応じて
 * 次の段階へ進む。それ以外は、警告が割り込んだ段階から再開する。
 */
internal fun phaseAfterSecurityWarning(
    currentPhase: AutomationPhase,
    target: AutomationTarget?
): AutomationPhase {
    return when (currentPhase) {
        AutomationPhase.START,
        AutomationPhase.WAITING_AFTER_SSB_LOGIN -> {
            when (target) {
                AutomationTarget.PC,
                AutomationTarget.MAIL ->
                    AutomationPhase.WAITING_FOR_BROWSER_CHROME

                AutomationTarget.MOBILE,
                null ->
                    AutomationPhase.WAITING_FOR_MOBILE_PAGE
            }
        }

        else -> currentPhase
    }
}

/**
 * Helperアプリから開始された1回の自動化セッションを処理する。
 *
 * セッション完了後または失敗後は、
 * Helperアプリから新しい開始要求が来るまで何もしない。
 */
class SSBAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG =
            "SSBLogin"

        private const val SSB_PACKAGE =
            "jp.co.soliton.securebrowserpro"

        /*
         * アクセシビリティイベント発生後、
         * WebViewの表示が落ち着くまで待つ時間。
         */
        private const val INSPECTION_DELAY_MS =
            400L

        /*
         * SSBPro本体の認証情報入力後、
         * ログインボタンを押すまでの時間。
         */
        private const val SSB_LOGIN_CLICK_DELAY_MS =
            350L

        /*
         * スマホ版の認証情報入力後、
         * ログインボタンを押すまでの時間。
         */
        private const val MOBILE_LOGIN_CLICK_DELAY_MS =
            500L

        /*
         * PC版の認証情報入力後、
         * ログインボタンを押すまでの時間。
         */
        private const val PC_LOGIN_CLICK_DELAY_MS =
            800L

        /*
         * 各段階の待機上限。
         *
         * 同じ操作を再試行するための時間ではなく、
         * 画面遷移が完了しなかった場合に
         * セッションを失敗終了させるための時間。
         */
        private const val START_TIMEOUT_MS =
            20_000L

        private const val SSB_TRANSITION_TIMEOUT_MS =
            20_000L

        private const val MOBILE_PAGE_TIMEOUT_MS =
            20_000L

        private const val MOBILE_LOGIN_TIMEOUT_MS =
            20_000L

        private const val BOOKMARK_TIMEOUT_MS =
            12_000L

        private const val PC_LOGIN_TIMEOUT_MS =
            20_000L

        private const val PC_PORTAL_TIMEOUT_MS =
            25_000L
    }

    /**
     * 自動化セッション全体の状態。
     */
    private enum class SessionState {
        /*
         * Helperアプリからの開始要求を待っている。
         */
        IDLE,

        /*
         * 自動化を実行中。
         */
        RUNNING,

        /*
         * 目的画面へのログインが完了した。
         *
         * この状態では、ブックマーク画面などを
         * 手動で開いても自動操作しない。
         */
        COMPLETED,

        /*
         * 操作失敗またはタイムアウトで停止した。
         *
         * 自動再試行はしない。
         */
        FAILED
    }

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private lateinit var credentialStore:
            CredentialStore

    private var sessionState =
        SessionState.IDLE

    private var automationPhase =
        AutomationPhase.START

    private var automationTarget:
            AutomationTarget? = null

    private var phaseStartedAt =
        0L

    private var inspectionScheduled =
        false

    private var actionInProgress =
        false

    override fun onServiceConnected() {
        super.onServiceConnected()

        credentialStore =
            CredentialStore(this)

        sessionState =
            SessionState.IDLE

        automationPhase =
            AutomationPhase.START

        automationTarget = null
        phaseStartedAt = 0L
        inspectionScheduled = false
        actionInProgress = false

        Log.i(
            TAG,
            "AccessibilityService connected"
        )
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        if (event == null) {
            return
        }

        val eventPackage =
            event.packageName
                ?.toString()
                ?: return

        /*
         * accessibility_service_config.xmlでも
         * SSBProに限定しているが、念のため再確認する。
         */
        if (eventPackage != SSB_PACKAGE) {
            return
        }

        /*
         * Helperアプリの
         * 「スマホ版を開く」または「PC版を開く」から
         * 新しい開始要求が来ていれば、新規セッションを開始する。
         */
        val requestedTarget =
            AutomationControl
                .consumeAutomationRequest(
                    this
                )

        if (requestedTarget != null) {
            startNewSession(
                requestedTarget
            )
        }

        /*
         * 完了後・失敗後・開始前は、
         * SSBPro内で画面が変わっても何もしない。
         */
        if (
            sessionState !=
            SessionState.RUNNING
        ) {
            return
        }

        scheduleInspection()
    }

    override fun onInterrupt() {
        Log.w(
            TAG,
            "AccessibilityService interrupted"
        )
    }

    /**
     * 新しい1回分の自動化を開始する。
     */
    private fun startNewSession(
        target: AutomationTarget
    ) {
        /*
         * 前回セッションで予約された遅延処理を破棄する。
         */
        handler.removeCallbacksAndMessages(
            null
        )

        inspectionScheduled = false
        actionInProgress = false

        automationTarget = target

        sessionState =
            SessionState.RUNNING

        setPhase(
            AutomationPhase.START
        )

        Log.i(
            TAG,
            "Automation session started: " +
                    "target=$target"
        )

        scheduleInspection(
            delayMillis = 100L
        )
    }

    /**
     * 少し待ってから現在画面を確認する。
     */
    private fun scheduleInspection(
        delayMillis: Long =
            INSPECTION_DELAY_MS
    ) {
        if (
            sessionState !=
            SessionState.RUNNING
        ) {
            return
        }

        if (
            inspectionScheduled ||
            actionInProgress
        ) {
            return
        }

        inspectionScheduled = true

        handler.postDelayed(
            {
                inspectionScheduled = false
                inspectCurrentScreen()
            },
            delayMillis
        )
    }

    /**
     * 現在の画面と処理段階を照合する。
     */
    private fun inspectCurrentScreen() {
        if (
            sessionState !=
            SessionState.RUNNING
        ) {
            return
        }

        if (actionInProgress) {
            return
        }

        /*
         * 同じ段階で一定時間以上進まなければ、
         * 操作を再実行せず失敗終了する。
         */
        if (isCurrentPhaseTimedOut()) {
            failSession(
                "画面遷移が制限時間内に完了しませんでした。" +
                        " phase=$automationPhase"
            )

            return
        }

        val root =
            rootInActiveWindow

        /*
         * 一時的に画面ツリーが取得できない場合は、
         * 直ちに失敗にせず同じ段階で待つ。
         */
        if (root == null) {
            scheduleInspection()
            return
        }

        if (
            root.packageName
                ?.toString() !=
            SSB_PACKAGE
        ) {
            scheduleInspection()
            return
        }

        /*
         * メールログイン成功を最優先で判定する。
         */
        if (
            automationTarget == AutomationTarget.MAIL &&
            isMailInboxDisplayed(root)
        ) {
            completeSession(
                "メールログインが完了しました。"
            )
            return
        }

        /*
         * スマホ版が既にログイン済みなら、そのまま完了する。
         */
        if (
            automationTarget == AutomationTarget.MOBILE &&
            isMobileHomeDisplayed(root)
        ) {
            completeSession(
                "スマホ版ログイン済み画面を確認しました。"
            )
            return
        }

        /*
         * PC版ログイン成功を判定する。
         */
        if (isPcPortalDisplayed(root)) {
            when (automationTarget) {
                AutomationTarget.PC -> {
                    completeSession(
                        "PC版ログインが完了しました。"
                    )
                }

                AutomationTarget.MOBILE,
                AutomationTarget.MAIL -> {
                    openBookmark(root)
                }

                null -> {
                    failSession(
                        "自動化の目標画面が設定されていません。"
                    )
                }
            }

            return
        }

        /*
         * 証明書警告は画面遷移の途中ならどの段階でも現れ得る。
         * 各段階より先に判定し、PC版ブックマークを開いた後に
         * 表示された場合もOKを押せるようにする。
         */
        if (isSecurityWarning(root)) {
            clickSecurityWarning(root)
            return
        }

        when (automationPhase) {
            AutomationPhase.START -> {
                inspectStartPhase(root)
            }

            AutomationPhase
                .WAITING_AFTER_SSB_LOGIN -> {
                inspectAfterSsbLogin(root)
            }

            AutomationPhase
                .WAITING_FOR_MOBILE_PAGE -> {
                inspectMobilePage(root)
            }

            AutomationPhase
                .WAITING_FOR_MOBILE_HOME -> {
                inspectMobileHome(root)
            }

            AutomationPhase
                .WAITING_FOR_MOBILE_BOOKMARK -> {
                inspectMobileBookmark(root)
            }

            AutomationPhase
                .WAITING_FOR_BROWSER_CHROME -> {
                inspectBrowserChrome(root)
            }

            AutomationPhase
                .WAITING_FOR_BOOKMARK_ROOT -> {
                inspectBookmarkRoot(root)
            }

            AutomationPhase
                .WAITING_FOR_PC_BOOKMARK -> {
                inspectPcBookmark(root)
            }

            AutomationPhase
                .WAITING_FOR_PC_LOGIN -> {
                inspectPcLogin(root)
            }

            AutomationPhase
                .WAITING_FOR_PC_PORTAL -> {
                /*
                 * PC版ログインは既に1回実行済み。
                 *
                 * dn-h-usernameが現れるまで待ち、
                 * ログイン画面が残っていても再送信しない。
                 */
                scheduleInspection()
            }

            AutomationPhase
                .WAITING_FOR_MAIL_BOOKMARK -> {
                inspectMailBookmark(root)
            }

            AutomationPhase
                .WAITING_FOR_MAIL_LOGIN -> {
                inspectMailLogin(root)
            }

            AutomationPhase
                .WAITING_FOR_MAIL_INBOX -> {
                scheduleInspection()
            }
        }
    }

    /**
     * セッション開始直後。
     *
     * SSBProが前回終了時の画面を保持している場合も考慮し、
     * 現在表示中の既知画面から処理を開始する。
     */
    private fun inspectStartPhase(
        root: AccessibilityNodeInfo
    ) {
        when {
            isSsbLoginScreen(root) -> {
                performSsbLogin(root)
            }

            !isMobileLoginPage(root) &&
                    !isPcLoginPage(root) &&
                    !isMailLoginPage(root) &&
                    hasBookmarkButton(root) -> {
                openBookmark(root)
            }

            isMobileLoginPage(root) -> {
                handleMobileLoginPage(root)
            }

            findFirstByExactText(
                root,
                "共通ブックマーク"
            ) != null &&
                    findFirstByExactText(
                        root,
                        "新Desknets(PC版)"
                    ) == null &&
                    findFirstByExactText(
                        root,
                        "新Desknets（スマホ版)"
                    ) == null &&
                    findFirstByExactText(
                        root,
                        "事務処理用PCメール"
                    ) == null -> {
                if (
                    automationTarget != null
                ) {
                    clickCommonBookmark(root)
                } else {
                    failSession(
                        "スマホ版を選択していますが、" +
                                "ブックマーク画面が表示されています。"
                    )
                }
            }

            isCommonBookmarkDestinationList(root) -> {
                openSelectedBookmark(root)
            }

            isPcLoginPage(root) -> {
                when (automationTarget) {
                    AutomationTarget.PC ->
                        performPcLogin(root)

                    AutomationTarget.MOBILE,
                    AutomationTarget.MAIL ->
                        openBookmark(root)

                    null ->
                        failSession(
                            "自動化の目標画面が設定されていません。"
                        )
                }
            }

            isMailLoginPage(root) -> {
                when (automationTarget) {
                    AutomationTarget.MAIL ->
                        performMailLogin(root)

                    AutomationTarget.MOBILE,
                    AutomationTarget.PC ->
                        openBookmark(root)

                    null ->
                        failSession(
                            "自動化の目標画面が設定されていません。"
                        )
                }
            }

            else -> {
                scheduleInspection()
            }
        }
    }

    /**
     * SSBPro本体ログイン後。
     *
     * 証明書警告が出る場合と、
     * 直接スマホ版画面へ進む場合の両方を許容する。
     */
    private fun inspectAfterSsbLogin(
        root: AccessibilityNodeInfo
    ) {
        when {
            automationTarget !=
                    AutomationTarget.MOBILE -> {
                inspectBrowserChrome(root)
            }

            isMobileLoginPage(root) -> {
                handleMobileLoginPage(root)
            }

            else -> {
                scheduleInspection()
            }
        }
    }

    /**
     * 証明書警告後、スマホ版画面を待つ。
     */
    private fun inspectMobilePage(
        root: AccessibilityNodeInfo
    ) {
        if (isMobileLoginPage(root)) {
            handleMobileLoginPage(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * PC版へ進むため、SSBPro本体のブラウザー操作領域を待つ。
     * 表示中のWebサイトがスマホ版、エラー画面、ログイン済み画面の
     * いずれであっても、サイト内容には依存しない。
     */
    private fun inspectBrowserChrome(
        root: AccessibilityNodeInfo
    ) {
        if (hasBookmarkButton(root)) {
            openBookmark(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * スマホ版ログインボタンを押した後、
     * ログインダイアログが消えるのを待つ。
     *
     * スマホ版が目標ならここで完了する。
     * PC版が目標ならブックマーク処理へ続ける。
     */
    /**
     * スマホ版ログインボタンを押した後、
     * ログインダイアログが消えるのを待つ。
     *
     * スマホ版が目標なら完了し、
     * PC版が目標ならブックマーク処理へ続ける。
     */
    private fun inspectMobileHome(
        root: AccessibilityNodeInfo
    ) {
        val loginDialogStillExists =
            findFirstByViewId(
                root,
                "d-logindialog-page"
            ) != null

        if (loginDialogStillExists) {
            /*
             * ログイン処理中または認証失敗で
             * ダイアログが残っている。
             *
             * ログイン操作は再実行せず待機する。
             */
            scheduleInspection()
            return
        }

        /*
         * ログインボタンを押した後に
         * ログインダイアログが消えたため、
         * スマホ版ログイン成功と判断する。
         */
        when (automationTarget) {
            AutomationTarget.MOBILE -> {
                completeSession(
                    "スマホ版ログインが完了しました。"
                )
            }

            AutomationTarget.PC,
            AutomationTarget.MAIL -> {
                openBookmark(root)
            }

            null -> {
                failSession(
                    "自動化の目標画面が設定されていません。"
                )
            }
        }
    }

    /**
     * ブックマーク一覧の先頭階層を待つ。
     */
    private fun inspectBookmarkRoot(
        root: AccessibilityNodeInfo
    ) {
        if (
            findFirstByExactText(
                root,
                "共通ブックマーク"
            ) != null
        ) {
            clickCommonBookmark(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * 共通ブックマーク内に、今回選択された項目が表示されているか。
     */
    private fun isCommonBookmarkDestinationList(
        root: AccessibilityNodeInfo
    ): Boolean {
        val targetText =
            when (automationTarget) {
                AutomationTarget.MOBILE ->
                    "新Desknets（スマホ版)"

                AutomationTarget.PC ->
                    "新Desknets(PC版)"

                AutomationTarget.MAIL ->
                    "事務処理用PCメール"

                null -> return false
            }

        return findFirstByExactText(
            root,
            targetText
        ) != null
    }

    /**
     * 共通ブックマークから、今回選択された項目を開く。
     */
    private fun openSelectedBookmark(
        root: AccessibilityNodeInfo
    ) {
        when (automationTarget) {
            AutomationTarget.MOBILE ->
                clickMobileBookmark(root)

            AutomationTarget.PC ->
                clickPcBookmark(root)

            AutomationTarget.MAIL ->
                clickMailBookmark(root)

            null ->
                failSession(
                    "自動化の目標画面が設定されていません。"
                )
        }
    }

    /**
     * 共通ブックマーク内のPC版リンクを待つ。
     */
    private fun inspectPcBookmark(
        root: AccessibilityNodeInfo
    ) {
        if (
            findFirstByExactText(
                root,
                "新Desknets(PC版)"
            ) != null
        ) {
            clickPcBookmark(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * 共通ブックマーク内のスマホ版リンクを待つ。
     */
    private fun inspectMobileBookmark(
        root: AccessibilityNodeInfo
    ) {
        if (
            findFirstByExactText(
                root,
                "新Desknets（スマホ版)"
            ) != null
        ) {
            clickMobileBookmark(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * 共通ブックマーク内のメールリンクを待つ。
     */
    private fun inspectMailBookmark(
        root: AccessibilityNodeInfo
    ) {
        if (
            findFirstByExactText(
                root,
                "事務処理用PCメール"
            ) != null
        ) {
            clickMailBookmark(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * PC版ログイン画面を待つ。
     */
    private fun inspectPcLogin(
        root: AccessibilityNodeInfo
    ) {
        if (isPcLoginPage(root)) {
            performPcLogin(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * メールログイン画面を待つ。
     */
    private fun inspectMailLogin(
        root: AccessibilityNodeInfo
    ) {
        if (isMailLoginPage(root)) {
            performMailLogin(root)
        } else {
            scheduleInspection()
        }
    }

    /**
     * SSBPro本体のログイン画面か。
     */
    private fun isSsbLoginScreen(
        root: AccessibilityNodeInfo
    ): Boolean {
        return (
                findFirstByViewId(
                    root,
                    "jp.co.soliton.securebrowserpro:id/username"
                ) != null &&
                        findFirstByViewId(
                            root,
                            "jp.co.soliton.securebrowserpro:id/password"
                        ) != null &&
                        findFirstByViewId(
                            root,
                            "jp.co.soliton.securebrowserpro:id/okButton"
                        ) != null
                )
    }

    /**
     * SSBPro本体のログインを1回実行する。
     */
    private fun performSsbLogin(
        root: AccessibilityNodeInfo
    ) {
        val credentials =
            credentialStore.load()

        if (credentials == null) {
            failSession(
                "認証情報を読み込めませんでした。"
            )

            return
        }

        val usernameNode =
            findFirstByViewId(
                root,
                "jp.co.soliton.securebrowserpro:id/username"
            )

        val passwordNode =
            findFirstByViewId(
                root,
                "jp.co.soliton.securebrowserpro:id/password"
            )

        if (
            usernameNode == null ||
            passwordNode == null
        ) {
            failSession(
                "SSBProの入力欄を取得できませんでした。"
            )

            return
        }

        val usernameSet =
            setText(
                usernameNode,
                credentials.loginId
            )

        val passwordSet =
            setText(
                passwordNode,
                credentials.password
            )

        if (
            !usernameSet ||
            !passwordSet
        ) {
            failSession(
                "SSBProの認証情報を入力できませんでした。"
            )

            return
        }

        actionInProgress = true

        handler.postDelayed(
            {
                val currentRoot =
                    rootInActiveWindow

                if (
                    currentRoot == null ||
                    currentRoot.packageName
                        ?.toString() !=
                    SSB_PACKAGE
                ) {
                    failSession(
                        "SSBProのログイン画面を再取得できませんでした。"
                    )

                    return@postDelayed
                }

                val currentButton =
                    findFirstByViewId(
                        currentRoot,
                        "jp.co.soliton.securebrowserpro:id/okButton"
                    )

                if (currentButton == null) {
                    failSession(
                        "SSBProのログインボタンを取得できませんでした。"
                    )

                    return@postDelayed
                }

                if (!clickNode(currentButton)) {
                    failSession(
                        "SSBProのログインボタンを押せませんでした。"
                    )

                    return@postDelayed
                }

                actionInProgress = false

                setPhase(
                    AutomationPhase
                        .WAITING_AFTER_SSB_LOGIN
                )

                scheduleInspection(
                    delayMillis = 800L
                )
            },
            SSB_LOGIN_CLICK_DELAY_MS
        )
    }

    /**
     * サーバー証明書警告か。
     */
    private fun isSecurityWarning(
        root: AccessibilityNodeInfo
    ): Boolean {
        val message =
            findFirstByViewId(
                root,
                "android:id/message"
            )
                ?.text
                ?.toString()
                .orEmpty()

        val buttonExists =
            findFirstByViewId(
                root,
                "android:id/button1"
            ) != null

        return buttonExists &&
                (
                        message.contains("証明書") ||
                                message.contains(
                                    "サーバーに接続しますか"
                                )
                        )
    }

    /**
     * サーバー証明書警告のOKを1回押す。
     */
    private fun clickSecurityWarning(
        root: AccessibilityNodeInfo
    ) {
        val phaseBeforeWarning =
            automationPhase

        val button =
            findFirstByViewId(
                root,
                "android:id/button1"
            )

        if (
            button == null ||
            !clickNode(button)
        ) {
            failSession(
                "証明書警告のOKを押せませんでした。"
            )

            return
        }

        val resumePhase =
            phaseAfterSecurityWarning(
                currentPhase = phaseBeforeWarning,
                target = automationTarget
            )

        /*
         * 同じ警告が画面に残った場合にタイムアウト計測を
         * 毎回リセットしないよう、段階が変わる場合だけ更新する。
         */
        if (resumePhase != phaseBeforeWarning) {
            setPhase(resumePhase)
        }

        Log.i(
            TAG,
            "Security warning OK clicked: " +
                    "phase=$phaseBeforeWarning, " +
                    "resumePhase=$resumePhase"
        )

        scheduleInspection(
            delayMillis = 800L
        )
    }

    /**
     * スマホ版ログイン画面か。
     *
     * ログインダイアログと2つの入力欄があれば、
     * 入力処理を開始できると判定する。
     *
     * ログインボタンは入力後に現在の画面から再取得するため、
     * この時点では判定条件に含めない。
     */
    private fun isMobileLoginPage(
        root: AccessibilityNodeInfo
    ): Boolean {
        val dialog =
            findFirstByViewId(
                root,
                "d-logindialog-page"
            ) ?: return false

        val usernameNode =
            findFirstByViewId(
                dialog,
                "UserID"
            )

        val passwordNode =
            findFirstByViewId(
                dialog,
                "password"
            )

        return (
                usernameNode != null &&
                        passwordNode != null
                )
    }

    /**
     * スマホ版へログイン済みか。
     *
     * XML上ではログイン後だけportal-pageが存在する。
     * 読み込み途中の誤判定を避けるため、login-pageの消失だけでは
     * ログイン成功と判断しない。
     */
    private fun isMobileHomeDisplayed(
        root: AccessibilityNodeInfo
    ): Boolean {
        val portalPageExists =
            findFirstByViewId(
                root,
                "portal-page"
            ) != null

        val loginDialogExists =
            findFirstByViewId(
                root,
                "d-logindialog-page"
            ) != null

        val pcPageExists =
            findFirstByViewId(
                root,
                "dn-h-username"
            ) != null ||
                    findFirstByViewId(
                        root,
                        "login-input"
                    ) != null

        val mailPageExists =
            findFirstByViewId(
                root,
                "loginbox"
            ) != null ||
                    findFirstByViewId(
                        root,
                        "maillist"
                    ) != null

        return (
                portalPageExists &&
                        !loginDialogExists &&
                        !pcPageExists &&
                        !mailPageExists
                )
    }

    /**
     * スマホ版ログイン画面へ到着したときの処理。
     *
     * スマホ版が目標ならログインする。
     * PC版が目標ならスマホ版へはログインせず、
     * SSBPro本体のブックマークからPC版へ進む。
     */
    private fun handleMobileLoginPage(
        root: AccessibilityNodeInfo
    ) {
        when (automationTarget) {
            AutomationTarget.MOBILE -> {
                performMobileLogin(root)
            }

            AutomationTarget.PC,
            AutomationTarget.MAIL -> {
                if (hasBookmarkButton(root)) {
                    openBookmark(root)
                } else {
                    setPhase(
                        AutomationPhase
                            .WAITING_FOR_BROWSER_CHROME
                    )
                    scheduleInspection()
                }
            }

            null -> {
                failSession(
                    "自動化の目標画面が設定されていません。"
                )
            }
        }
    }

    /**
     * スマホ版ログインを1回だけ実行する。
     */
    private fun performMobileLogin(
        root: AccessibilityNodeInfo
    ) {
        val credentials =
            credentialStore.load()

        if (credentials == null) {
            failSession(
                "認証情報を読み込めませんでした。"
            )
            return
        }

        val dialog =
            findFirstByViewId(
                root,
                "d-logindialog-page"
            )

        if (dialog == null) {
            failSession(
                "スマホ版ログイン画面を取得できませんでした。"
            )
            return
        }

        val usernameNode =
            findFirstByViewId(
                dialog,
                "UserID"
            )

        val passwordNode =
            findFirstByViewId(
                dialog,
                "password"
            )

        if (
            usernameNode == null ||
            passwordNode == null
        ) {
            failSession(
                "スマホ版の入力欄を取得できませんでした。"
            )
            return
        }

        val usernameSet =
            setText(
                usernameNode,
                credentials.loginId
            )

        val passwordSet =
            setText(
                passwordNode,
                credentials.password
            )

        Log.i(
            TAG,
            "Mobile credentials input: " +
                    "username=$usernameSet, " +
                    "password=$passwordSet"
        )

        if (
            !usernameSet ||
            !passwordSet
        ) {
            failSession(
                "スマホ版の認証情報を入力できませんでした。"
            )
            return
        }

        actionInProgress = true

        handler.postDelayed(
            {
                val currentRoot =
                    rootInActiveWindow

                if (
                    currentRoot == null ||
                    currentRoot.packageName
                        ?.toString() !=
                    SSB_PACKAGE
                ) {
                    failSession(
                        "スマホ版ログイン画面を再取得できませんでした。"
                    )
                    return@postDelayed
                }

                val currentDialog =
                    findFirstByViewId(
                        currentRoot,
                        "d-logindialog-page"
                    )

                val currentPasswordNode =
                    currentDialog?.let {
                        findFirstByViewId(
                            it,
                            "password"
                        )
                    }

                val currentLoginButton =
                    currentDialog?.let {
                        findFirstByViewId(
                            it,
                            "login-exec"
                        )
                    }

                if (
                    currentDialog == null ||
                    currentLoginButton == null
                ) {
                    failSession(
                        "スマホ版ログインボタンを再取得できませんでした。"
                    )
                    return@postDelayed
                }

                /*
                 * WebView側へ入力確定を通知しやすくする。
                 */
                currentPasswordNode?.performAction(
                    AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
                )

                setPhase(
                    AutomationPhase
                        .WAITING_FOR_MOBILE_HOME
                )

                val dispatched =
                    tapMobileLoginButton(
                        currentLoginButton
                    )

                if (!dispatched) {
                    failSession(
                        "スマホ版ログインボタンをタップできませんでした。"
                    )
                }
            },
            MOBILE_LOGIN_CLICK_DELAY_MS
        )
    }

    /**
     * SSBPro本体のブックマークボタンが利用可能か。
     */
    private fun hasBookmarkButton(
        root: AccessibilityNodeInfo
    ): Boolean {
        return findFirstByViewId(
            root,
            "jp.co.soliton.securebrowserpro:id/browser_bookmark"
        ) != null
    }

    /**
     * PC版へ進むため、
     * SSBPro本体のブックマークボタンを1回開く。
     */
    private fun openBookmark(
        root: AccessibilityNodeInfo
    ) {
        val bookmarkButton =
            findFirstByViewId(
                root,
                "jp.co.soliton.securebrowserpro:id/browser_bookmark"
            )

        if (
            bookmarkButton == null ||
            !clickNode(bookmarkButton)
        ) {
            failSession(
                "ブックマークボタンを押せませんでした。"
            )

            return
        }

        setPhase(
            AutomationPhase
                .WAITING_FOR_BOOKMARK_ROOT
        )

        scheduleInspection(
            delayMillis = 600L
        )
    }

    /**
     * 「共通ブックマーク」を1回押す。
     */
    private fun clickCommonBookmark(
        root: AccessibilityNodeInfo
    ) {
        val node =
            findFirstByExactText(
                root,
                "共通ブックマーク"
            )

        if (
            node == null ||
            !clickNode(node)
        ) {
            failSession(
                "共通ブックマークを開けませんでした。"
            )

            return
        }

        setPhase(
            when (automationTarget) {
                AutomationTarget.MAIL ->
                    AutomationPhase.WAITING_FOR_MAIL_BOOKMARK

                AutomationTarget.MOBILE ->
                    AutomationPhase.WAITING_FOR_MOBILE_BOOKMARK

                AutomationTarget.PC,
                null ->
                    AutomationPhase.WAITING_FOR_PC_BOOKMARK
            }
        )

        scheduleInspection(
            delayMillis = 600L
        )
    }

    /**
     * 「新Desknets(PC版)」を1回押す。
     */
    private fun clickPcBookmark(
        root: AccessibilityNodeInfo
    ) {
        val node =
            findFirstByExactText(
                root,
                "新Desknets(PC版)"
            )

        if (
            node == null ||
            !clickNode(node)
        ) {
            failSession(
                "PC版ブックマークを開けませんでした。"
            )

            return
        }

        setPhase(
            AutomationPhase
                .WAITING_FOR_PC_LOGIN
        )

        scheduleInspection(
            delayMillis = 1_000L
        )
    }

    /**
     * 「新Desknets（スマホ版)」を1回押す。
     */
    private fun clickMobileBookmark(
        root: AccessibilityNodeInfo
    ) {
        val node =
            findFirstByExactText(
                root,
                "新Desknets（スマホ版)"
            )

        if (
            node == null ||
            !clickNode(node)
        ) {
            failSession(
                "スマホ版ブックマークを開けませんでした。"
            )
            return
        }

        setPhase(
            AutomationPhase.WAITING_FOR_MOBILE_PAGE
        )

        scheduleInspection(
            delayMillis = 1_000L
        )
    }

    /**
     * 「事務処理用PCメール」を1回押す。
     */
    private fun clickMailBookmark(
        root: AccessibilityNodeInfo
    ) {
        val node =
            findFirstByExactText(
                root,
                "事務処理用PCメール"
            )

        if (
            node == null ||
            !clickNode(node)
        ) {
            failSession(
                "メールのブックマークを開けませんでした。"
            )
            return
        }

        setPhase(
            AutomationPhase.WAITING_FOR_MAIL_LOGIN
        )

        scheduleInspection(
            delayMillis = 1_000L
        )
    }

    /**
     * PC版ログイン画面か。
     */
    private fun isPcLoginPage(
        root: AccessibilityNodeInfo
    ): Boolean {
        return (
                findFirstByViewId(
                    root,
                    "login-input"
                ) != null &&
                        findFirstByViewId(
                            root,
                            "login-btn"
                        ) != null
                )
    }

    /**
     * 事務処理用PCメールのログイン画面か。
     */
    private fun isMailLoginPage(
        root: AccessibilityNodeInfo
    ): Boolean {
        return (
                findFirstByViewId(root, "loginbox") != null &&
                        findFirstByViewId(root, "id") != null &&
                        findFirstByViewId(root, "pwd") != null &&
                        findFirstByExactText(root, "Login") != null
                )
    }

    /**
     * 事務処理用PCメールへ1回だけログインする。
     */
    private fun performMailLogin(
        root: AccessibilityNodeInfo
    ) {
        val credentials = credentialStore.load()

        if (credentials == null) {
            failSession(
                "認証情報を読み込めませんでした。"
            )
            return
        }

        val loginIdNode = findFirstByViewId(root, "id")
        val passwordNode = findFirstByViewId(root, "pwd")

        if (
            loginIdNode == null ||
            passwordNode == null
        ) {
            scheduleInspection()
            return
        }

        if (
            !setText(loginIdNode, credentials.loginId) ||
            !setText(passwordNode, credentials.password)
        ) {
            failSession(
                "メールの認証情報を入力できませんでした。"
            )
            return
        }

        actionInProgress = true

        handler.postDelayed(
            {
                val currentRoot = rootInActiveWindow

                if (
                    currentRoot == null ||
                    currentRoot.packageName?.toString() != SSB_PACKAGE
                ) {
                    failSession(
                        "メールログイン画面を再取得できませんでした。"
                    )
                    return@postDelayed
                }

                val loginButton =
                    findFirstByExactText(
                        currentRoot,
                        "Login"
                    )

                if (loginButton == null) {
                    failSession(
                        "メールのLoginボタンを再取得できませんでした。"
                    )
                    return@postDelayed
                }

                setPhase(
                    AutomationPhase.WAITING_FOR_MAIL_INBOX
                )

                if (!tapNodeCenter(loginButton)) {
                    failSession(
                        "メールのLoginボタンをタップできませんでした。"
                    )
                }
            },
            PC_LOGIN_CLICK_DELAY_MS
        )
    }

    /**
     * PC版ログインを1回実行する。
     */
    private fun performPcLogin(
        root: AccessibilityNodeInfo
    ) {
        val credentials =
            credentialStore.load()

        if (credentials == null) {
            failSession(
                "認証情報を読み込めませんでした。"
            )

            return
        }

        val loginContainer =
            findFirstByViewId(
                root,
                "login-input"
            )

        if (loginContainer == null) {
            failSession(
                "PC版ログイン欄を取得できませんでした。"
            )

            return
        }

        val editableNodes =
            mutableListOf<
                    AccessibilityNodeInfo
                    >()

        collectEditableNodes(
            loginContainer,
            editableNodes
        )

        if (editableNodes.size < 2) {
            /*
             * WebViewが入力欄を構築中の場合は、
             * 直ちに失敗せず同じ段階で待つ。
             */
            scheduleInspection()
            return
        }

        val usernameSet =
            setText(
                editableNodes[0],
                credentials.loginId
            )

        val passwordSet =
            setText(
                editableNodes[1],
                credentials.password
            )

        if (
            !usernameSet ||
            !passwordSet
        ) {
            failSession(
                "PC版の認証情報を入力できませんでした。"
            )

            return
        }

        actionInProgress = true

        handler.postDelayed(
            {
                /*
                 * 待機中にWebViewが再描画される可能性があるため、
                 * 現在の画面ツリーからボタンを再取得する。
                 */
                val currentRoot =
                    rootInActiveWindow

                if (
                    currentRoot == null ||
                    currentRoot.packageName
                        ?.toString() !=
                    SSB_PACKAGE
                ) {
                    failSession(
                        "PC版ログイン画面を再取得できませんでした。"
                    )

                    return@postDelayed
                }

                val currentLoginContainer =
                    findFirstByViewId(
                        currentRoot,
                        "login-input"
                    )

                val currentLoginButton =
                    findFirstByViewId(
                        currentRoot,
                        "login-btn"
                    )

                if (
                    currentLoginContainer == null ||
                    currentLoginButton == null
                ) {
                    failSession(
                        "PC版ログインボタンを再取得できませんでした。"
                    )

                    return@postDelayed
                }

                /*
                 * タップ前に待機段階を変更し、
                 * ログイン画面が残っても再送信しない。
                 */
                setPhase(
                    AutomationPhase
                        .WAITING_FOR_PC_PORTAL
                )

                val dispatched =
                    tapNodeCenter(
                        currentLoginButton
                    )

                if (!dispatched) {
                    failSession(
                        "PC版ログインボタンをタップできませんでした。"
                    )
                }
            },
            PC_LOGIN_CLICK_DELAY_MS
        )
    }

    /**
     * PC版へログイン済みか。
     *
     * PC版ログインフォームが存在せず、
     * ログイン後のユーザー名領域が存在すれば
     * ログイン済みと判定する。
     */
    private fun isPcPortalDisplayed(
        root: AccessibilityNodeInfo
    ): Boolean {
        if (
            findFirstByViewId(
                root,
                "login-input"
            ) != null
        ) {
            return false
        }

        return findFirstByViewId(
            root,
            "dn-h-username"
        ) != null
    }

    /**
     * 事務処理用PCメールの受信トレイが表示されているか。
     * メール件名や利用者情報には依存せず、固定IDだけで判定する。
     */
    private fun isMailInboxDisplayed(
        root: AccessibilityNodeInfo
    ): Boolean {
        if (
            findFirstByViewId(root, "id") != null ||
            findFirstByViewId(root, "pwd") != null
        ) {
            return false
        }

        return (
                findFirstByViewId(root, "page-folder2") != null &&
                        findFirstByViewId(root, "maillist") != null &&
                        findFirstByViewId(root, "main_menu") != null
                )
    }

    /**
     * 現在段階を変更し、
     * タイムアウト計測を開始する。
     */
    private fun setPhase(
        phase: AutomationPhase
    ) {
        automationPhase = phase

        phaseStartedAt =
            System.currentTimeMillis()

        Log.i(
            TAG,
            "Automation phase changed: $phase"
        )
    }

    /**
     * 現在段階の待機時間上限を超えたか。
     */
    private fun isCurrentPhaseTimedOut():
            Boolean {
        val timeoutMillis =
            when (automationPhase) {
                AutomationPhase.START ->
                    START_TIMEOUT_MS

                AutomationPhase
                    .WAITING_AFTER_SSB_LOGIN ->
                    SSB_TRANSITION_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_MOBILE_PAGE ->
                    MOBILE_PAGE_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_MOBILE_HOME ->
                    MOBILE_LOGIN_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_MOBILE_BOOKMARK ->
                    BOOKMARK_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_BROWSER_CHROME ->
                    MOBILE_PAGE_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_BOOKMARK_ROOT ->
                    BOOKMARK_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_PC_BOOKMARK ->
                    BOOKMARK_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_PC_LOGIN ->
                    PC_LOGIN_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_PC_PORTAL ->
                    PC_PORTAL_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_MAIL_BOOKMARK ->
                    BOOKMARK_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_MAIL_LOGIN ->
                    PC_LOGIN_TIMEOUT_MS

                AutomationPhase
                    .WAITING_FOR_MAIL_INBOX ->
                    PC_PORTAL_TIMEOUT_MS
            }

        return (
                System.currentTimeMillis() -
                        phaseStartedAt
                ) > timeoutMillis
    }

    /**
     * 自動化を正常終了する。
     */
    private fun completeSession(
        message: String
    ) {
        actionInProgress = false
        inspectionScheduled = false

        sessionState =
            SessionState.COMPLETED

        Log.i(
            TAG,
            "Automation completed: $message"
        )
    }

    /**
     * 自動化を失敗終了する。
     */
    private fun failSession(
        reason: String
    ) {
        actionInProgress = false
        inspectionScheduled = false

        sessionState =
            SessionState.FAILED

        Log.e(
            TAG,
            "Automation failed: $reason"
        )
    }

    /**
     * viewIdが一致する最初の要素を探す。
     */
    private fun findFirstByViewId(
        node: AccessibilityNodeInfo?,
        targetViewId: String
    ): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }

        if (
            node.viewIdResourceName ==
            targetViewId
        ) {
            return node
        }

        for (
        index in
        0 until node.childCount
        ) {
            val result =
                findFirstByViewId(
                    node.getChild(index),
                    targetViewId
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * 表示文字が完全一致する最初の要素を探す。
     */
    private fun findFirstByExactText(
        node: AccessibilityNodeInfo?,
        targetText: String
    ): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }

        if (
            node.text
                ?.toString() ==
            targetText
        ) {
            return node
        }

        for (
        index in
        0 until node.childCount
        ) {
            val result =
                findFirstByExactText(
                    node.getChild(index),
                    targetText
                )

            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * 編集可能要素をUIツリー順に収集する。
     */
    private fun collectEditableNodes(
        node: AccessibilityNodeInfo?,
        result:
        MutableList<
                AccessibilityNodeInfo
                >
    ) {
        if (node == null) {
            return
        }

        if (node.isEditable) {
            result.add(node)
        }

        for (
        index in
        0 until node.childCount
        ) {
            collectEditableNodes(
                node.getChild(index),
                result
            )
        }
    }

    /**
     * 入力欄へ文字列を設定する。
     */
    private fun setText(
        node: AccessibilityNodeInfo,
        value: String
    ): Boolean {
        if (!node.isEditable) {
            Log.w(
                TAG,
                "Node is not editable: " +
                        "class=${node.className}"
            )

            return false
        }

        val arguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo
                        .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value
                )
            }

        return node.performAction(
            AccessibilityNodeInfo
                .ACTION_SET_TEXT,
            arguments
        )
    }

    /**
     * 対象自身またはクリック可能な親要素を押す。
     */
    private fun clickNode(
        sourceNode: AccessibilityNodeInfo
    ): Boolean {
        var currentNode:
                AccessibilityNodeInfo? =
            sourceNode

        repeat(8) {
            val node =
                currentNode
                    ?: return false

            if (node.isClickable) {
                return node.performAction(
                    AccessibilityNodeInfo
                        .ACTION_CLICK
                )
            }

            currentNode =
                node.parent
        }

        return false
    }
    /**
     * スマホ版ログインボタン中央へ、
     * 実タップ相当のジェスチャーを送る。
     */
    private fun tapMobileLoginButton(
        node: AccessibilityNodeInfo
    ): Boolean {
        val bounds =
            Rect()

        node.getBoundsInScreen(
            bounds
        )

        if (bounds.isEmpty) {
            Log.w(
                TAG,
                "Cannot tap mobile login button: " +
                        "empty bounds"
            )

            return false
        }

        val centerX =
            bounds.centerX()
                .toFloat()

        val centerY =
            bounds.centerY()
                .toFloat()

        Log.i(
            TAG,
            "Dispatching mobile login tap: " +
                    "x=$centerX, y=$centerY"
        )

        val path =
            Path().apply {
                moveTo(
                    centerX,
                    centerY
                )
            }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0L,
                            100L
                        )
                )
                .build()

        return dispatchGesture(
            gesture,
            object :
                GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription:
                    GestureDescription?
                ) {
                    super.onCompleted(
                        gestureDescription
                    )

                    Log.i(
                        TAG,
                        "Mobile login tap completed"
                    )

                    actionInProgress = false

                    /*
                     * WAITING_FOR_MOBILE_HOMEのまま、
                     * ログインダイアログが消えたか確認する。
                     */
                    scheduleInspection(
                        delayMillis = 800L
                    )
                }

                override fun onCancelled(
                    gestureDescription:
                    GestureDescription?
                ) {
                    super.onCancelled(
                        gestureDescription
                    )

                    failSession(
                        "スマホ版ログインのタップ操作が取り消されました。"
                    )
                }
            },
            null
        )
    }


    /**
     * WebView内の要素中央へ
     * 実タップ相当のジェスチャーを送る。
     */
    private fun tapNodeCenter(
        node: AccessibilityNodeInfo
    ): Boolean {
        val bounds =
            Rect()

        node.getBoundsInScreen(
            bounds
        )

        if (bounds.isEmpty) {
            Log.w(
                TAG,
                "Cannot tap node: empty bounds"
            )

            return false
        }

        val centerX =
            bounds.centerX()
                .toFloat()

        val centerY =
            bounds.centerY()
                .toFloat()

        Log.i(
            TAG,
            "Dispatching tap gesture: " +
                    "x=$centerX, y=$centerY"
        )

        val path =
            Path().apply {
                moveTo(
                    centerX,
                    centerY
                )
            }

        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription
                        .StrokeDescription(
                            path,
                            0L,
                            100L
                        )
                )
                .build()

        return dispatchGesture(
            gesture,
            object :
                GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription:
                    GestureDescription?
                ) {
                    super.onCompleted(
                        gestureDescription
                    )

                    Log.i(
                        TAG,
                        "PC login tap completed"
                    )

                    actionInProgress = false

                    scheduleInspection(
                        delayMillis = 1_000L
                    )
                }

                override fun onCancelled(
                    gestureDescription:
                    GestureDescription?
                ) {
                    super.onCancelled(
                        gestureDescription
                    )

                    failSession(
                        "PC版ログインのタップ操作が取り消されました。"
                    )
                }
            },
            null
        )
    }
}
