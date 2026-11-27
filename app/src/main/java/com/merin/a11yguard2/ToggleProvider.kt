package com.merin.a11yguard2

import android.content.Context

/** 统一执行层接口。route = tap | shizuku。 */
interface ToggleProvider {
    val route: String

    /** available | not_running | no_permission | unsupported */
    fun status(ctx: Context): String

    fun toggle(ctx: Context, comp: String, want: Boolean): ToggleResult
}

data class ToggleResult(
    val route: String,
    val target: String,
    val found: Boolean,
    val clicked: Boolean,
    val verify: Boolean,
    val reason: String? = null
) {
    val ok: Boolean get() = verify

    fun logLine(): String =
        "route=$route target=$target found=$found clicked=$clicked verify=$verify reason=${reason ?: "-"}"
}
