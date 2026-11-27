package com.merin.a11yguard2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Diag.init(context)
        val action = intent?.action ?: return
        Diag.log("boot", "received $action")
        // elapsedRealtime 在重启后归零，旧的 check_armed_at 会被误判成“闹钟还在”，导致重启后永远不再排自检
        SvcRepo.prefs(context).edit().remove("check_armed_at").apply()
        Keepalive.scheduleBootOnce(context)
        Keepalive.scheduleCheck(context)
        // 占位开关跨重启保持：Settings 本身会持久化，这里只处理被系统/设置页清掉的情况
        if (TalkBackPlace.isOn(context)) {
            Thread { TalkBackPlace.reconcile(context, "boot") }.start()
        }
        if (SvcRepo.prefs(context).getBoolean("desired_on", false)) {
            Keepalive.start(context, boot = true)
        }
    }
}
