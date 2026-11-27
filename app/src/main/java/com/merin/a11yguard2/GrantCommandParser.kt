package com.merin.a11yguard2

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 授权命令窗口 —— 粘贴 adb 命令即可检测并引导激活。
 *
 * ══════════════════════════════════════════════════════════════════
 * 为什么不能"粘贴就执行"
 * ══════════════════════════════════════════════════════════════════
 *
 * `adb shell pm grant ... WRITE_SECURE_SETTINGS` 这条命令需要 **shell 权限**
 * 才能执行。而 App 自己就是没有这个权限才需要它的 —— 逻辑上绕回来了。
 *
 * 能执行 shell 的三条路：
 *   · root（su）：设备已 root 才行
 *   · Shizuku：本身就需要先通过 adb/无线调试激活
 *   · 系统签名：不可能
 *
 * 所以「粘贴 adb 命令 → App 自己执行」在未 root 设备上**做不到**。
 *
 * ══════════════════════════════════════════════════════════════════
 * 这个窗口实际做什么
 * ══════════════════════════════════════════════════════════════════
 *
 * 1. **解析粘贴的命令**，确认是给本应用的 WRITE_SECURE_SETTINGS 授权
 * 2. **立即检测权限是否已生效**（用户可能已经在电脑上执行完了）
 * 3. 已生效 → 明确告知成功
 * 4. 未生效 → 给出三条**不需要电脑**的替代路径：
 *      · Shizuku（推荐，全程手机内完成）
 *      · root（已 root 的设备）
 *      · 点按模式（只需无障碍授权，已内建）
 *
 * 这样既诚实、又确实帮用户把路走通。
 */
object GrantCommandParser {

    /** 解析结果。 */
    data class Parsed(
        /** 是否识别为本应用的授权命令。 */
        val isOurGrant: Boolean,
        /** 涉及的权限名（若能提取）。 */
        val permission: String,
        /** 面向用户的说明。 */
        val message: String
    )

    /**
     * 解析粘贴进来的文本。
     *
     * 能识别的形态（用户从各处复制的都可能不同）：
     *   adb shell pm grant com.merin.a11yguard2 android.permission.WRITE_SECURE_SETTINGS
     *   pm grant com.merin.a11yguard2 android.permission.WRITE_SECURE_SETTINGS
     *   adb shell pm grant <包名> <权限>
     */
    fun parse(raw: String, ownPkg: String): Parsed {
        val s = raw.trim().replace("\r", "").replace("\n", " ")
        if (s.isEmpty()) {
            return Parsed(false, "", "内容为空")
        }

        // 提取 grant/revoke 语句
        val m = Regex("""pm\\s+(grant|revoke)\\s+(\\S+)\\s+(\\S+)""").find(s)
            ?: return Parsed(
                false, "",
                "没识别出 pm grant 命令。\n\n" +
                    "正确的形式是：\n" +
                    "pm grant $ownPkg android.permission.WRITE_SECURE_SETTINGS"
            )

        val action = m.groupValues[1]
        val pkg = m.groupValues[2]
        val perm = m.groupValues[3]

        if (pkg != ownPkg) {
            return Parsed(
                false, perm,
                "这条命令是给「$pkg」授权的，不是本应用（$ownPkg）。\n\n" +
                    "包名不对的话，授权不会生效。"
            )
        }

        if (!perm.contains("WRITE_SECURE_SETTINGS")) {
            return Parsed(
                false, perm,
                "这条命令授权的权限是「$perm」，本应用需要的是 " +
                    "android.permission.WRITE_SECURE_SETTINGS。"
            )
        }

        return if (action == "grant") {
            Parsed(true, perm, "识别成功：给本应用授予直写权限")
        } else {
            Parsed(false, perm, "这是 revoke（撤销）命令，会把已授予的权限收回。")
        }
    }
}
