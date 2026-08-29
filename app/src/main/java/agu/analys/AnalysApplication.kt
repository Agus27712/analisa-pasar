package agu.analys

import android.app.Application
import timber.log.Timber

class AnalysApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextProvider.init(this)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
