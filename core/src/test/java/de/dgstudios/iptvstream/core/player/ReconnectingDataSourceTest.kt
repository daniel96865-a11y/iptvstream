package de.dgstudios.iptvstream.core.player

import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import android.net.Uri
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconnectingDataSourceTest {
    @Test fun providerEndReopensWhenEnabled() {
        val factory = ScriptedFactory(
            listOf(listOf(chunk("abcd")), listOf(chunk("efgh"))),
        )
        val kicks = mutableListOf<Boolean>()
        val src = ReconnectingDataSource(
            factory,
            enabled = { factory.opens < 2 },
            onReconnect = { _, _, kicked -> kicks += kicked },
        )
        src.open(DataSpec(Uri.parse("http://example.test/live.ts")))
        assertEquals("abcdefgh", readAll(src))
        assertEquals(2, factory.opens)
        assertEquals(listOf(false), kicks)
        src.close()
    }

    @Test fun stallKickReopensInSameSourceWhenEnabled() {
        val factory = ScriptedFactory(
            listOf(
                listOf(ScriptedSource.Step.Bytes("aa".toByteArray()), ScriptedSource.Step.Error),
                listOf(ScriptedSource.Step.Bytes("bb".toByteArray())),
            ),
        )
        val kicks = mutableListOf<Boolean>()
        val src = ReconnectingDataSource(
            factory,
            enabled = { factory.opens < 2 },
            onReconnect = { _, _, kicked -> kicks += kicked },
            stallEnabled = { true },
            takeStallKick = { true },
        )
        src.open(DataSpec(Uri.parse("http://example.test/live.ts")))
        assertEquals("aabb", readAll(src))
        assertEquals(2, factory.opens)
        assertEquals(listOf(true), kicks)
        src.close()
    }

    @Test fun ptsMismatchEndsStreamWithoutAnotherReopen() {
        val factory = ScriptedFactory(
            listOf(
                listOf(chunk("aa")),
                listOf(ScriptedSource.Step.Mismatch),
                listOf(chunk("should-not-be-read")),
            ),
        )
        val src = ReconnectingDataSource(
            factory,
            enabled = { factory.opens < 3 },
            onReconnect = { _, _, _ -> },
        )
        src.open(DataSpec(Uri.parse("http://example.test/live.ts")))
        assertEquals("aa", readAll(src))
        assertEquals(2, factory.opens)
        src.close()
    }

    private fun chunk(s: String) = ScriptedSource.Step.Bytes(s.toByteArray())

    private fun readAll(src: DataSource): String {
        val buf = ByteArray(16)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = src.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toString(Charsets.US_ASCII)
    }

    private class ScriptedFactory(private val connections: List<List<ScriptedSource.Step>>) : DataSource.Factory {
        var opens = 0
        override fun createDataSource(): DataSource {
            val steps = connections.getOrElse(opens) { emptyList() }
            opens++
            return ScriptedSource(ArrayList(steps))
        }
    }

    private class ScriptedSource(private val queue: MutableList<Step>) : DataSource {
        private var remaining: ByteArray = ByteArray(0)
        private var off = 0
        private var opened = false

        sealed class Step {
            class Bytes(val data: ByteArray) : Step()
            object Error : Step()
            object Mismatch : Step()
        }

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            opened = true
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (off < remaining.size) {
                val n = minOf(length, remaining.size - off)
                System.arraycopy(remaining, off, buffer, offset, n)
                off += n
                return n
            }
            if (queue.isEmpty()) return C.RESULT_END_OF_INPUT
            when (val step = queue.removeAt(0)) {
                is Step.Bytes -> {
                    remaining = step.data
                    off = 0
                    return read(buffer, offset, length)
                }
                Step.Error -> throw IOException("stall")
                Step.Mismatch -> throw TsJoinMismatchException()
            }
        }

        override fun getUri(): Uri? = Uri.parse("http://example.test/live.ts")
        override fun close() { opened = false }
    }
}
