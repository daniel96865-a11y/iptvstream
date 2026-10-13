package de.dgstudios.iptvstream.core.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlanTest {
    @Test
    fun fullRequestReplacesAPartialOne() {
        val queued = SyncRequest(4, setOf(SyncSection.SERIES))
        val incoming = SyncRequest(4, null)
        assertEquals(null, SyncPlan.merge(queued, incoming).sections)
    }

    @Test
    fun partialRequestsAreJoined() {
        val queued = SyncRequest(4, setOf(SyncSection.MOVIE))
        val incoming = SyncRequest(4, setOf(SyncSection.SERIES))
        assertEquals(setOf(SyncSection.MOVIE, SyncSection.SERIES), SyncPlan.merge(queued, incoming).sections)
    }

    @Test
    fun pendingRoundTrip() {
        val raw = SyncPlan.format(setOf(SyncSection.SERIES, SyncSection.LIVE))
        assertEquals("live,series", raw)
        assertEquals(setOf(SyncSection.LIVE, SyncSection.SERIES), SyncPlan.parse(raw))
        assertTrue(SyncPlan.parse(null).isEmpty())
        assertTrue(SyncPlan.parse("nope").isEmpty())
    }

    @Test
    fun partialMessageNamesTheMissingSection() {
        val text = SyncPlan.message(setOf(SyncSection.SERIES), "Zeitüberschreitung")
        assertEquals(
            "Live-TV und Filme sind aktuell. Serien konnten nicht geladen werden (Zeitüberschreitung) und werden erneut geladen.",
            text,
        )
    }

    @Test
    fun totalFailureDoesNotClaimSuccess() {
        val text = SyncPlan.message(SyncPlan.ALL, null)
        assertEquals(
            "Live-TV, Filme und Serien konnten nicht geladen werden und werden erneut geladen.",
            text,
        )
    }

    @Test
    fun stagingIdStaysAwayFromTheRealProfile() {
        assertEquals(-7L, EpgStaging.profileId(7))
    }

    @Test
    fun unknownCategoryFallsBackToAll() {
        assertEquals(Cat.ALL, Cat.restoreCategory("12", setOf("3")))
        assertEquals("12", Cat.restoreCategory("12", setOf("12")))
        assertEquals(Cat.FAV, Cat.restoreCategory(Cat.FAV, emptySet()))
        assertEquals(Cat.ALL, Cat.restoreCategory(null, setOf("1")))
    }

    @Test
    fun secondProfileRunsAfterTheCurrentOne() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val queue = ProfileQueue<Long>(scope, key = { it }, merge = { _, incoming -> incoming })
        val order = mutableListOf<Long>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            queue.submit(1L) {
                order.add(it)
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        val second = async { queue.submit(2L) { order.add(it) } }
        delay(150)
        assertEquals(listOf(1L), order.toList())
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf(1L, 2L), order)
    }

    @Test
    fun waitingRequestsForTheSameProfileCollapse() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val queue = ProfileQueue<SyncRequest>(scope, key = { it.profileId }, merge = SyncPlan::merge)
        val seen = mutableListOf<Set<SyncSection>?>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            queue.submit(SyncRequest(1, setOf(SyncSection.LIVE))) {
                seen.add(it.sections)
                started.complete(Unit)
                release.await()
            }
        }
        started.await()
        val movie = async {
            queue.submit(SyncRequest(1, setOf(SyncSection.MOVIE))) { seen.add(it.sections) }
        }
        val series = async {
            queue.submit(SyncRequest(1, setOf(SyncSection.SERIES))) { seen.add(it.sections) }
        }
        delay(150)
        release.complete(Unit)
        first.await()
        movie.await()
        series.await()
        assertEquals(listOf(setOf(SyncSection.LIVE), setOf(SyncSection.MOVIE, SyncSection.SERIES)), seen)
    }
}
