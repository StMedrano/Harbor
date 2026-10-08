package dev.stmedrano.harbor.parent.usage

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

class UsagePermission(context:Context) {
    private val context=context.applicationContext
    fun isGranted():Boolean = try {
        context.getSystemService(AppOpsManager::class.java)?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,Process.myUid(),context.packageName)==AppOpsManager.MODE_ALLOWED
    } catch (_:SecurityException) {false}
    fun settingsIntent():Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS,Uri.parse("package:${context.packageName}"))
}
