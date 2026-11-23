package com.doppelisk.daemon

import android.util.Log

/**
 * Entry point for the root daemon.
 *
 * Started as `app_process` with this module on the classpath, so there is no
 * `Application` and no `Context`: everything the daemon needs comes from the
 * filesystem and from `android.*` system calls.
 */
object DoppeliskDaemon {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            DaemonServer().serveForever()
        } catch (t: Throwable) {
            Log.e("DoppeliskDaemon", "fatal", t)
        }
    }
}
