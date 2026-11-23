package com.doppelisk.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.doppelisk.hook.engine.DaemonClient
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The manager's `Application`.
 *
 * Two jobs: point the IPC client at this app's mailbox, and track the Xposed
 * service so the UI can ask whether the manager is connected to the framework
 * and whether a package is in scope.
 *
 * The service is a remote binder, so it can die and come back; listeners are
 * notified on every transition and the UI re-reads the scope rather than caching
 * it.
 */
class DoppeliskApp : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()

        DaemonClient.configure(filesDir)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        Log.i(TAG, "framework service bound: ${service.frameworkName} ${service.frameworkVersion}")
        Companion.service = service
        notifyServiceChanged()
    }

    override fun onServiceDied(service: XposedService) {
        Log.i(TAG, "framework service died")
        if (Companion.service === service) Companion.service = null
        notifyServiceChanged()
    }

    companion object {

        private const val TAG = "Doppelisk"

        private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

        private val serviceListeners = CopyOnWriteArrayList<() -> Unit>()

        @Volatile
        private var service: XposedService? = null

        /** True when the manager can talk to the Xposed framework. */
        fun isConnected(): Boolean = service != null

        /** True when [packageName] is in the framework's hook scope. */
        fun isInScope(packageName: String): Boolean =
            service?.scope?.contains(packageName) == true

        fun addServiceListener(listener: () -> Unit) {
            serviceListeners += listener
        }

        fun removeServiceListener(listener: () -> Unit) {
            serviceListeners -= listener
        }

        private fun notifyServiceChanged() {
            for (listener in serviceListeners) {
                runCatching { listener() }
            }
        }

        /**
         * Asks the framework to add [packageName] to the hook scope.
         *
         * The request may be answered by the framework, by a user dialog, or not
         * at all, so a six-second timeout turns silence into a failure the UI can
         * show instead of a spinner that never stops.
         */
        fun requestScope(packageName: String, callback: (Boolean, String?) -> Unit) {
            val answered = java.util.concurrent.atomic.AtomicBoolean(false)

            fun finish(approved: Boolean, message: String?) {
                if (answered.compareAndSet(false, true)) callback(approved, message)
            }

            val timeout = Runnable {
                finish(false, "LSPosed did not answer the scope request")
            }
            mainHandler.postDelayed(timeout, 6000L)

            fun complete(approved: Boolean, message: String?) {
                mainHandler.removeCallbacks(timeout)
                finish(approved, message)
            }

            val current = service
            if (current == null) {
                complete(
                    false,
                    "LSPosed service not connected; enable Doppelisk in LSPosed first",
                )
                return
            }

            if (current.scope.contains(packageName)) {
                complete(true, null)
                return
            }

            current.requestScope(
                listOf(packageName),
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: List<String>) {
                        if (approved.contains(packageName)) {
                            complete(true, null)
                        } else {
                            complete(false, "LSPosed did not approve $packageName")
                        }
                    }

                    override fun onScopeRequestFailed(message: String) {
                        complete(false, message)
                    }
                },
            )
        }
    }
}
