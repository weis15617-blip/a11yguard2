package com.google.android.marvin.talkback

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.merin.a11yguard2.Diag

/**
 * 类名刻意与真 TalkBack 的服务类完全一致（组件所属 package 仍是本 App），
 * 照“无障碍联盟服务插件”的做法：只伪装类名，不伪造包名、不申请任何权限。
 *
 * 没有任何实际能力：不订阅事件（config 中 eventTypes=0）、不读窗口内容、
 * 不请求触摸探索/按键过滤；onServiceConnected 里再把触摸探索位清掉，确保绝不接管触摸。
 */
class TalkBackService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Diag.init(this)
        runCatching {
            serviceInfo?.let {
                // 与插件同款保险：运行时清掉 FLAG_REQUEST_TOUCH_EXPLORATION_MODE(0x4)
                it.flags = it.flags and 0x4.inv()
                setServiceInfo(it)
            }
        }
        Diag.log("tbplace", "shell connected flags=${serviceInfo?.flags}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        // 空壳不参与保活：解绑只记录，不触发自我修复
        Diag.log("tbplace", "shell unbind")
        return super.onUnbind(intent)
    }
}
