package de.dgstudios.iptvstream.core.update

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.InstallException
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Prüft den Update-Ablauf der Play-Variante mit dem FakeAppUpdateManager von Play Core. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerPlayTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var fake: FakeAppUpdateManager
    private lateinit var scope: CoroutineScope
    private val launched = mutableListOf<IntentSenderRequest>()

    private val launcher = object : ActivityResultLauncher<IntentSenderRequest>() {
        override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {
            launched += input
        }

        override fun unregister() = Unit

        override val contract: ActivityResultContract<IntentSenderRequest, *> =
            ActivityResultContracts.StartIntentSenderForResult()
    }

    @Before
    fun setUp() {
        app.getSharedPreferences("iptvstream_update", Context.MODE_PRIVATE).edit().clear().apply()
        fake = FakeAppUpdateManager(app)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun settle() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun checker(manager: AppUpdateManager = fake, feed: (suspend () -> UpdateInfo)? = null) =
        UpdateChecker(app, OkHttpClient(), scope, manager, feed)

    private fun UpdateChecker.openStartScreen() {
        onStartScreenVisible()
        settle()
    }

    private fun CoroutineScope.launchJob(block: suspend () -> Unit): Job = launch { block() }

    @Test
    fun noUpdateStaysSilent() {
        fake.setUpdateNotAvailable()
        val c = checker()
        c.openStartScreen()
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
    }

    @Test
    fun manualCheckSaysUpToDate() {
        fake.setUpdateNotAvailable()
        val c = checker()
        c.checkNow()
        settle()
        val p = c.prompt.value as UpdatePrompt.Message
        assertTrue(p.text.contains("aktuelle Version"))
    }

    @Test
    fun availableUpdateIsOfferedOnStartScreenOnly() {
        fake.setUpdateAvailable(9999)
        val c = checker()
        // Nicht auf dem Startbildschirm: kein Hinweis.
        c.maybeCheck()
        settle()
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
        // Startbildschirm: Hinweis erscheint.
        c.openStartScreen()
        val offer = c.prompt.value as UpdatePrompt.Offer
        assertEquals(9999, offer.info.versionCode)
        assertFalse(offer.downloading)
        assertFalse(offer.viaStore)
    }

    @Test
    fun laterHidesUntilNextVisit() {
        fake.setUpdateAvailable(9999)
        val c = checker()
        c.openStartScreen()
        assertTrue(c.prompt.value is UpdatePrompt.Offer)
        c.later()
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
        c.onStartScreenResume()
        settle()
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
        // Neuer Start: wieder anbieten.
        c.onStartScreenHidden()
        c.onAppBackground()
        c.openStartScreen()
        assertTrue(c.prompt.value is UpdatePrompt.Offer)
    }

    @Test
    fun flexibleUpdateRunsThroughPlayAndAsksForRestart() {
        fake.setUpdateAvailable(9999)
        val c = checker()
        val actions = mutableListOf<UpdateAction>()
        val job = scope.launchJob { c.actions.collect { actions += it } }
        settle()
        c.openStartScreen()

        c.confirm()
        settle()
        val start = actions.single() as UpdateAction.StartFlow
        assertEquals(AppUpdateType.FLEXIBLE, start.type)
        assertTrue((c.prompt.value as UpdatePrompt.Offer).downloading)

        c.startFlow(start, launcher)
        assertTrue(fake.isConfirmationDialogVisible)
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        settle()
        assertTrue((c.prompt.value as UpdatePrompt.Offer).downloading)

        fake.downloadCompletes()
        settle()
        assertEquals(UpdatePrompt.ReadyToRestart, c.prompt.value)

        c.confirm()
        settle()
        assertTrue(fake.isInstallSplashScreenVisible)
        fake.installCompletes()
        settle()
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
        job.cancel()
    }

    @Test
    fun declinePlayDialogHidesOffer() {
        fake.setUpdateAvailable(9999)
        val c = checker()
        c.openStartScreen()
        c.confirm()
        settle()
        c.onFlowResult(Activity.RESULT_CANCELED)
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
    }

    @Test
    fun failedFlowFallsBackToStoreHint() {
        fake.setUpdateAvailable(9999)
        val c = checker()
        c.openStartScreen()
        c.confirm()
        settle()
        c.onFlowResult(ActivityResult.RESULT_IN_APP_UPDATE_FAILED)
        val offer = c.prompt.value as UpdatePrompt.Offer
        assertTrue(offer.viaStore)
    }

    @Test
    fun highPriorityUsesImmediateUpdate() {
        fake.setUpdateAvailable(9999)
        fake.setUpdatePriority(5)
        val c = checker()
        val actions = mutableListOf<UpdateAction>()
        val job = scope.launchJob { c.actions.collect { actions += it } }
        settle()
        c.openStartScreen()
        c.confirm()
        settle()
        assertEquals(AppUpdateType.IMMEDIATE, (actions.single() as UpdateAction.StartFlow).type)
        job.cancel()
    }

    @Test
    fun downloadedUpdateIsOfferedAgainOnNextStart() {
        fake.setUpdateAvailable(9999)
        val c = checker()
        c.openStartScreen()
        c.confirm()
        settle()
        val info = fake.appUpdateInfo.result
        c.startFlow(UpdateAction.StartFlow(info, AppUpdateType.FLEXIBLE), launcher)
        fake.userAcceptsUpdate()
        fake.downloadStarts()
        c.later()
        fake.downloadCompletes()
        settle()
        // „Später“ gedrückt: Hinweis bleibt weg …
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
        // … kommt aber beim nächsten Start wieder.
        c.onStartScreenHidden()
        c.onAppBackground()
        c.openStartScreen()
        assertEquals(UpdatePrompt.ReadyToRestart, c.prompt.value)
    }

    @Test
    fun tvWithoutInAppUpdatesPointsToPlayStore() {
        val unsupported = object : AppUpdateManager by fake {
            override fun getAppUpdateInfo(): Task<AppUpdateInfo> =
                Tasks.forException(InstallException(InstallErrorCode.ERROR_API_NOT_AVAILABLE))
        }
        val c = checker(unsupported, feed = { UpdateInfo(99999, "9.9.9", "", "", "") })
        val actions = mutableListOf<UpdateAction>()
        val job = scope.launchJob { c.actions.collect { actions += it } }
        settle()
        c.openStartScreen()
        val offer = c.prompt.value as UpdatePrompt.Offer
        assertTrue(offer.viaStore)
        assertEquals("9.9.9", offer.info.versionName)

        c.confirm()
        settle()
        assertEquals(UpdateAction.OpenStore, actions.single())
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
        job.cancel()
    }

    @Test
    fun tvWithCurrentVersionStaysSilentButManualCheckAnswers() {
        val unsupported = object : AppUpdateManager by fake {
            override fun getAppUpdateInfo(): Task<AppUpdateInfo> =
                Tasks.forException(InstallException(InstallErrorCode.ERROR_API_NOT_AVAILABLE))
        }
        val c = checker(unsupported, feed = { UpdateInfo(installedVersionCode(app).toInt(), "0.0.1", "", "", "") })
        c.openStartScreen()
        assertEquals(UpdatePrompt.Hidden, c.prompt.value)
        c.checkNow()
        settle()
        assertTrue((c.prompt.value as UpdatePrompt.Message).text.contains("aktuelle Version"))
    }

    @Test
    fun brokenFeedOnManualCheckShowsError() {
        val unsupported = object : AppUpdateManager by fake {
            override fun getAppUpdateInfo(): Task<AppUpdateInfo> =
                Tasks.forException(InstallException(InstallErrorCode.ERROR_API_NOT_AVAILABLE))
        }
        val c = checker(unsupported, feed = { error("offline") })
        c.checkNow()
        settle()
        assertTrue((c.prompt.value as UpdatePrompt.Message).text.contains("nicht geprüft"))
    }

    @Test
    fun feedParserNeedsOnlyVersion() {
        val info = UpdateChecker.parseFeed("""{"versionCode":77,"versionName":"1.0.75","apkUrl":"https://x","sha256":"y"}""")
        assertEquals(77, info.versionCode)
        assertEquals("", info.apkUrl)
        assertTrue(UpdateChecker.feedUrl("de.dgstudios.iptvstream.tv").endsWith("iptvstream-tv.json"))
        assertTrue(UpdateChecker.feedUrl("de.dgstudios.iptvstream").endsWith("iptvstream-mobile.json"))
    }

    @Test
    fun storeIntentsTargetPlayStore() {
        val intents = UpdateChecker.storeIntents("de.dgstudios.iptvstream")
        assertEquals("market://details?id=de.dgstudios.iptvstream", intents[0].data.toString())
        assertEquals("com.android.vending", intents[0].`package`)
        assertNull(intents[1].`package`)
    }
}
