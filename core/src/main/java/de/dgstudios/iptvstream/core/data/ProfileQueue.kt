package de.dgstudios.iptvstream.core.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Arbeitet Aufträge nacheinander ab. Ein Auftrag für ein Profil, das noch wartet,
 * wird mit [merge] zusammengelegt. Läuft das Profil schon, kommt der neue Auftrag danach dran.
 * Der Aufrufer wartet, bis sein Auftrag fertig ist. Bricht der Aufrufer ab, läuft die Schlange weiter.
 */
internal class ProfileQueue<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Long,
    private val merge: (queued: T, incoming: T) -> T,
) {
    private class Slot<T>(
        val item: T,
        val run: suspend (T) -> Unit,
        val waiters: List<CompletableDeferred<Unit>>,
    )

    private val gate = Mutex()
    private val pending = LinkedHashMap<Long, Slot<T>>()

    suspend fun submit(item: T, run: suspend (T) -> Unit) {
        val waiter = CompletableDeferred<Unit>()
        synchronized(pending) {
            val id = key(item)
            val prev = pending.remove(id)
            val merged = if (prev == null) item else merge(prev.item, item)
            val waiters = (prev?.waiters ?: emptyList()) + waiter
            pending[id] = Slot(merged, run, waiters)
        }
        scope.launch { drain() }
        waiter.await()
    }

    private suspend fun drain() {
        if (!gate.tryLock()) return
        try {
            while (true) {
                val slot = synchronized(pending) {
                    val entry = pending.entries.firstOrNull() ?: return@synchronized null
                    pending.remove(entry.key)
                    entry.value
                } ?: break
                try {
                    slot.run(slot.item)
                    slot.waiters.forEach { if (!it.isCompleted) it.complete(Unit) }
                } catch (e: CancellationException) {
                    slot.waiters.forEach { if (!it.isCompleted) it.cancel(e) }
                    if (!scope.isActive) throw e
                } catch (t: Throwable) {
                    slot.waiters.forEach { if (!it.isCompleted) it.completeExceptionally(t) }
                }
            }
        } finally {
            gate.unlock()
        }
        if (synchronized(pending) { pending.isNotEmpty() }) drain()
    }
}
