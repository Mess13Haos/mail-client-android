package com.example.mailclient

import android.content.Context

object ThemeStore {
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_GESTURE_LEFT = "gesture_left"
    private const val KEY_GESTURE_RIGHT = "gesture_right"

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    enum class GestureAction(val title: String) {
        DELETE("Удалить"),
        MARK_READ("Пометить прочитанным"),
        SPAM("В спам"),
        NONE("Ничего")
    }

    fun getThemeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_THEME_MODE, "SYSTEM")) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun nextMode(current: ThemeMode): ThemeMode {
        return when (current) {
            ThemeMode.SYSTEM -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.SYSTEM
        }
    }

    fun getLeftGesture(context: Context): GestureAction {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            GestureAction.valueOf(prefs.getString(KEY_GESTURE_LEFT, GestureAction.DELETE.name) ?: GestureAction.DELETE.name)
        } catch (e: Exception) {
            GestureAction.DELETE
        }
    }

    fun setLeftGesture(context: Context, action: GestureAction) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GESTURE_LEFT, action.name).apply()
    }

    fun getRightGesture(context: Context): GestureAction {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            GestureAction.valueOf(prefs.getString(KEY_GESTURE_RIGHT, GestureAction.MARK_READ.name) ?: GestureAction.MARK_READ.name)
        } catch (e: Exception) {
            GestureAction.MARK_READ
        }
    }

    fun setRightGesture(context: Context, action: GestureAction) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GESTURE_RIGHT, action.name).apply()
    }
}
