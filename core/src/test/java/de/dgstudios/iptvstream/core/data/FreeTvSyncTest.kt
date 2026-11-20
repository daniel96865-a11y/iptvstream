package de.dgstudios.iptvstream.core.data

import androidx.room.Room
import de.dgstudios.iptvstream.core.data.db.AppDatabase
import de.dgstudios.iptvstream.core.data.db.CatType
import de.dgstudios.iptvstream.core.data.db.ProfileEntity
import de.dgstudios.iptvstream.core.data.db.ProfileType
import de.dgstudios.iptvstream.core.data.remote.HttpService
import de.dgstudios.iptvstream.core.data.remote.XtreamApi
import de.dgstudios.iptvstream.core.settings.SettingsStore
import de.dgstudios.iptvstream.core.util.StreamHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Der Weg Liste laden → Datenbank für „Freie Sender“, mit einer gefälschten Netzschicht. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeTvSyncTest {
    private lateinit var db: AppDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files = HashMap<String, String>()

    private val http = object : HttpService {
        override suspend fun get(url: String) = (files[url] ?: throw IOException("offline: $url"))
            .toResponseBody("audio/x-mpegurl".toMediaType())
    }

    private lateinit var repo: ContentRepository

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ContentRepository(db, http, XtreamApi(http), EpgManager(db, http, scope), scope, SettingsStore(app))
        files[FreeTv.listUrl("deu")] = """
            #EXTM3U
            #EXTINF:-1 tvg-id="3sat.de@SD" group-title="General",3sat
            https://3sat.example/a.m3u8
            #EXTINF:-1 tvg-id="Tagesschau24.de@SD" group-title="News",Tagesschau 24
            https://tagesschau.example/a.m3u8
        """.trimIndent()
        files[FreeTv.listUrl("ita")] = """
            #EXTM3U
            #EXTINF:-1 tvg-id="Rai1.it@SD" group-title="Undefined",Rai 1 (1080p)
            https://rai.example/1.m3u8
            #EXTINF:-1 tvg-id="RaiNews24.it@SD" group-title="News",Rai News 24 (720p)
            #EXTVLCOPT:http-referrer=https://www.raiplay.it/
            https://rai.example/news.m3u8
        """.trimIndent()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newProfile(languages: String, options: String = ""): Long =
        db.profiles().insert(ProfileEntity(name = "Freie Sender", type = ProfileType.FREE, url = languages, username = options))

    @Test
    fun loadsTwoLanguagesIntoGroupedChannels() = runBlocking {
        val id = newProfile("deu,ita")
        repo.sync(id)
        val ch = db.content().allChannels(id).first()
        assertEquals(4, ch.size)
        val cats = db.content().categories(id, CatType.LIVE).first().map { it.name }
        assertTrue(cats.toString(), "Deutsch · Allgemein" in cats && "Deutsch · Nachrichten" in cats)
        assertTrue(cats.toString(), "Italienisch · Nachrichten" in cats && "Italienisch · Sonstige" in cats)
        // Deutsch zuerst, danach Italienisch.
        assertEquals("3sat", ch.minByOrNull { it.num }!!.name)
        val news = ch.first { it.name.startsWith("Rai News") }
        assertEquals("https://www.raiplay.it/", StreamHeaders.parse(news.directUrl!!).referrer)
        // Die Abspiel-URL ist http(s) und für den Player gültig.
        val item = repo.liveItem(repo.profile(id)!!, news, "ts")
        assertTrue(item.url.startsWith("https://rai.example/news.m3u8"))
        assertNotNull(db.profiles().get(id)?.lastSync?.takeIf { it > 0 })
        Unit
    }

    @Test
    fun oneFailedLanguageKeepsTheOthersAndOldChannels() = runBlocking {
        val id = newProfile("deu,ita")
        repo.sync(id)
        assertEquals(4, db.content().allChannels(id).first().size)
        // Italienisch fällt aus: Es wird nichts gelöscht, damit keine Sender verloren gehen.
        files.remove(FreeTv.listUrl("ita"))
        files[FreeTv.listUrl("deu")] = "#EXTM3U\n#EXTINF:-1 group-title=\"General\",3sat\nhttps://3sat.example/a.m3u8\n"
        repo.sync(id)
        val names = db.content().allChannels(id).first().map { it.name }
        assertTrue(names.toString(), "Rai 1 (1080p)" in names && "3sat" in names)
        assertTrue("Tagesschau 24" in names)
        assertTrue(repo.syncState.value.error?.contains("Italienisch") == true)
    }

    @Test
    fun everythingOfflineKeepsOldListAndShowsError() = runBlocking {
        val id = newProfile("deu")
        repo.sync(id)
        assertEquals(2, db.content().allChannels(id).first().size)
        files.clear()
        repo.sync(id)
        assertEquals(2, db.content().allChannels(id).first().size)
        assertNotNull(repo.syncState.value.error)
    }

    @Test
    fun geoBlockedAreHiddenOnRequest() = runBlocking {
        files[FreeTv.listUrl("deu")] = "#EXTM3U\n#EXTINF:-1 group-title=\"General\",Sender A\nhttps://a.example/1.m3u8\n" +
            "#EXTINF:-1 group-title=\"General\",Sender B [Geo-blocked]\nhttps://a.example/2.m3u8\n"
        val id = newProfile("deu", FreeTv.NO_GEO)
        repo.sync(id)
        assertEquals(listOf("Sender A"), db.content().allChannels(id).first().map { it.name })
    }
}
