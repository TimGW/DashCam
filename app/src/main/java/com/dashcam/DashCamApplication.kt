package com.dashcam

import android.app.Application
import com.dashcam.di.AppContainer

class DashCamApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
