package com.intunix.sample

import android.app.Application
import com.intunix.sdk.Intunix
import com.intunix.sdk.IntunixOptions

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Intunix.init(this, BuildConfig.API_KEY, IntunixOptions(registerAll = true, debug = true))
    }
}
