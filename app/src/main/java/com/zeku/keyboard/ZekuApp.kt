package com.zeku.keyboard

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class ZekuApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs(this).nightMode())
    }
}
