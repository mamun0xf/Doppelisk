package com.doppelisk.hook.engine

import com.doppelisk.core.model.DoppeliskArtifact
import com.doppelisk.core.model.HardwareIdentity
import java.io.File
import java.util.Locale

/**
 * Generates the CPU surface of a chassis: `/proc/cpuinfo`, `/proc/meminfo` and the
 * `/sys/devices/system/cpu` tree.
 *
 * These files are read by anti-fraud SDKs because they are cheap and hard to fake
 * convincingly. A profile that spoofs `Build.MODEL` but leaves the real Exynos
 * cluster layout in `/proc/cpuinfo` is trivially detected, so the layout is
 * derived from the SoC slug in the profile.
 *
 * Values are generated rather than copied from a real device: they only have to be
 * self-consistent and plausible, not byte-identical to any particular unit.
 */
object CpuIdentity {

    private const val MIN_MHZ = 300

    /** SoC slug → cluster layout. Unknown SoCs produce no virtual CPU files. */
    private val SPECS: Map<String, Spec> = mapOf(
        "google/tensor-g3" to Spec(
            "4 nm", "64-bit ARMv9-A",
            listOf(
                Cluster("Cortex-X3", 3406, 2, 2910, MIN_MHZ),
                Cluster("Cortex-A715", 3405, 3, 2370, MIN_MHZ),
                Cluster("Cortex-A510", 3398, 4, 1700, MIN_MHZ),
            ),
        ),
        "google/tensor-g2" to Spec(
            "5 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-X1", 3396, 2, 2850, MIN_MHZ),
                Cluster("Cortex-A78", 3393, 2, 2350, MIN_MHZ),
                Cluster("Cortex-A55", 3333, 4, 1800, MIN_MHZ),
            ),
        ),
        "samsung/exynos2400" to Spec(
            "4 nm", "64-bit ARMv9.2-A",
            listOf(
                Cluster("Cortex-X4", 3458, 2, 3200, 400),
                Cluster("Cortex-A720", 3457, 5, 2900, 400),
                Cluster("Cortex-A520", 3456, 3, 1950, 400),
            ),
        ),
        "samsung/exynos1280" to Spec(
            "5 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-A78", 3393, 2, 2400, MIN_MHZ),
                Cluster("Cortex-A55", 3333, 6, 2000, MIN_MHZ),
            ),
        ),
        "samsung/exynos9611" to Spec(
            "10 nm", "64-bit ARMv8-A",
            listOf(
                Cluster("Cortex-A73", 3337, 4, 2300, 400),
                Cluster("Cortex-A53", 3331, 4, 1740, 400),
            ),
        ),
        "mediatek/mt6877" to Spec(
            "6 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-A78", 3393, 2, 2600, MIN_MHZ),
                Cluster("Cortex-A55", 3333, 6, 2000, MIN_MHZ),
            ),
        ),
        "mediatek/mt6789" to Spec(
            "6 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-A76", 3339, 2, 2200, MIN_MHZ),
                Cluster("Cortex-A55", 3333, 6, 2000, MIN_MHZ),
            ),
        ),
        "mediatek/mt6886" to Spec(
            "4 nm", "64-bit ARMv9-A",
            listOf(
                Cluster("Cortex-A715", 3405, 2, 2800, MIN_MHZ),
                Cluster("Cortex-A510", 3398, 6, 2000, MIN_MHZ),
            ),
        ),
        "qualcomm/sm7325" to Spec(
            "6 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-A78", 3393, 2, 2400, MIN_MHZ),
                Cluster("Cortex-A78", 3393, 2, 2200, MIN_MHZ),
                Cluster("Cortex-A55", 3333, 4, 1900, MIN_MHZ),
            ),
        ),
        "qualcomm/sm8550" to Spec(
            "4 nm", "64-bit ARMv9-A",
            listOf(
                Cluster("Cortex-X3", 3406, 2, 3200, MIN_MHZ),
                Cluster("Cortex-A715", 3405, 4, 2800, MIN_MHZ),
                Cluster("Cortex-A510", 3398, 2, 2000, MIN_MHZ),
            ),
        ),
        "qualcomm/sm7150" to Spec(
            "8 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-A76", 3339, 2, 2200, MIN_MHZ),
                Cluster("Cortex-A55", 3333, 6, 1800, MIN_MHZ),
            ),
        ),
        "qualcomm/sm6225" to Spec(
            "6 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-A73", 3337, 4, 2400, MIN_MHZ),
                Cluster("Cortex-A53", 3331, 4, 1900, MIN_MHZ),
            ),
        ),
        "qualcomm/sm4350" to Spec(
            "8 nm", "64-bit ARMv8.2-A",
            listOf(
                Cluster("Cortex-A76", 3339, 2, 2000, MIN_MHZ),
                Cluster("Cortex-A55", 3333, 6, 1800, MIN_MHZ),
            ),
        ),
        "qualcomm/sm8750" to Spec(
            "3 nm", "64-bit ARMv9.2-A",
            listOf(
                Cluster("Oryon V2 Phoenix L", 3461, 2, 4320, 400),
                Cluster("Oryon V2 Phoenix M", 3461, 6, 3530, 400),
            ),
        ),
    )

    fun forArtifact(artifact: DoppeliskArtifact): Spec? = forHardware(artifact.identity.hardware)

    fun forHardware(hardware: HardwareIdentity): Spec? =
        SPECS[hardware.soc.trim().lowercase(Locale.ROOT)]

    /**
     * A `/proc/cpuinfo` that matches [artifact]'s SoC, or null when the SoC is not
     * in the table.
     */
    fun procCpuInfo(artifact: DoppeliskArtifact): String? {
        val spec = forArtifact(artifact) ?: return null
        val soc = RootHiding.socIdentity(artifact.identity.hardware)

        val hardware = soc.model.ifBlank {
            artifact.identity.hardware.cpuHardware.ifBlank { artifact.identity.build.board }
        }

        val architecture = if (spec.instructionSet.contains("ARMv9")) "9" else "8"
        val features = if (architecture == "9") {
            "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm " +
                "lrcpc dcpop asimddp i8mm bf16 sve sve2"
        } else {
            "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm " +
                "lrcpc dcpop asimddp"
        }

        val builder = StringBuilder(4096)
        var cpu = 0
        for (cluster in spec.clusters) {
            repeat(cluster.count) {
                builder.append("processor\t: ").append(cpu).append('\n')
                builder.append("BogoMIPS\t: 38.40\n")
                builder.append("Features\t: ").append(features).append('\n')
                builder.append("CPU implementer\t: 0x41\n")
                builder.append("CPU architecture: ").append(architecture).append('\n')
                builder.append("CPU variant\t: 0x1\n")
                builder.append("CPU part\t: 0x").append(Integer.toString(cluster.part, 16)).append('\n')
                builder.append("CPU revision\t: 0\n\n")
                cpu++
            }
        }
        builder.append("Hardware\t: ").append(hardware).append('\n')
        builder.append("Revision\t: 0000\n")
        builder.append("Serial\t\t: 0000000000000000\n")
        return builder.toString()
    }

    /** The `/sys/devices/system/cpu` files for [artifact]'s SoC. */
    fun sysfs(artifact: DoppeliskArtifact): Map<String, String> {
        val spec = forArtifact(artifact) ?: return emptyMap()
        val result = LinkedHashMap<String, String>()

        val last = spec.cores - 1
        result["/sys/devices/system/cpu/present"] = "0-$last\n"
        result["/sys/devices/system/cpu/possible"] = "0-$last\n"
        result["/sys/devices/system/cpu/online"] = "0-$last\n"
        result["/sys/devices/system/cpu/kernel_max"] = last.toString()

        var cpu = 0
        spec.clusters.forEachIndexed { index, cluster ->
            val clusterCpus = (cpu until cpu + cluster.count).toList()
            val clusterList = compactCpuList(clusterCpus)
            val available = availableFrequencies(cluster)
            val minKhz = cluster.minMhz * 1000
            val maxKhz = cluster.maxMhz * 1000
            val curKhz = midFrequency(cluster) * 1000

            repeat(cluster.count) {
                val base = "/sys/devices/system/cpu/cpu$cpu"
                result["$base/online"] = "1\n"
                // The first two clusters are the "big" ones on every layout in the
                // table, so they get the full capacity value.
                result["$base/cpu_capacity"] = if (index <= 1) "1024\n" else "512\n"
                result["$base/topology/core_id"] = cpu.toString()
                result["$base/topology/physical_package_id"] = "0"
                result["$base/topology/cluster_id"] = index.toString()
                result["$base/topology/core_siblings_list"] = clusterList
                result["$base/topology/thread_siblings_list"] = cpu.toString()
                result["$base/cpufreq/affected_cpus"] = clusterCpus.joinToString(" ")
                result["$base/cpufreq/related_cpus"] = clusterCpus.joinToString(" ")
                result["$base/cpufreq/cpuinfo_max_freq"] = "$maxKhz\n"
                result["$base/cpufreq/cpuinfo_min_freq"] = "$minKhz\n"
                result["$base/cpufreq/scaling_max_freq"] = "$maxKhz\n"
                result["$base/cpufreq/scaling_min_freq"] = "$minKhz\n"
                result["$base/cpufreq/scaling_cur_freq"] = "$curKhz\n"
                result["$base/cpufreq/scaling_available_frequencies"] = available
                cpu++
            }

            val policy = "/sys/devices/system/cpu/cpufreq/policy$index"
            result["$policy/affected_cpus"] = clusterCpus.joinToString(" ")
            result["$policy/related_cpus"] = clusterCpus.joinToString(" ")
            result["$policy/cpuinfo_max_freq"] = "$maxKhz\n"
            result["$policy/cpuinfo_min_freq"] = "$minKhz\n"
            result["$policy/scaling_max_freq"] = "$maxKhz\n"
            result["$policy/scaling_min_freq"] = "$minKhz\n"
            result["$policy/scaling_cur_freq"] = "$curKhz\n"
            result["$policy/scaling_available_frequencies"] = available
        }

        return result
    }

    /**
     * A `/proc/meminfo` with [ramMb] of total memory.
     *
     * The real file is read and every "amount of memory" line is scaled by the
     * same ratio, which keeps the free/used proportions believable. Lines that are
     * not sizes (e.g. `Hugepagesize`) are passed through untouched.
     */
    fun meminfo(ramMb: Int): String? {
        val targetKb = ramMb.toLong() * 1024
        if (targetKb <= 0) return null

        val real = runCatching { File("/proc/meminfo").readLines() }.getOrNull()
        if (real.isNullOrEmpty()) return null

        val realTotal = real.firstNotNullOfOrNull { line ->
            if (line.substringBefore(':').trim() == "MemTotal") {
                line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()
            } else {
                null
            }
        } ?: return null
        if (realTotal <= 0) return null

        val ratio = targetKb.toDouble() / realTotal
        val out = StringBuilder(real.sumOf { it.length + 1 })

        for (line in real) {
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull()

            when {
                key == "MemTotal" -> out.append("MemTotal:       ").append(targetKb).append(" kB\n")
                value == null || key !in SCALED_KEYS -> out.append(line).append('\n')
                else -> {
                    val fake = maxOf(0L, (value * ratio).toLong())
                    out.append(key).append(": ").append(fake).append(" kB\n")
                }
            }
        }
        return out.toString()
    }

    private val SCALED_KEYS = setOf(
        "MemFree", "MemAvailable", "Buffers", "Cached", "SwapCached", "Active", "Inactive",
        "Active(anon)", "Inactive(anon)", "Active(file)", "Inactive(file)", "Unevictable",
        "Mlocked", "SwapTotal", "SwapFree", "Dirty", "Writeback", "AnonPages", "Mapped",
        "Shmem", "Slab", "SReclaimable", "SUnreclaim", "KernelStack", "PageTables",
        "NFS_Unstable", "Bounce", "WritebackTmp", "CommitLimit", "Committed_AS",
        "VmallocUsed", "Percpu", "HardwareCorrupted", "AnonHugePages", "ShmemHugePages",
        "ShmemPmdMapped", "FileHugePages", "FilePmdMapped", "CmaTotal", "CmaFree",
        "Unaccepted", "Balloon",
    )

    private fun availableFrequencies(cluster: Cluster): String {
        val min = cluster.minMhz * 1000
        val max = cluster.maxMhz * 1000
        val mid = midFrequency(cluster) * 1000
        val values = if (mid <= min || mid >= max) "$min" else "$min $mid"
        return "$values $max\n"
    }

    private fun midFrequency(cluster: Cluster): Int = (cluster.minMhz + cluster.maxMhz) / 2

    /** `[0,1,2,3,4,5,6,7]` → `0-3,4-7`. */
    private fun compactCpuList(cpus: List<Int>): String {
        if (cpus.isEmpty()) return ""

        val builder = StringBuilder()
        var start = cpus.first()
        var prev = start

        for (cpu in cpus.drop(1)) {
            if (cpu == prev + 1) {
                prev = cpu
            } else {
                emitRange(builder, start, prev)
                start = cpu
                prev = cpu
            }
        }
        emitRange(builder, start, prev)
        return builder.toString()
    }

    private fun emitRange(builder: StringBuilder, from: Int, to: Int) {
        if (builder.isNotEmpty()) builder.append(',')
        if (from == to) {
            builder.append(from)
        } else {
            builder.append(from).append('-').append(to)
        }
    }

    data class Cluster(
        val name: String,
        val part: Int,
        val count: Int,
        val maxMhz: Int,
        val minMhz: Int,
    )

    data class Spec(
        val process: String,
        val instructionSet: String,
        val clusters: List<Cluster>,
    ) {
        val cores: Int = maxOf(clusters.sumOf { it.count }, 1)
        val maxMhz: Int = clusters.maxOfOrNull { it.maxMhz } ?: 0
        val minMhz: Int = clusters.minOfOrNull { it.minMhz } ?: 0
    }
}
