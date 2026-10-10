package dev.johnai.seam

import android.app.Application

/** Restores or resumes a find-my-phone ring if the process was killed mid-ring. */
class SeamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            PhoneFinder.onProcessStart(this)
        } catch (_: Exception) {
        }
    }
}
