package de.dgstudios.iptvstream.core.data

import android.util.Xml
import androidx.room.withTransaction
import de.dgstudios.iptvstream.core.data.db.AppDatabase
import de.dgstudios.iptvstream.core.data.db.EpgEntity
import de.dgstudios.iptvstream.core.data.db.EpgNameEntity
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileCrypto
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.data.remote.HttpService
import de.dgstudios.iptvstream.core.data.remote.Net
import de.dgstudios.iptvstream.core.util.EpgMatcher
import de.dgstudios.iptvstream.core.util.Urls
import de.dgstudios.iptvstream.core.util.XmltvTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Lädt XMLTV (oder Xtream-EPG) streamend in die lokale Datenbank. Die Daten bleiben nach
 * einem Neustart erhalten; die Aktualisierung läuft im Hintergrund.
 */
class EpgManager(
    private val db: AppDatabase,
    private val http: HttpService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(EpgState())
    val state: StateFlow<EpgState> = _state

    private val mutex = Mutex()

    fun epgUrlFor(p: ProfileEntity): String? = when {
        p.epgUrl.isNotBlank() -> p.epgUrl
        p.type == ProfileType.XTREAM -> Urls.xmltv(p.url, p.username, p.password)
        else -> null
    }

    /** Startet eine Aktualisierung im Hintergrund (falls noch keine läuft). */
    fun launchRefresh(profileId: Long) {
        scope.launch {
            val p = db.profiles().get(profileId)?.let(ProfileCrypto::fromStorage) ?: return@launch
            refresh(p)
        }
    }

    /** Startet nur, wenn die letzte Aktualisierung länger als [maxAgeMs] zurückliegt. */
    fun launchRefreshIfStale(profileId: Long, maxAgeMs: Long) {
        scope.launch {
            val p = db.profiles().get(profileId)?.let(ProfileCrypto::fromStorage) ?: return@launch
            if (System.currentTimeMillis() - p.lastEpgSync > maxAgeMs) refresh(p)
        }
    }

    suspend fun refresh(p: ProfileEntity) {
        if (!mutex.tryLock()) return
        try {
            val url = epgUrlFor(p)
            if (url == null) {
                _state.value = EpgState(error = "Keine EPG-Quelle hinterlegt.")
                return
            }
            _state.value = EpgState(running = true, fraction = -1f, message = "EPG wird geladen …")
            withContext(Dispatchers.IO) { importXmltv(p, url) }
            db.profiles().setEpgSync(p.id, System.currentTimeMillis())
            _state.value = EpgState(running = true, fraction = 1f, message = "Sender werden zugeordnet …")
            rematch(p.id)
            _state.value = EpgState(running = false, fraction = 1f, message = "EPG ist aktuell")
        } catch (e: CancellationException) {
            _state.value = EpgState()
            throw e
        } catch (e: Exception) {
            _state.value = EpgState(error = "EPG-Fehler: " + Net.friendly(e))
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun importXmltv(p: ProfileEntity, url: String) {
        val now = System.currentTimeMillis()
        val token = now
        // Vergangenheit für Zurückblicken behalten (Archivfenster bis 7 Tage).
        val minStop = now - 7 * 24 * 3_600_000L
        val maxStart = now + 72 * 3_600_000L
        val epg = db.epg()

        http.get(url).use { body ->
            val total = body.contentLength()
            val counting = CountingInputStream(body.byteStream())
            val input = maybeGunzip(counting)
            val parser = Xml.newPullParser()
            parser.setInput(input, null)

            val batch = ArrayList<EpgEntity>(1000)
            val names = HashMap<String, String>()
            var lastReport = 0L
            var replaced = false
            var event = parser.eventType

            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser, names)
                        "programme" -> {
                            val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
                            val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
                            val ch = parser.getAttributeValue(null, "channel")?.lowercase()
                            var title: String? = null
                            var desc: String? = null
                            val depth = parser.depth
                            while (true) {
                                val ev = parser.next()
                                if (ev == XmlPullParser.END_DOCUMENT) break
                                if (ev == XmlPullParser.END_TAG && parser.depth == depth) break
                                if (ev == XmlPullParser.START_TAG) {
                                    when (parser.name) {
                                        "title" -> {
                                            val t = parser.nextText()
                                            if (title == null) title = t
                                        }
                                        "desc" -> {
                                            val t = parser.nextText()
                                            if (desc == null) desc = t
                                        }
                                    }
                                }
                            }
                            val finalTitle = title?.trim()
                            if (start != null && stop != null && ch != null && !finalTitle.isNullOrEmpty() &&
                                stop > minStop && start < maxStart
                            ) {
                                batch.add(
                                    EpgEntity(
                                        profileId = p.id,
                                        channelKey = ch,
                                        start = start,
                                        stop = stop,
                                        title = finalTitle,
                                        description = desc?.trim()?.takeIf { it.isNotEmpty() },
                                        sync = token,
                                    ),
                                )
                            }
                        }
                    }
                }
                if (batch.size >= 1000) {
                    flushProgrammes(p.id, batch, replaced)
                    replaced = true
                    currentCoroutineContext().ensureActive()
                }
                val t = System.nanoTime()
                if (t - lastReport > 300_000_000L) {
                    lastReport = t
                    val bytes = counting.count
                    val mb = bytes / 1_048_576L
                    _state.value = if (total > 0) {
                        EpgState(true, (bytes.toFloat() / total).coerceAtMost(0.98f), "EPG wird geladen … $mb MB")
                    } else {
                        EpgState(true, -1f, "EPG wird geladen … $mb MB")
                    }
                }
                event = parser.next()
            }
            if (batch.isNotEmpty()) {
                flushProgrammes(p.id, batch, replaced)
            }
            epg.pruneBySync(p.id, token)

            epg.clearNames(p.id)
            val nameRows = names.entries.map { EpgNameEntity(p.id, it.key, it.value) }
            for (chunk in nameRows.chunked(500)) epg.insertNames(chunk)
        }
    }

    /**
     * Alte Sendungen des Profils werden vor dem ersten Einfügen gelöscht (eine Transaktion),
     * damit ein Abbruch mitten im Import keine Mischung aus alt und neu hinterlässt.
     */
    private suspend fun flushProgrammes(profileId: Long, batch: ArrayList<EpgEntity>, alreadyReplaced: Boolean) {
        val rows = ArrayList(batch)
        batch.clear()
        if (rows.isEmpty()) return
        if (!alreadyReplaced) {
            db.withTransaction {
                db.epg().clear(profileId)
                db.epg().insertAll(rows)
            }
        } else {
            db.epg().insertAll(rows)
        }
    }

    private fun readChannel(p: XmlPullParser, names: MutableMap<String, String>) {
        val id = p.getAttributeValue(null, "id")?.lowercase()
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && p.depth == depth) break
            if (ev == XmlPullParser.START_TAG && p.name == "display-name") {
                val text = p.nextText()
                if (id != null) {
                    val n = EpgMatcher.normalize(text)
                    if (n.isNotEmpty()) names.putIfAbsent(n, id)
                }
            }
        }
        if (id != null) {
            val n = EpgMatcher.normalize(id)
            if (n.isNotEmpty()) names.putIfAbsent(n, id)
        }
    }

    /**
     * Ordnet Sender ohne direkten EPG-Treffer anhand normalisierter Namen zu
     * (HD/FHD/4K-Varianten, Ländervorsätze, Sonderzeichen werden ignoriert).
     */
    suspend fun rematch(profileId: Long) = withContext(Dispatchers.IO) {
        val epg = db.epg()
        val content = db.content()
        val have = epg.keysWithData(profileId).toHashSet()
        if (have.isEmpty()) return@withContext
        val byNorm = HashMap<String, String>()
        for (k in have) {
            val n = EpgMatcher.normalize(k)
            if (n.isNotEmpty()) byNorm.putIfAbsent(n, k)
        }
        for (n in epg.names(profileId)) {
            if (n.channelKey in have) byNorm.putIfAbsent(n.normName, n.channelKey)
        }
        val channels = content.channelEpgInfo(profileId)
        db.withTransaction {
            for (c in channels) {
                if (c.epgKey in have) continue
                val m = EpgMatcher.match(c.epgKey, c.name, byNorm)
                if (m != null && m != c.epgKey) content.setEpgKey(profileId, c.streamId, m)
            }
        }
    }

    suspend fun nowNext(profileId: Long, epgKey: String?, now: Long): NowNext {
        if (epgKey.isNullOrBlank()) return NowNext(null, null)
        val list = db.epg().upcoming(profileId, epgKey, now, 2)
        val cur = list.firstOrNull()?.takeIf { it.start <= now }
        val next = if (cur != null) list.getOrNull(1) else list.firstOrNull()
        return NowNext(cur, next)
    }

    suspend fun schedule(profileId: Long, epgKey: String?, from: Long, to: Long): List<EpgEntity> {
        if (epgKey.isNullOrBlank()) return emptyList()
        return db.epg().range(profileId, epgKey, from, to)
    }

    private fun maybeGunzip(source: InputStream): InputStream {
        val buf = BufferedInputStream(source, 64 * 1024)
        buf.mark(2)
        val b1 = buf.read()
        val b2 = buf.read()
        buf.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(buf, 64 * 1024) else buf
    }

    private class CountingInputStream(source: InputStream) : FilterInputStream(source) {
        @Volatile
        var count: Long = 0
            private set

        override fun read(): Int {
            val r = super.read()
            if (r >= 0) count++
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val r = super.read(b, off, len)
            if (r > 0) count += r
            return r
        }
    }
}
