package de.dgstudios.iptvstream.core.data.remote

import de.dgstudios.iptvstream.core.util.ContainerDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Liest die Laufzeit mit einem kurzen Bereichs-Abruf und legt danach auf.
 * Die Verbindung bleibt nicht als Stream offen.
 */
object MediaDuration {
    private val cache = ConcurrentHashMap<String, Long>()

    suspend fun probe(client: OkHttpClient, url: String): Long? {
        cache[url]?.let { return it }
        val ms = read(client, url) ?: return null
        cache[url] = ms
        return ms
    }

    private suspend fun read(client: OkHttpClient, url: String): Long? = withContext(Dispatchers.IO) {
        val head = fetch(client, url, 0, HEAD_BYTES - 1) ?: return@withContext null
        ContainerDuration.read(head.bytes)?.let { return@withContext it }
        val total = head.total
        if (total == null || total <= head.bytes.size.toLong()) return@withContext null
        val start = (total - TAIL_BYTES).coerceAtLeast(0L)
        val tail = fetch(client, url, start, total - 1) ?: return@withContext null
        if (!tail.partial) return@withContext null
        ContainerDuration.read(tail.bytes)
    }

    private suspend fun fetch(client: OkHttpClient, url: String, start: Long, end: Long): Chunk? =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=$start-$end")
                    .header("User-Agent", Net.USER_AGENT)
                    .build(),
            )
            cont.invokeOnCancellation { call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful && response.code != 206) {
                        cont.resume(null)
                        return@use
                    }
                    val wanted = (end - start + 1).coerceAtMost(TAIL_BYTES)
                    val body = response.body
                    if (body == null) {
                        cont.resume(null)
                        return@use
                    }
                    val bytes = body.byteStream().use { input ->
                        val out = ByteArray(wanted.toInt())
                        var filled = 0
                        while (filled < out.size) {
                            val n = input.read(out, filled, out.size - filled)
                            if (n < 0) break
                            filled += n
                        }
                        if (filled == out.size) out else out.copyOf(filled)
                    }
                    val total = totalLength(response.header("Content-Range"))
                        ?: response.header("Content-Length")?.toLongOrNull()?.takeIf { response.code == 200 }
                    cont.resume(Chunk(bytes, response.code == 206, total))
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    fun totalLength(contentRange: String?): Long? {
        val total = contentRange?.substringAfter('/', "")?.trim()?.takeIf { it.isNotEmpty() && it != "*" }
        return total?.toLongOrNull()?.takeIf { it > 0 }
    }

    private data class Chunk(val bytes: ByteArray, val partial: Boolean, val total: Long?)

    private const val HEAD_BYTES = 1024L * 1024L
    private const val TAIL_BYTES = 2L * 1024L * 1024L
}
