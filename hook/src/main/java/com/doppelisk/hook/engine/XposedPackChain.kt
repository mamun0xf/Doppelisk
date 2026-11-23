package com.doppelisk.hook.engine

import com.doppelisk.hook.api.PackChain
import io.github.libxposed.api.XposedInterface

/**
 * Adapts libxposed's [XposedInterface.Chain] to the framework-neutral [PackChain].
 *
 * The only real work is copying the argument list: libxposed hands out a `List`,
 * while hooks expect an array they can index into cheaply.
 */
internal class XposedPackChain(private val delegate: XposedInterface.Chain) : PackChain {

    override val thisObject: Any?
        get() = delegate.thisObject

    override val args: Array<Any?>
        get() = delegate.args.toTypedArray()

    override fun proceed(): Any? = delegate.proceed()

    override fun proceedWith(args: Array<Any?>): Any? = delegate.proceed(args)
}
