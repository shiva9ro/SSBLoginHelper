package com.shiva9ro.ssbloginhelper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shiva9ro.ssbloginhelper.ui.theme.SSBLoginHelperTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        /*
         * ChromeOSではFLAG_SECUREを設定したAndroidアプリで
         * 仮想キーボードが白く描画され、入力できなくなる場合がある。
         * Chromebookでは入力を優先し、それ以外の端末では従来どおり
         * スクリーンショットと最近使ったアプリ画面への表示を防ぐ。
         */
        if (!isChromeOsDevice()) {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }

        setContent {
            SSBLoginHelperTheme {
                MainScreen()
            }
        }
    }

    private fun isChromeOsDevice(): Boolean {
        return packageManager.hasSystemFeature(
            PackageManager.FEATURE_PC
        ) || packageManager.hasSystemFeature(
            "org.chromium.arc.device_management"
        )
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val credentialStore = remember {
        CredentialStore(context)
    }

    var storedCredentials by remember {
        mutableStateOf(
            credentialStore.load()
        )
    }

    var accessibilityEnabled by remember {
        mutableStateOf(
            isAccessibilityServiceEnabled(context)
        )
    }

    var showCredentialEditor by remember {
        mutableStateOf(
            storedCredentials == null
        )
    }

    var loginId by remember {
        mutableStateOf(
            storedCredentials
                ?.loginId
                .orEmpty()
        )
    }

    /*
     * 保存済みパスワードは画面へ復元しない。
     */
    var password by remember {
        mutableStateOf("")
    }

    var passwordVisible by remember {
        mutableStateOf(false)
    }

    var statusMessage by remember {
        mutableStateOf("")
    }

    /*
     * ユーザー補助設定画面などから戻ったとき、
     * サービス状態と認証情報を再取得する。
     */
    DisposableEffect(
        lifecycleOwner,
        context
    ) {
        val observer =
            LifecycleEventObserver {
                    _,
                    event ->

                if (event == Lifecycle.Event.ON_RESUME) {
                    storedCredentials =
                        credentialStore.load()

                    accessibilityEnabled =
                        isAccessibilityServiceEnabled(
                            context
                        )

                    if (!showCredentialEditor) {
                        loginId =
                            storedCredentials
                                ?.loginId
                                .orEmpty()
                    }
                }
            }

        lifecycleOwner.lifecycle.addObserver(
            observer
        )

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(
                observer
            )
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(20.dp)
                .fillMaxSize(),
            verticalArrangement =
                Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "SSB Login Helper",
                style =
                    MaterialTheme.typography.headlineMedium
            )

            when {
                /*
                 * 状態1：
                 * 認証情報が未登録。
                 */
                storedCredentials == null -> {
                    InitialSetupSection(
                        loginId = loginId,
                        onLoginIdChange = {
                            loginId = it
                        },
                        password = password,
                        onPasswordChange = {
                            password = it
                        },
                        passwordVisible =
                            passwordVisible,
                        onPasswordVisibilityChange = {
                            passwordVisible =
                                !passwordVisible
                        },
                        onSave = {
                            if (loginId.isBlank()) {
                                statusMessage =
                                    "ログインIDを入力してください。"

                                return@InitialSetupSection
                            }

                            if (password.isBlank()) {
                                statusMessage =
                                    "パスワードを入力してください。"

                                return@InitialSetupSection
                            }

                            try {
                                credentialStore.save(
                                    loginId =
                                        loginId.trim(),
                                    password =
                                        password
                                )

                                storedCredentials =
                                    credentialStore.load()

                                password = ""
                                showCredentialEditor = false

                                statusMessage =
                                    "認証情報を暗号化して保存しました。"
                            } catch (
                                exception: Exception
                            ) {
                                statusMessage =
                                    "認証情報の保存に失敗しました。"
                            }
                        }
                    )
                }

                /*
                 * 状態2：
                 * 認証情報は登録済みだが、
                 * ユーザー補助サービスが無効。
                 */
                !accessibilityEnabled -> {
                    AccessibilityRequiredSection(
                        onOpenSettings = {
                            context.startActivity(
                                Intent(
                                    Settings
                                        .ACTION_ACCESSIBILITY_SETTINGS
                                )
                            )
                        },
                        onRefresh = {
                            accessibilityEnabled =
                                isAccessibilityServiceEnabled(
                                    context
                                )

                            statusMessage =
                                if (accessibilityEnabled) {
                                    "ユーザー補助サービスが有効になりました。"
                                } else {
                                    "ユーザー補助サービスはまだ無効です。"
                                }
                        }
                    )

                    CredentialSettingsSection(
                        showEditor =
                            showCredentialEditor,
                        onShowEditorChange = {
                            showCredentialEditor = it

                            if (it) {
                                loginId =
                                    storedCredentials
                                        ?.loginId
                                        .orEmpty()

                                password = ""
                            }
                        },
                        loginId = loginId,
                        onLoginIdChange = {
                            loginId = it
                        },
                        password = password,
                        onPasswordChange = {
                            password = it
                        },
                        passwordVisible =
                            passwordVisible,
                        onPasswordVisibilityChange = {
                            passwordVisible =
                                !passwordVisible
                        },
                        onSave = {
                            val current =
                                credentialStore.load()

                            val passwordToSave =
                                when {
                                    password.isNotBlank() ->
                                        password

                                    current != null ->
                                        current.password

                                    else ->
                                        ""
                                }

                            if (loginId.isBlank()) {
                                statusMessage =
                                    "ログインIDを入力してください。"

                                return@CredentialSettingsSection
                            }

                            if (passwordToSave.isBlank()) {
                                statusMessage =
                                    "パスワードを入力してください。"

                                return@CredentialSettingsSection
                            }

                            try {
                                credentialStore.save(
                                    loginId =
                                        loginId.trim(),
                                    password =
                                        passwordToSave
                                )

                                storedCredentials =
                                    credentialStore.load()

                                password = ""
                                showCredentialEditor = false

                                statusMessage =
                                    "認証情報を更新しました。"
                            } catch (
                                exception: Exception
                            ) {
                                statusMessage =
                                    "認証情報の更新に失敗しました。"
                            }
                        },
                        onDelete = {
                            credentialStore.clear()

                            storedCredentials = null
                            loginId = ""
                            password = ""
                            showCredentialEditor = true

                            statusMessage =
                                "認証情報を削除しました。"
                        }
                    )
                }

                /*
                 * 状態3：
                 * 認証情報登録済み、
                 * ユーザー補助サービス有効。
                 */
                else -> {
                    ReadySection(
                        onLaunchMobile = {
                            launchSsbPro(
                                context = context,
                                target =
                                    AutomationTarget.MOBILE,
                                onError = {
                                    statusMessage = it
                                }
                            )
                        },
                        onLaunchPc = {
                            launchSsbPro(
                                context = context,
                                target =
                                    AutomationTarget.PC,
                                onError = {
                                    statusMessage = it
                                }
                            )
                        }
                    )

                    CredentialSettingsSection(
                        showEditor =
                            showCredentialEditor,
                        onShowEditorChange = {
                            showCredentialEditor = it

                            if (it) {
                                loginId =
                                    storedCredentials
                                        ?.loginId
                                        .orEmpty()

                                password = ""
                            }
                        },
                        loginId = loginId,
                        onLoginIdChange = {
                            loginId = it
                        },
                        password = password,
                        onPasswordChange = {
                            password = it
                        },
                        passwordVisible =
                            passwordVisible,
                        onPasswordVisibilityChange = {
                            passwordVisible =
                                !passwordVisible
                        },
                        onSave = {
                            val current =
                                credentialStore.load()

                            val passwordToSave =
                                when {
                                    password.isNotBlank() ->
                                        password

                                    current != null ->
                                        current.password

                                    else ->
                                        ""
                                }

                            if (loginId.isBlank()) {
                                statusMessage =
                                    "ログインIDを入力してください。"

                                return@CredentialSettingsSection
                            }

                            if (passwordToSave.isBlank()) {
                                statusMessage =
                                    "パスワードを入力してください。"

                                return@CredentialSettingsSection
                            }

                            try {
                                credentialStore.save(
                                    loginId =
                                        loginId.trim(),
                                    password =
                                        passwordToSave
                                )

                                storedCredentials =
                                    credentialStore.load()

                                password = ""
                                showCredentialEditor = false

                                statusMessage =
                                    "認証情報を更新しました。"
                            } catch (
                                exception: Exception
                            ) {
                                statusMessage =
                                    "認証情報の更新に失敗しました。"
                            }
                        },
                        onDelete = {
                            credentialStore.clear()

                            storedCredentials = null
                            loginId = ""
                            password = ""
                            showCredentialEditor = true

                            statusMessage =
                                "認証情報を削除しました。"
                        }
                    )

                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(
                                    Settings
                                        .ACTION_ACCESSIBILITY_SETTINGS
                                )
                            )
                        },
                        modifier =
                            Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "ユーザー補助設定を開く"
                        )
                    }
                }
            }

            if (statusMessage.isNotBlank()) {
                Text(
                    text = statusMessage,
                    style =
                        MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * 認証情報未登録時の初期設定画面。
 */
@Composable
private fun InitialSetupSection(
    loginId: String,
    onLoginIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: () -> Unit,
    onSave: () -> Unit
) {
    Text(
        text = "初期設定が必要です",
        style =
            MaterialTheme.typography.titleLarge
    )

    Text(
        text =
            "ログインIDとパスワードを登録してください。認証情報は端末内で暗号化して保存されます。"
    )

    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {
        CredentialEditor(
            loginId = loginId,
            onLoginIdChange =
                onLoginIdChange,
            password = password,
            onPasswordChange =
                onPasswordChange,
            passwordVisible =
                passwordVisible,
            onPasswordVisibilityChange =
                onPasswordVisibilityChange,
            passwordAlreadyStored = false,
            onSave = onSave,
            saveButtonText =
                "認証情報を保存"
        )
    }
}

/**
 * 認証情報登録済みだが、
 * ユーザー補助サービスが無効な場合。
 */
@Composable
private fun AccessibilityRequiredSection(
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {
        Column(
            modifier =
                Modifier.padding(20.dp),
            verticalArrangement =
                Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text =
                    "ユーザー補助サービスが無効です",
                style =
                    MaterialTheme.typography.titleLarge
            )

            Text(
                text = "認証情報：登録済み"
            )

            Text(
                text =
                    "SSBProのログイン操作を自動化するには、SSB Login Helperのユーザー補助サービスを有効にしてください。"
            )

            Button(
                onClick = onOpenSettings,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "ユーザー補助設定を開く"
                )
            }

            OutlinedButton(
                onClick = onRefresh,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("状態を再確認")
            }
        }
    }
}

/**
 * 利用準備が整った通常画面。
 */
@Composable
private fun ReadySection(
    onLaunchMobile: () -> Unit,
    onLaunchPc: () -> Unit
) {
    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {
        Column(
            modifier =
                Modifier.padding(20.dp),
            verticalArrangement =
                Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "利用準備完了",
                style =
                    MaterialTheme.typography.titleLarge
            )

            Text(
                text = "認証情報：登録済み"
            )

            Text(
                text =
                    "ユーザー補助サービス：有効"
            )

            Button(
                onClick = onLaunchMobile,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("スマホ版を開く")
            }

            Button(
                onClick = onLaunchPc,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text("PC版を開く")
            }

            Text(
                text =
                    "各ボタンを押したときだけ、自動ログイン処理を1回実行します。",
                style =
                    MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * 保存済み認証情報の変更・削除欄。
 *
 * 通常時は折りたたんで表示する。
 */
@Composable
private fun CredentialSettingsSection(
    showEditor: Boolean,
    onShowEditorChange: (Boolean) -> Unit,
    loginId: String,
    onLoginIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit
) {
    if (!showEditor) {
        OutlinedButton(
            onClick = {
                onShowEditorChange(true)
            },
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text("認証情報を変更")
        }

        return
    }

    Card(
        modifier =
            Modifier.fillMaxWidth()
    ) {
        Column(
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {
            CredentialEditor(
                loginId = loginId,
                onLoginIdChange =
                    onLoginIdChange,
                password = password,
                onPasswordChange =
                    onPasswordChange,
                passwordVisible =
                    passwordVisible,
                onPasswordVisibilityChange =
                    onPasswordVisibilityChange,
                passwordAlreadyStored = true,
                onSave = onSave,
                saveButtonText =
                    "認証情報を更新"
            )

            Column(
                modifier = Modifier
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onShowEditorChange(false)
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text("変更をやめる")
                }

                OutlinedButton(
                    onClick = onDelete,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Text("認証情報を削除")
                }
            }
        }
    }
}

/**
 * ID・パスワード入力フォーム。
 */
@Composable
private fun CredentialEditor(
    loginId: String,
    onLoginIdChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: () -> Unit,
    passwordAlreadyStored: Boolean,
    onSave: () -> Unit,
    saveButtonText: String
) {
    Column(
        modifier =
            Modifier.padding(16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "認証情報",
            style =
                MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = loginId,
            onValueChange =
                onLoginIdChange,
            modifier =
                Modifier.fillMaxWidth(),
            label = {
                Text("ログインID")
            },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType =
                        KeyboardType.Text
                )
        )

        OutlinedTextField(
            value = password,
            onValueChange =
                onPasswordChange,
            modifier =
                Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (passwordAlreadyStored) {
                        "新しいパスワード"
                    } else {
                        "パスワード"
                    }
                )
            },
            supportingText = {
                if (
                    passwordAlreadyStored &&
                    password.isBlank()
                ) {
                    Text(
                        "変更しない場合は空欄のままで更新できます"
                    )
                }
            },
            singleLine = true,
            visualTransformation =
                if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            keyboardOptions =
                KeyboardOptions(
                    keyboardType =
                        KeyboardType.Password
                ),
            trailingIcon = {
                TextButton(
                    onClick =
                        onPasswordVisibilityChange
                ) {
                    Text(
                        if (passwordVisible) {
                            "隠す"
                        } else {
                            "表示"
                        }
                    )
                }
            }
        )

        Button(
            onClick = onSave,
            modifier =
                Modifier.fillMaxWidth()
        ) {
            Text(saveButtonText)
        }
    }
}

/**
 * SSBProを起動する。
 */
/**
 * SSBProを起動する。
 */
/**
 * 指定された画面を目標としてSSBProを起動する。
 */
private fun launchSsbPro(
    context: Context,
    target: AutomationTarget,
    onError: (String) -> Unit
) {
    val launchIntent =
        context.packageManager
            .getLaunchIntentForPackage(
                "jp.co.soliton.securebrowserpro"
            )

    if (launchIntent == null) {
        onError(
            "SSBProが見つかりません。"
        )

        return
    }

    val requestSaved =
        AutomationControl.requestAutomation(
            context = context,
            target = target
        )

    if (!requestSaved) {
        onError(
            "自動化の開始要求を保存できませんでした。"
        )

        return
    }

    context.startActivity(
        launchIntent
    )
}

/**
 * SSB Login Helperのユーザー補助サービスが
 * 有効になっているか確認する。
 */
private fun isAccessibilityServiceEnabled(
    context: Context
): Boolean {
    val expectedComponent =
        ComponentName(
            context,
            SSBAccessibilityService::class.java
        )

    val enabledServices =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure
                .ENABLED_ACCESSIBILITY_SERVICES
        )

    if (enabledServices.isNullOrBlank()) {
        return false
    }

    val splitter =
        TextUtils.SimpleStringSplitter(':')

    splitter.setString(
        enabledServices
    )

    while (splitter.hasNext()) {
        val componentName =
            ComponentName.unflattenFromString(
                splitter.next()
            ) ?: continue

        if (componentName == expectedComponent) {
            return true
        }
    }

    return false
}
