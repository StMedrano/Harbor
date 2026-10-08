package dev.stmedrano.harbor.parent.usage

import android.content.Context
import android.content.Intent

class UsagePermission(private val context:Context) {
    fun isGranted():Boolean=false
    fun settingsIntent():Intent=Intent()
}
