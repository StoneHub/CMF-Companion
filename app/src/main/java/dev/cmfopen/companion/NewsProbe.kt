package dev.cmfopen.companion

import dev.cmfopen.protocol.Bytes
import dev.cmfopen.protocol.Op
import dev.cmfopen.protocol.Session

/**
 * Finds the phone's answer to the watch's Essential News request by trying candidates.
 *
 * Opening Essential News on the watch sends NEWS_SYNC_REQUEST (FFFF:A0D5, payload 01) about once a
 * second, for up to about 45 seconds, until the phone answers. Each request here gets the next
 * candidate reply. When a candidate is accepted the retries stop, and the log line before that
 * names the winner. Whatever the watch sends next (play, pause, volume, an A2DP check) then shows up
 * as "unhandled" in the log.
 *
 * A request more than [RESTART_GAP_MS] after the previous one starts again from the first candidate,
 * so every time the news screen is opened the whole list is tried. Duplicates the watch sends within
 * [DUPLICATE_MS] of each other count once.
 */
class NewsProbe {
    class Candidate(val op: Op, val payload: ByteArray) {
        override fun toString() = "$op ${Bytes.hex(payload).ifEmpty { "(empty)" }}"
    }

    private var index = 0
    private var lastRequestAt = 0L

    /** Candidate to send for a request that arrived at [now], or null for a duplicate. */
    @Synchronized
    fun next(now: Long): Pair<Int, Candidate>? {
        val gap = now - lastRequestAt
        if (gap < DUPLICATE_MS) return null
        lastRequestAt = now
        if (gap > RESTART_GAP_MS) index = 0
        if (index >= CANDIDATES.size) return null
        val i = index++
        return i to CANDIDATES[i]
    }

    suspend fun answer(s: Session, now: Long = System.currentTimeMillis()) {
        val (i, c) = next(now) ?: return
        DebugLog.info("news probe #$i/${CANDIDATES.size}: $c")
        s.send(c.op, c.payload)
    }

    companion object {
        const val DUPLICATE_MS = 300L
        const val RESTART_GAP_MS = 15_000L

        private val REPLY = Op(0xFFFF, 0x90D5)

        private fun c(op: Op, vararg bytes: Int) = Candidate(op, ByteArray(bytes.size) { bytes[it].toByte() })

        /**
         * Ordered by how this protocol usually works: a watch request 0xA0xx is answered with 0x90xx
         * (A064 -> 9064, A065 -> 9065). Nothing X's own player states are IDLE 0, LOADED 1,
         * PREPARING 2, PLAYING 3, PAUSE 4, ENDING 5, PREPARING_PAUSE 6, END 7, and its play info adds a
         * position and a duration. The last few try other reply opcodes.
         */
        val CANDIDATES: List<Candidate> = listOf(
            c(REPLY, 0x01),
            c(REPLY),
            c(REPLY, 0x00),
            c(REPLY, 0x01, 0x01),
            c(REPLY, 0x01, 0x00),
            c(REPLY, 0x00, 0x01),
            c(REPLY, 0x03),
            c(REPLY, 0x02),
            c(REPLY, 0x01, 0x03),
            c(REPLY, 0x03, 0, 0, 0, 0, 0, 0, 0, 0),
            c(REPLY, 0x01, 0, 0, 0, 0, 0, 0, 0, 0),
            c(REPLY, 0x01, 0x00, 0x00),
            c(REPLY, 0x00, 0x00, 0x00, 0x00),
            c(Op(0xFFFF, 0x00D5), 0x01),
            c(Op(0xFFFF, 0x00D5)),
            c(Op(0xFFFF, 0x80D5), 0x01),
            c(Op(0xFFFF, 0x90D6), 0x01),
        )
    }
}
