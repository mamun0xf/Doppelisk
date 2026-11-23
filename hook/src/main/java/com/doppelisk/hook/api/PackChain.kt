package com.doppelisk.hook.api

/**
 * One call being intercepted.
 *
 * This is the hooking surface the generic hooks are written against, in the
 * shape Xposed's `Chain` has, so the same code can also be driven by a fake
 * chain outside a hooked process — which is how the manager previews a profile
 * and how the fixtures check a hook without a device.
 */
interface PackChain {

    /** The instance the method was called on, or null for a static method. */
    val thisObject: Any?

    /** The call's arguments, as passed. */
    val args: Array<Any?>

    /** Runs the original method with the original arguments. */
    fun proceed(): Any?

    /** Runs the original method with [args] instead. */
    fun proceedWith(args: Array<Any?>): Any?

    /** Argument at [index], or null when the index is out of range. */
    fun arg(index: Int): Any? = args.getOrNull(index)
}

/** The interception body. */
fun interface PackHook {
    fun intercept(chain: PackChain): Any?
}
