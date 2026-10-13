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
import de.dgstudios.iptvstream.core.util.GermanGuide
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
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap
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
    private val germanMode: suspend () -> String = { GermanGuide.FALLBACK },
) {
    private val _state = MutableStateFlow(EpgState())
    val state: StateFlow<EpgState> = _state

    private val queue = ProfileQueue<EpgRequest>(
        scope = scope,
        key = { it.profileId },
        merge = { queued, incoming ->
            EpgRequest(
                profileId = queued.profileId,
                force = queued.force || incoming.force,
                maxAgeMs = minOf(queued.maxAgeMs, incoming.maxAgeMs),
            )
        },
    )
    private val guide = ConcurrentHashMap<GuideKey, GuideEntry>()

    private data class GuideKey(val profileId: Long, val epgKey: String)
    private data class GuideEntry(val value: NowNext, val until: Long)

    fun epgUrlFor(p: ProfileEntity): String? = when {
        p.epgUrl.isNotBlank() -> p.epgUrl
        p.type == ProfileType.XTREAM -> Urls.xmltv(p.url, p.username, p.password)
        else -> null
    }

    /** Startet eine Aktualisierung im Hintergrund. Ein laufender Abruf wird zu Ende gebracht, dieser danach. */
    fun launchRefresh(profileId: Long) {
        scope.launch { refreshIfStale(profileId, maxAgeMs = 0L, force = true) }
    }

    /** Startet nur, wenn die letzte Aktualisierung länger als [maxAgeMs] zurückliegt. */
    fun launchRefreshIfStale(profileId: Long, maxAgeMs: Long) {
        scope.launch { refreshIfStale(profileId, maxAgeMs, force = false) }
    }

    /** Wartet, bis dieser Abruf (oder ein zusammengelegter) fertig ist. */
    suspend fun refreshIfStale(profileId: Long, maxAgeMs: Long, force: Boolean = false) {
        queue.submit(EpgRequest(profileId, force, maxAgeMs)) { req ->
            val p = db.profiles().get(req.profileId)?.let(ProfileCrypto::fromStorage) ?: return@submit
            if (!req.force && System.currentTimeMillis() - p.lastEpgSync <= req.maxAgeMs) return@submit
            runRefresh(p)
        }
    }

    private suspend fun runRefresh(p: ProfileEntity) {
        try {
            val mode = germanMode()
            val url = epgUrlFor(p)
            if (url == null && mode == GermanGuide.OFF) {
                _state.value = EpgState(error = "Keine EPG-Quelle hinterlegt.")
                return
            }
            var loaded = 0
            var failure: String? = null
            if (url != null) {
                _state.value = EpgState(running = true, fraction = -1f, message = "EPG wird geladen …")
                try {
                    withContext(Dispatchers.IO) { importXmltv(p, url, german = false) }
                    loaded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = Net.friendly(e)
                }
            }
            if (mode != GermanGuide.OFF) {
                _state.value = EpgState(running = true, fraction = -1f, message = "Deutscher Programmführer wird geladen …")
                try {
                    withContext(Dispatchers.IO) { importXmltv(p, GermanGuide.URL, german = true) }
                    loaded++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (failure == null) failure = Net.friendly(e)
                }
            } else {
                withContext(Dispatchers.IO) {
                    db.epg().clearGerman(p.id)
                    db.epg().clearGermanNames(p.id)
                }
            }
            if (loaded == 0) {
                _state.value = EpgState(error = "EPG-Fehler: " + (failure ?: "unbekannt"))
                return
            }
            db.profiles().setEpgSync(p.id, System.currentTimeMillis())
            _state.value = EpgState(running = true, fraction = 1f, message = "Sender werden zugeordnet …")
            rematch(p.id)
            dropGuide(p.id)
            _state.value = EpgState(
                running = false,
                fraction = 1f,
                message = if (failure == null) "EPG ist aktuell" else "EPG teilweise aktuell ($failure)",
            )
        } catch (e: CancellationException) {
            _state.value = EpgState()
            throw e
        } catch (e: Exception) {
            _state.value = EpgState(error = "EPG-Fehler: " + Net.friendly(e))
        }
    }

    private suspend fun importXmltv(p: ProfileEntity, url: String, german: Boolean) {
        val prefix = if (german) GermanGuide.PREFIX else ""
        val label = if (german) "Programmführer" else "EPG"
        val now = System.currentTimeMillis()
        val token = now
        // Vergangenheit für Zurückblicken behalten (Archivfenster bis 7 Tage).
        val minStop = now - 7 * 24 * 3_600_000L
        val maxStart = now + 72 * 3_600_000L
        val stage = EpgStaging.profileId(p.id)
        val epg = db.epg()
        var committed = false
        try {
            epg.clear(stage)
            epg.clearNames(stage)

            http.get(url).use { body ->
                val total = body.contentLength()
                val counting = CountingInputStream(body.byteStream())
                val input = maybeGunzip(counting)
                val parser = Xml.newPullParser()
                parser.setInput(input, null)

                val batch = ArrayList<EpgEntity>(1000)
                val names = HashMap<String, String>()
                var lastReport = 0L
                var event = parser.eventType

                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "channel" -> readChannel(parser, names, prefix)
                            "programme" -> {
                                val start = XmltvTime.parse(parser.getAttributeValue(null, "start"))
                                val stop = XmltvTime.parse(parser.getAttributeValue(null, "stop"))
                                val ch = parser.getAttributeValue(null, "channel")?.lowercase()?.let { prefix + it }
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
                                            profileId = stage,
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
                        flushProgrammes(batch)
                        currentCoroutineContext().ensureActive()
                    }
                    val t = System.nanoTime()
                    if (t - lastReport > 300_000_000L) {
                        lastReport = t
                        val bytes = counting.count
                        val mb = bytes / 1_048_576L
                        _state.value = if (total > 0) {
                            EpgState(true, (bytes.toFloat() / total).coerceAtMost(0.98f), "$label wird geladen … $mb MB")
                        } else {
                            EpgState(true, -1f, "$label wird geladen … $mb MB")
                        }
                    }
                    event = parser.next()
                }
                if (batch.isNotEmpty()) flushProgrammes(batch)
                // Der sichtbare Führer wird erst ersetzt, wenn die Datei vollständig gelesen wurde.
                db.withTransaction {
                    if (german) {
                        epg.clearGerman(p.id)
                        epg.clearGermanNames(p.id)
                        epg.promoteGerman(stage, p.id)
                    } else {
                        epg.clearProvider(p.id)
                        epg.clearProviderNames(p.id)
                        epg.promoteProvider(stage, p.id)
                    }
                    val nameRows = names.entries.map {
                        EpgNameEntity(p.id, if (german) GermanGuide.nameKey(it.key) else it.key, it.value)
                    }
                    for (chunk in nameRows.chunked(500)) epg.insertNames(chunk)
                }
                committed = true
            }
        } catch (t: Throwable) {
            if (!committed) abandonStaging(stage)
            if (!committed || t is CancellationException) throw t
        }
    }

    /** Neue Sendungen liegen unter der Zwischen-ID und sind in der Übersicht nicht sichtbar. */
    private suspend fun flushProgrammes(batch: ArrayList<EpgEntity>) {
        val rows = ArrayList(batch)
        batch.clear()
        if (rows.isEmpty()) return
        db.epg().insertAll(rows)
    }

    private suspend fun abandonStaging(stage: Long) {
        try {
            db.epg().clear(stage)
            db.epg().clearNames(stage)
        } catch (_: Exception) {
            // Aufräumen darf den eigentlichen Fehler nicht verdecken.
        }
    }

    private fun readChannel(p: XmlPullParser, names: MutableMap<String, String>, prefix: String) {
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
                    if (n.isNotEmpty()) names.putIfAbsent(n, prefix + id)
                }
            }
        }
        if (id != null) {
            val n = EpgMatcher.normalize(id)
            if (n.isNotEmpty()) names.putIfAbsent(n, prefix + id)
        }
    }

    /**
     * Ordnet Sender ohne direkten EPG-Treffer anhand normalisierter Namen zu
     * (HD/FHD/4K-Varianten, Ländervorsätze, Sonderzeichen werden ignoriert; das Land des Senders
     * muss zum Land des EPG-Eintrags passen). Deutsche Sender können zusätzlich aus dem
     * deutschen Programmführer bedient werden, je nach Einstellung nur als Ersatz oder bevorzugt.
     */
    suspend fun rematch(profileId: Long) = withContext(Dispatchers.IO) {
        val mode = germanMode()
        val epg = db.epg()
        val content = db.content()
        val all = epg.keysWithData(profileId)
        if (all.isEmpty()) return@withContext
        val providerHave = all.filterNot(GermanGuide::isKey).toHashSet()
        val germanHave = all.filter(GermanGuide::isKey).toHashSet()

        val byNorm = HashMap<String, MutableList<String>>()
        fun add(norm: String, key: String) {
            if (norm.isEmpty()) return
            val list = byNorm.getOrPut(norm) { ArrayList(2) }
            if (key !in list) list.add(key)
        }
        for (k in providerHave) add(EpgMatcher.normalize(k), k)

        val german = HashMap<String, String>()
        for (k in germanHave) {
            val n = EpgMatcher.normalize(k.removePrefix(GermanGuide.PREFIX))
            if (n.isNotEmpty()) german.putIfAbsent(n, k)
        }
        for (n in epg.names(profileId)) {
            if (GermanGuide.isNameKey(n.normName)) {
                if (n.channelKey in germanHave) german.putIfAbsent(n.normName.removePrefix(GermanGuide.PREFIX), n.channelKey)
            } else if (n.channelKey in providerHave) {
                add(n.normName, n.channelKey)
            }
        }

        val channels = content.channelEpgInfo(profileId)
        db.withTransaction {
            for (c in channels) {
                val country = EpgMatcher.countryOf(c.name) ?: EpgMatcher.countryOf(c.epgKey)
                val useGerman = mode != GermanGuide.OFF && germanHave.isNotEmpty() && GermanGuide.eligible(country, mode)
                var target: String? = null
                if (useGerman && mode == GermanGuide.PREFER) {
                    target = GermanGuide.match(c.epgKey, c.name, german)
                }
                if (target == null) {
                    if (c.epgKey in providerHave) continue
                    target = EpgMatcher.match(c.epgKey, c.name, byNorm)
                }
                if (target == null && useGerman) target = GermanGuide.match(c.epgKey, c.name, german)
                if (target == null && GermanGuide.isKey(c.epgKey) && (mode == GermanGuide.OFF || c.epgKey !in germanHave)) {
                    target = ""
                }
                if (target != null && target != c.epgKey) content.setEpgKey(profileId, c.streamId, target)
            }
        }
    }

    /** Treffer aus dem Speicher, solange die laufende Sendung noch nicht vorbei ist. */
    fun peek(profileId: Long, epgKey: String?, now: Long = System.currentTimeMillis()): NowNext? {
        if (epgKey.isNullOrBlank()) return NowNext(null, null)
        val hit = guide[GuideKey(profileId, epgKey)] ?: return null
        if (now >= hit.until) return null
        return hit.value
    }

    suspend fun nowNext(profileId: Long, epgKey: String?, now: Long): NowNext {
        if (epgKey.isNullOrBlank()) return NowNext(null, null)
        peek(profileId, epgKey, now)?.let { return it }
        val list = db.epg().upcoming(profileId, epgKey, now, 2)
        val value = EpgGuide.fromRows(list, now)
        store(profileId, epgKey, value, now)
        return value
    }

    /**
     * Lädt die ersten fehlenden Sender in einer Abfrage, damit die sichtbare Liste nicht jedes Logo einzeln fragt.
     * Nur die nächsten Stunden und nur wenige Sender, sonst landen bei „Alle“ hunderttausende Zeilen im Speicher.
     * Sender ohne Treffer bleiben ungespeichert und werden beim Anzeigen genau nachgefragt.
     */
    suspend fun warm(profileId: Long, keys: Collection<String>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val missing = keys.asSequence()
            .filter { it.isNotBlank() && peek(profileId, it, now) == null }
            .distinct()
            .take(EpgGuide.WARM_KEYS)
            .toList()
        if (missing.isEmpty()) return@withContext
        val rows = db.epg().upcomingForKeys(profileId, missing, now, now + EpgGuide.WARM_WINDOW_MS)
        for ((key, value) in EpgGuide.group(rows, missing, now)) {
            if (EpgGuide.hasData(value)) store(profileId, key, value, now)
        }
    }

    private fun store(profileId: Long, epgKey: String, value: NowNext, now: Long) {
        guide[GuideKey(profileId, epgKey)] = GuideEntry(value, EpgGuide.freshUntil(value, now))
    }

    private fun dropGuide(profileId: Long) {
        guide.keys.removeAll { it.profileId == profileId }
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
