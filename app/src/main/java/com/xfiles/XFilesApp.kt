package com.xfiles

import android.app.Application

class XFilesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: XFilesApp
            private set
    }
}
