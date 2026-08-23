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

    /**
     * 現在待っている画面または処理段階。
     */
    private enum class AutomationPhase {
        /*
         * SSBPro起動直後。
         *
         * 前回表示していた画面が残っている可能性もあるため、
         * 現在の既知画面を判定する。
         */
        START,

        /*
         * SSBPro本体のログインボタンを押した後。
         *
         * 証明書警告、スマホ版画面、または
         * PC版へ進むためのブラウザー操作領域を待つ。
         */
        WAITING_AFTER_SSB_LOGIN,

        /*
         * 証明書警告のOKを押した後。
         *
         * スマホ版ログイン画面または
         * スマホ版ログイン済み画面を待つ。
         */
        WAITING_FOR_MOBILE_PAGE,

        /*
         * スマホ版ログインボタンを押した後。
         *
         * ログインダイアログが消えるのを待つ。
         */
        WAITING_FOR_MOBILE_HOME,

        /*
         * PC版を選んだ場合、スマホ版サイトへはログインせず、
         * SSBPro本体のブックマークボタンが利用可能になるのを待つ。
         */
        WAITING_FOR_BROWSER_CHROME,

        /*
         * SSBProのブックマークボタンを押した後。
         *
         * 「共通ブックマーク」を待つ。
         */
        WAITING_FOR_BOOKMARK_ROOT,

        /*
         * 「共通ブックマーク」を押した後。
         *
         * 「新Desknets(PC版)」を待つ。
         */
        WAITING_FOR_PC_BOOKMARK,

        /*
         * 「新Desknets(PC版)」を押した後。
         *
         * PC版ログイン画面を待つ。
         */
        WAITING_FOR_PC_LOGIN,

        /*
         * PC版ログインボタンを押した後。
         *
         * dn-h-usernameが表示されるのを待つ。
         */
        WAITING_FOR_PC_PORTAL
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
         * PC版ログイン成功を最優先で判定する。
         */
        if (isPcPortalDisplayed(root)) {
            if (
                automationTarget ==
                AutomationTarget.PC
            ) {
                completeSession(
                    "PC版ログインが完了しました。"
                )
            } else {
                /*
                 * スマホ版を選んだのに、既にPC版へ
                 * ログイン済みだった場合は何も操作せず終了する。
                 */
                completeSession(
                    "PC版ログイン済み画面を確認しました。"
                )
            }

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

            isSecurityWarning(root) -> {
                clickSecurityWarning(root)
            }

            automationTarget ==
                    AutomationTarget.PC &&
                    !isPcLoginPage(root) &&
                    hasBookmarkButton(root) -> {
                openBookmark(root)
            }

            isMobileLoginPage(root) -> {
                handleMobileLoginPage(root)
            }

            findFirstByExactText(
                root,
                "共通ブックマーク"
            ) != null -> {
                if (
                    automationTarget ==
                    AutomationTarget.PC
                ) {
                    clickCommonBookmark(root)
                } else {
                    failSession(
                        "スマホ版を選択していますが、" +
                                "ブックマーク画面が表示されています。"
                    )
                }
            }

            findFirstByExactText(
                root,
                "新Desknets(PC版)"
            ) != null -> {
                if (
                    automationTarget ==
                    AutomationTarget.PC
                ) {
                    clickPcBookmark(root)
                } else {
                    failSession(
                        "スマホ版を選択していますが、" +
                                "PC版ブックマーク画面が表示されています。"
                    )
                }
            }

            isPcLoginPage(root) -> {
                if (
                    automationTarget ==
                    AutomationTarget.PC
                ) {
                    performPcLogin(root)
                } else {
                    failSession(
                        "スマホ版を選択していますが、" +
                                "PC版ログイン画面が表示されています。"
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
            isSecurityWarning(root) -> {
                clickSecurityWarning(root)
            }

            automationTarget ==
                    AutomationTarget.PC -> {
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

            AutomationTarget.PC -> {
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

        setPhase(
            when (automationTarget) {
                AutomationTarget.PC ->
                    AutomationPhase
                        .WAITING_FOR_BROWSER_CHROME

                AutomationTarget.MOBILE,
                null ->
                    AutomationPhase
                        .WAITING_FOR_MOBILE_PAGE
            }
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
     * XML上ではスマホ版本体のlogin-pageが存在し、
     * ログインダイアログのd-logindialog-pageが消える。
     */
    private fun isMobileHomeDisplayed(
        root: AccessibilityNodeInfo
    ): Boolean {
        val mobilePageExists =
            findFirstByViewId(
                root,
                "login-page"
            ) != null

        val loginDialogExists =
            findFirstByViewId(
                root,
                "d-logindialog-page"
            ) != null

        return (
                mobilePageExists &&
                        !loginDialogExists
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

            AutomationTarget.PC -> {
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
            AutomationPhase
                .WAITING_FOR_PC_BOOKMARK
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
