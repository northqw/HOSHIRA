package dev.aniliberty.android

import android.content.Context
import java.nio.file.Path

object AndroidEnvironment {
    private lateinit var applicationContext: Context

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun context(): Context {
        check(::applicationContext.isInitialized) {
            "AndroidEnvironment must be initialized before using platform services"
        }
        return applicationContext
    }
}

internal fun dataDirectory(): Path = AndroidEnvironment.context().filesDir.toPath()

internal fun cacheDirectory(): Path = AndroidEnvironment.context().cacheDir.toPath()
