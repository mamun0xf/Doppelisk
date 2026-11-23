package com.doppelisk.hook.generic

import android.security.keystore.KeyGenParameterSpec
import com.doppelisk.hook.api.HookContext
import com.doppelisk.hook.generic.HookSupport.hook
import com.doppelisk.hook.generic.HookSupport.hookLazy
import com.doppelisk.hook.generic.HookSupport.record
import io.github.libxposed.api.XposedInterface
import java.security.Key
import java.security.KeyStore
import java.security.cert.Certificate
import java.util.Collections
import java.util.Enumeration

/**
 * Moves every AndroidKeyStore alias into a per-profile namespace.
 *
 * Keystore aliases are a strong signal: an app that created `myapp_key` on one
 * account and then finds it already present on a "different" device knows the
 * data survived. Prefixing every alias with the profile's namespace makes each
 * profile look like a fresh installation, without destroying the real keys —
 * they are still there, just under a different name.
 *
 * The alias is rewritten on the way in *and* stripped on the way out, so an app
 * that enumerates its aliases sees exactly the names it created.
 */
object KeystoreHooks {

    fun install(xposed: XposedInterface, context: HookContext) {
        val namespace = context.artifact.container.keystoreNamespace?.trim()
        if (namespace.isNullOrEmpty()) return

        hookAliasGetter(xposed, context, namespace)
        hookKeyStoreMethods(xposed, context, namespace)
        hookAliases(xposed, context, namespace)
    }

    private fun hookAliasGetter(xposed: XposedInterface, context: HookContext, namespace: String) {
        hookLazy(
            xposed,
            context,
            "KeyGenParameterSpec.getKeystoreAlias",
            { KeyGenParameterSpec::class.java.getMethod("getKeystoreAlias") },
        ) { chain ->
            val real = runCatching { chain.proceed() }.getOrNull() as? String
            val fake = prefix(namespace, real)
            record(context, "keystore", "KeyGenParameterSpec.getKeystoreAlias", real, fake)
            fake
        }
    }

    /**
     * Rewrites the alias argument of every `KeyStore` method that takes one.
     *
     * Signatures are listed explicitly instead of scanning for `String` first
     * parameters: `KeyStore` has non-alias methods that take a `String` too, and
     * rewriting those would corrupt unrelated calls.
     */
    private fun hookKeyStoreMethods(xposed: XposedInterface, context: HookContext, namespace: String) {
        val signatures: List<Pair<String, Array<Class<*>>>> = listOf(
            "getKey" to arrayOf(String::class.java, CharArray::class.java),
            "getCertificate" to arrayOf(String::class.java),
            "getCertificateChain" to arrayOf(String::class.java),
            "getCreationDate" to arrayOf(String::class.java),
            "getEntry" to arrayOf(String::class.java, KeyStore.ProtectionParameter::class.java),
            "setEntry" to arrayOf(
                String::class.java,
                KeyStore.Entry::class.java,
                KeyStore.ProtectionParameter::class.java,
            ),
            "setKeyEntry" to arrayOf(
                String::class.java,
                Key::class.java,
                CharArray::class.java,
                Array<Certificate>::class.java,
            ),
            "setKeyEntry" to arrayOf(
                String::class.java,
                ByteArray::class.java,
                Array<Certificate>::class.java,
            ),
            "setCertificateEntry" to arrayOf(String::class.java, Certificate::class.java),
            "deleteEntry" to arrayOf(String::class.java),
            "containsAlias" to arrayOf(String::class.java),
            "isKeyEntry" to arrayOf(String::class.java),
            "isCertificateEntry" to arrayOf(String::class.java),
        )

        for ((name, types) in signatures) {
            val method = runCatching { KeyStore::class.java.getMethod(name, *types) }.getOrNull() ?: continue

            hook(xposed, context, "KeyStore.$name", method) { chain ->
                if (!isAndroidKeyStore(chain)) return@hook chain.proceed()

                val args = chain.args.toMutableList()
                val real = args.getOrNull(0) as? String
                val fake = prefix(namespace, real)
                if (fake != null) args[0] = fake

                record(context, "keystore", "KeyStore.$name", real, fake)
                chain.proceedWith(args.toTypedArray())
            }
        }
    }

    private fun hookAliases(xposed: XposedInterface, context: HookContext, namespace: String) {
        hookLazy(
            xposed,
            context,
            "KeyStore.aliases",
            { KeyStore::class.java.getMethod("aliases") },
        ) { chain ->
            val androidKeyStore = isAndroidKeyStore(chain)
            val result = chain.proceed()

            if (result is Enumeration<*> && androidKeyStore) {
                val marker = "$namespace:"
                val values = Collections.list(result).map { entry ->
                    val name = entry as? String
                    if (name != null && name.startsWith(marker)) name.removePrefix(marker) else entry
                }
                record(context, "keystore", "KeyStore.aliases", null, values.size)
                enumerationOf(values)
            } else {
                result
            }
        }
    }

    /** True when the call is on the `AndroidKeyStore` provider. */
    private fun isAndroidKeyStore(chain: com.doppelisk.hook.api.PackChain): Boolean {
        val store = chain.thisObject as? KeyStore ?: return false
        val provider = runCatching { store.provider }.getOrNull() ?: return false
        return provider.name.equals("AndroidKeyStore", ignoreCase = true)
    }

    private fun prefix(namespace: String, alias: String?): String? {
        if (alias == null) return null
        val marker = "$namespace:"
        return if (alias.startsWith(marker)) alias else marker + alias
    }

    private fun enumerationOf(values: List<Any?>): Enumeration<Any?> =
        object : Enumeration<Any?> {
            private var index = 0
            override fun hasMoreElements(): Boolean = index < values.size
            override fun nextElement(): Any? = values[index++]
        }
}
