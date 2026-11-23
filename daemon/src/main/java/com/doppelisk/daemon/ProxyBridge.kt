package com.doppelisk.daemon

import android.os.ParcelFileDescriptor
import com.doppelisk.core.identity.Exit
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A loopback SOCKS5 client for one uid.
 *
 * `iptables` REDIRECTs a uid's outbound TCP into this server; the server asks
 * the kernel where the connection was originally going (`SO_ORIGINAL_DST` via
 * [NativeNet]), opens a SOCKS5 connection to the configured upstream exit, and
 * relays bytes. The app never learns it is proxied, and the upstream never sees
 * the device's real address.
 *
 * If the upstream dies, [onFatal] is invoked so the manager can decide whether
 * the kill switch should block the uid.
 */
class ProxyBridge(
    private val upstream: Exit,
    private val onFatal: () -> Unit,
) : Closeable {

    private val closed = AtomicBoolean(false)
    private val live: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val acceptThread: Thread

    /** Port the kernel should redirect to. */
    val port: Int get() = server.localPort

    init {
        acceptThread = Thread(::acceptLoop, "doppelisk-socks-$port").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val client = server.accept()
                live.add(client)
                Thread({ handle(client) }, "doppelisk-socks-conn").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
                if (closed.get()) return
                runCatching { onFatal() }
                return
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 15_000
            val descriptor = runCatching { ParcelFileDescriptor.fromSocket(client) }.getOrNull() ?: return
            try {
                val target = NativeNet.dst(descriptor.fd) ?: return
                val split = target.lastIndexOf(':')
                if (split <= 0) return

                val host = target.substring(0, split).trim('[', ']')
                val port = target.substring(split + 1).toIntOrNull() ?: return
                if (port !in 1..65535) return

                Socket().use { upstreamSocket ->
                    upstreamSocket.connect(InetSocketAddress(upstream.host, upstream.port), 10_000)
                    upstreamSocket.soTimeout = 15_000
                    handshake(upstreamSocket, host, port)
                    relay(client, upstreamSocket)
                }
            } finally {
                runCatching { descriptor.close() }
            }
        } catch (_: Throwable) {
            // A broken connection is not fatal: the app just sees a closed socket.
        } finally {
            live.remove(client)
            runCatching { client.close() }
        }
    }

    /** Performs the SOCKS5 greeting and CONNECT for [host]:[port]. */
    private fun handshake(socket: Socket, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val greeting = readN(input, 2)
        if (greeting[0] != 5.toByte() || greeting[1] != 0.toByte()) {
            throw IOException("SOCKS5 greeting rejected")
        }

        val address = InetAddress.getByName(host).address
        val request = ArrayList<Byte>(address.size + 8)
        request.add(5)
        request.add(1)
        when (address.size) {
            4 -> request.add(1)
            16 -> request.add(4)
            else -> throw IOException("unsupported target address")
        }
        address.forEach { request.add(it) }
        request.add(((port shr 8) and 0xFF).toByte())
        request.add((port and 0xFF).toByte())
        out.write(request.toByteArray())
        out.flush()

        val head = readN(input, 4)
        if (head[0] != 5.toByte() || head[1] != 0.toByte()) {
            throw IOException("SOCKS5 connect rejected (${head[1]})")
        }
        when (head[3].toInt()) {
            1 -> readN(input, 4)
            3 -> readN(input, readN(input, 1)[0].toInt() and 0xFF)
            4 -> readN(input, 16)
        }
        readN(input, 2)
    }

    /**
     * Pumps bytes in both directions until either side closes.
     *
     * The upstream→client direction runs on this thread so the caller's `use`
     * block does not close the upstream socket early; the client→upstream
     * direction runs on a helper thread.
     */
    private fun relay(client: Socket, upstream: Socket) {
        val up = Thread({
            copy(client.getInputStream(), upstream.getOutputStream())
            runCatching { upstream.shutdownOutput() }
        }, "doppelisk-socks-up").apply {
            isDaemon = true
            start()
        }

        copy(upstream.getInputStream(), client.getOutputStream())
        runCatching { client.shutdownOutput() }
        up.join(TimeUnit.SECONDS.toMillis(2))
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (!closed.get()) {
            val count = input.read(buffer)
            if (count < 0) return
            try {
                output.write(buffer, 0, count)
                output.flush()
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun readN(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read < 0) throw IOException("unexpected EOF")
            offset += read
        }
        return out
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        acceptThread.interrupt()
        for (socket in live) runCatching { socket.close() }
        live.clear()
    }
}
