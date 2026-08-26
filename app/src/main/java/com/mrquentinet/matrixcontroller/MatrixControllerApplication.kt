package com.mrquentinet.matrixcontroller

import android.app.Application

class MatrixControllerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
