package com.rotalab.emotibit.app

import android.app.Application

class EmotiBitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HostController.init(this)
    }
}
