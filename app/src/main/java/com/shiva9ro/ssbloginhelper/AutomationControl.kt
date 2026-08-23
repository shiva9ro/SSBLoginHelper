package com.shiva9ro.ssbloginhelper

import android.content.Context

/**
 * 自動化で最終的に開く画面。
 */
enum class AutomationTarget {
    MOBILE,
    PC
}

/**
 * MainActivityからAccessibilityServiceへ、
 * 新しい自動化の開始要求を渡す。
 */
object AutomationControl {

    private const val PREFERENCES_NAME =
        "automation_control"

    private const val KEY_REQUEST_PENDING =
        "request_pending"

    private const val KEY_TARGET =
        "target"

    /**
     * 新しい自動化セッションを要求する。
     */
    fun requestAutomation(
        context: Context,
        target: AutomationTarget
    ): Boolean {
        return context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
            .edit()
            .putBoolean(
                KEY_REQUEST_PENDING,
                true
            )
            .putString(
                KEY_TARGET,
                target.name
            )
            .commit()
    }

    /**
     * 開始要求を1回だけ取得する。
     *
     * 要求を取得した後は、同じ要求を再取得しない。
     */
    fun consumeAutomationRequest(
        context: Context
    ): AutomationTarget? {
        val preferences =
            context.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )

        if (
            !preferences.getBoolean(
                KEY_REQUEST_PENDING,
                false
            )
        ) {
            return null
        }

        val targetName =
            preferences.getString(
                KEY_TARGET,
                null
            )

        preferences
            .edit()
            .remove(KEY_REQUEST_PENDING)
            .remove(KEY_TARGET)
            .commit()

        return try {
            targetName?.let {
                AutomationTarget.valueOf(it)
            }
        } catch (
            exception: IllegalArgumentException
        ) {
            null
        }
    }
}