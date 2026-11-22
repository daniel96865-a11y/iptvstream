package de.dgstudios.iptvstream.core.data

import androidx.room.Room
import de.dgstudios.iptvstream.core.data.db.AppDatabase
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.data.remote.HttpService
import de.dgstudios.iptvstream.core.data.remote.XtreamApi
import de.dgstudios.iptvstream.core.settings.SettingsStore
import de.dgstudios.iptvstream.core.util.GermanGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** EPG der Freien Sender: deutscher Führer auch bei „Aus“, AT/CH gefiltert, Ausfall einer Datei. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeEpgTest {
    private val app = RuntimeEnvironment.getApplication()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files = HashMap<String, String>()
    private val http = object : HttpService {
        override suspend fun get(url: String) = (files[url] ?: throw IOException("offline: $url")).toResponseBody(null)
    }

    private fun t(ms: Long) = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    private fun guide(vararg channels: Pair<String, String>): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder("<?xml version=\"1.0\"?><tv>")
        for ((id, name) in channels) sb.append("<channel id=\"$id\"><display-name>$name</display-name></channel>")
        for ((id, name) in channels) {
            sb.append("<programme start=\"${t(now - 600_000)}\" stop=\"${t(now + 3_000_000)}\" channel=\"$id\"><title>Jetzt auf $name</title></programme>")
        }
        return sb.append("</tv>").toString()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun germanFreeChannelsGetGuideEvenWhenGermanGuideIsOff() = runBlocking {
        files[FreeTv.listUrl("deu")] = """
            #EXTM3U
            #EXTINF:-1 tvg-id="3sat.de@SD" group-title="General",3sat
            https://3sat.example/a.m3u8
            #EXTINF:-1 tvg-id="hrfernsehen.de@SD" group-title="General",hr-fernsehen (1080p)
            https://hr.example/a.m3u8
            #EXTINF:-1 tvg-id="ndrfernsehen.de@niedersachsen" group-title="General",NDR Fernsehen Niedersachsen (720p)
            https://ndr.example/a.m3u8
            #EXTINF:-1 tvg-id="okto.at@SD" group-title="General",Okto (1080p)
            https://okto.example/a.m3u8
            #EXTINF:-1 tvg-id="telezuri.ch@SD" group-title="General",TeleZuri (720p)
            https://tz.example/a.m3u8
        """.trimIndent()
        files[GermanGuide.URL] = guide("3sat.de" to "3sat", "HR.de" to "HR", "NDR.de" to "NDR")
        // AT-Datei mit einem fremden Sender, der nicht gespeichert werden soll.
        files[FreeTv.GERMAN_EXTRA_EPG[0]] = guide("OKTO.at" to "OKTO", "PULS.24.at" to "PULS 24", "3SAT.at" to "3SAT")
        // CH fällt aus: Die anderen Dateien bleiben trotzdem erhalten.
        val epg = EpgManager(db, http, scope) { GermanGuide.OFF }
        val repo = ContentRepository(db, http, XtreamApi(http), epg, scope, SettingsStore(app))
        val id = db.profiles().insert(ProfileEntity(name = "Freie Sender", type = ProfileType.FREE, url = "deu"))
        repo.sync(id)
        epg.refreshIfStale(id, 0L, force = true)

        val now = System.currentTimeMillis()
        val byName = db.content().allChannels(id).first().associateBy { it.name }
        suspend fun title(name: String) = epg.nowNext(id, byName.getValue(name).epgKey, now).now?.title
        assertEquals("Jetzt auf 3sat", title("3sat"))
        assertEquals("Jetzt auf HR", title("hr-fernsehen (1080p)"))
        assertEquals("Jetzt auf NDR", title("NDR Fernsehen Niedersachsen (720p)"))
        assertEquals("Jetzt auf OKTO", title("Okto (1080p)"))
        // Deutscher Sender bekommt den deutschen, nicht den österreichischen Führer.
        assertTrue(byName.getValue("3sat").epgKey, byName.getValue("3sat").epgKey.startsWith(GermanGuide.PREFIX))
        // Gefiltert: PULS 24 ist nicht in der Liste und wird nicht gespeichert.
        assertTrue("puls.24.at" !in db.epg().keysWithData(id))
    }
}
