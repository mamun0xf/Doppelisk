package com.doppelisk.core.container

import kotlinx.serialization.Serializable

/**
 * A bind mount the container needs: [source] is the prepared directory inside
 * the container root, [target] is the real path it replaces.
 */
@Serializable
data class ContainerMount(
    val source: String,
    val target: String,
)

/**
 * A directory swap: the real [name] under [dataDir] is moved to [backup], the
 * [container] copy takes its place, and the original can be restored later.
 *
 * This is how a hybrid container replaces `shared_prefs` or `databases` without
 * losing what the app had before.
 */
@Serializable
data class DirectorySwap(
    val dataDir: String,
    val name: String,
    val container: String,
    val backup: String,
) {
    /** Absolute path being replaced; the whole data dir when [name] is blank. */
    val target: String
        get() = if (name.isBlank()) dataDir else "$dataDir/$name"
}

/**
 * Everything needed to put one package into one container.
 *
 * [mountRoot] holds the prepared copies, [swapRoot] holds the container's
 * private copies of swapped directories, and [keystoreNamespace] isolates
 * keystore entries when the mode is not [ContainerModes.SHARED].
 */
@Serializable
data class ContainerPlan(
    val doppeliskId: String,
    val packageName: String,
    val mode: String,
    val dataDir: String,
    val mountRoot: String,
    val swapRoot: String,
    val mounts: List<ContainerMount> = emptyList(),
    val swaps: List<DirectorySwap> = emptyList(),
    val keystoreNamespace: String? = null,
) {
    /**
     * True when the mode cannot work without a private mount namespace, i.e. an
     * isolated container that must hide the app's real data directory.
     */
    val requiresPrivateNamespace: Boolean
        get() = mode == ContainerModes.ISOLATED

    /** True when there is at least one bind mount to apply. */
    val usesMounts: Boolean
        get() = mounts.isNotEmpty()

    /** True when there is at least one directory swap to apply. */
    val usesSwaps: Boolean
        get() = swaps.isNotEmpty()
}

/** The subdirectories a hybrid container isolates when the profile names none. */
val DEFAULT_HYBRID_ISOLATE: List<String> = listOf("shared_prefs", "databases", "files")

/**
 * Builds a [ContainerPlan] from an artifact.
 *
 * The three modes differ in how much of the data directory is replaced:
 *
 *  - [ContainerModes.SHARED] replaces nothing (hooks only);
 *  - [ContainerModes.HYBRID] bind-mounts a handful of subdirectories and swaps
 *    them on disk so the app cannot see the real files even through `su`;
 *  - [ContainerModes.ISOLATED] replaces the entire data directory.
 */
object ContainerPlanner {

    fun plan(artifact: com.doppelisk.core.model.DoppeliskArtifact, dataDir: String, root: String = "/data/adb/doppelisk"): ContainerPlan {
        val packageName = artifact.target.packageName
        require(packageName.isNotBlank()) { "artifact has no target package" }

        val spec = artifact.container
        val mode = ContainerModes.normalize(spec.mode)
        val safeId = ContainerPaths.safeComponent(artifact.id)
        val safePkg = ContainerPaths.safeComponent(packageName)
        val mountRoot = "$root/containers/$safeId/$safePkg"
        val swapRoot = "$dataDir/.doppelisk-store/$safeId"

        val keystoreNamespace = when {
            !spec.keystoreNamespace.isNullOrBlank() -> spec.keystoreNamespace
            mode != ContainerModes.SHARED -> defaultKeystoreNamespace(artifact.id)
            else -> null
        }

        return when (mode) {
            ContainerModes.ISOLATED -> ContainerPlan(
                doppeliskId = artifact.id,
                packageName = packageName,
                mode = mode,
                dataDir = dataDir,
                mountRoot = mountRoot,
                swapRoot = swapRoot,
                mounts = listOf(ContainerMount("$mountRoot/data", dataDir)),
                swaps = emptyList(),
                keystoreNamespace = keystoreNamespace,
            )

            ContainerModes.HYBRID -> {
                val names = isolateNames(spec)
                ContainerPlan(
                    doppeliskId = artifact.id,
                    packageName = packageName,
                    mode = mode,
                    dataDir = dataDir,
                    mountRoot = mountRoot,
                    swapRoot = swapRoot,
                    mounts = names.map { ContainerMount("$mountRoot/$it", "$dataDir/$it") },
                    swaps = names.map {
                        DirectorySwap(
                            dataDir = dataDir,
                            name = it,
                            container = "$swapRoot/$it",
                            backup = "$root/backups/$safePkg/$it",
                        )
                    },
                    keystoreNamespace = keystoreNamespace,
                )
            }

            else -> ContainerPlan(
                doppeliskId = artifact.id,
                packageName = packageName,
                mode = ContainerModes.SHARED,
                dataDir = dataDir,
                mountRoot = mountRoot,
                swapRoot = swapRoot,
            )
        }
    }

    /**
     * Normalises the requested isolate list: trims, drops anything that is not a
     * safe single path segment, de-duplicates, and falls back to
     * [DEFAULT_HYBRID_ISOLATE] when nothing usable is left.
     */
    private fun isolateNames(spec: com.doppelisk.core.model.ContainerSpec): List<String> {
        val requested = if (spec.isolate.isEmpty()) DEFAULT_HYBRID_ISOLATE else spec.isolate
        val cleaned = requested.map(String::trim).filter(ContainerPaths::isSafeChild).distinct()
        return cleaned.ifEmpty { DEFAULT_HYBRID_ISOLATE }
    }

    /** Deterministic keystore namespace for an artifact: `dpl_` + 8 hex chars. */
    fun defaultKeystoreNamespace(doppeliskId: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(doppeliskId.toByteArray(Charsets.UTF_8))
        val hex = digest.take(4).joinToString("") { String.format("%02x", it.toInt() and 0xFF) }
        return "dpl_$hex"
    }
}
