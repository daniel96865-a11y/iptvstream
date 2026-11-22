package de.dgstudios.iptvstream.core.data

import androidx.room.Room
import de.dgstudios.iptvstream.core.data.db.AppDatabase
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.data.remote.HttpService
import de.dgstudios.iptvstream.core.data.remote.XtreamApi
import de.dgstudios.iptvstream.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * Misst die EPG-Abdeckung der Freien Sender mit echten, vorher heruntergeladenen Dateien.
 * Läuft nur, wenn EPG_COVERAGE_DIR gesetzt ist (Dateinamen = letzter Teil der URL).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgCoverageLiveTest {
    @Test
    fun coverage() = runBlocking {
        val dir = System.getenv("EPG_COVERAGE_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val langs = System.getenv("EPG_COVERAGE_LANGS") ?: "deu"
        val app = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val requested = ArrayList<String>()
        val http = object : HttpService {
            override suspend fun get(url: String): okhttp3.ResponseBody {
                requested += url
                val f = File(dir, url.substringAfterLast('/'))
                if (!f.isFile) throw IOException("fehlt: $url")
                return f.readBytes().toResponseBody(null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val mode = System.getenv("EPG_COVERAGE_MODE") ?: "fallback"
        val epg = EpgManager(db, http, scope) { mode }
        val repo = ContentRepository(db, http, XtreamApi(http), epg, scope, SettingsStore(app))
        val id = db.profiles().insert(ProfileEntity(name = "Freie Sender", type = ProfileType.FREE, url = langs))
        repo.sync(id)
        epg.refreshIfStale(id, 0L, force = true)
        val now = System.currentTimeMillis()
        val channels = db.content().allChannels(id).first()
        val out = StringBuilder()
        var withNow = 0
        var withAny = 0
        val byLang = HashMap<String, IntArray>()
        for (c in channels) {
            val rows = if (c.epgKey.isBlank()) emptyList() else db.epg().upcoming(id, c.epgKey, now, 2)
            val cur = rows.firstOrNull { it.start <= now && it.stop > now }
            if (rows.isNotEmpty()) withAny++
            if (cur != null) withNow++
            val lang = c.categoryId.substringBefore(" · ")
            val a = byLang.getOrPut(lang) { IntArray(2) }
            a[0]++
            if (cur != null) a[1]++
            out.append(if (cur != null) "OK  " else "--  ").append(c.name).append(" | ").append(c.epgKey)
                .append(" | ").append(cur?.title ?: "").append('\n')
        }
        val summary = "Sprachen=$langs Modus=$mode Sender=${channels.size} mitAktuellerSendung=$withNow mitDaten=$withAny " +
            "proGruppe=${byLang.entries.joinToString { "${it.key}:${it.value[1]}/${it.value[0]}" }} state=${epg.state.value}\nURLs=$requested\n"
        File(dir, "coverage-$langs-$mode.txt").writeText(summary + out)
        println(summary)
        db.close()
    }
}
