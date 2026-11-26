package dev.cmfopen.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.cmfopen.protocol.Bytes
import dev.cmfopen.protocol.Message
import dev.cmfopen.protocol.Op

/**
 * A live console for working out unknown watch commands from adb, without rebuilding the app.
 *
 * Only the shell user (adb) can reach it: the receiver requires android.permission.DUMP, which
 * ordinary apps can't hold. A frame is written as "OPCODE PAYLOAD" in hex, for example
 * "FFFF90D5 01" or "FFFF90D5" for an empty payload.
 *
 *   adb shell am broadcast -a dev.cmfopen.companion.LAB --es send "FFFF90D6 010301"
 *   adb shell am broadcast -a dev.cmfopen.companion.LAB --es rule "FFFFA0D6 0101 => FFFF90D6 0103"
 *   adb shell am broadcast -a dev.cmfopen.companion.LAB --es rule "FFFFA0D6 * => FFFF90D6 01; FFFF90D5 03"
 *   adb shell am broadcast -a dev.cmfopen.companion.LAB --ez list true
 *   adb shell am broadcast -a dev.cmfopen.companion.LAB --ez clear true
 *
 * A rule answers every incoming frame whose opcode matches and whose payload starts with the given
 * hex ("*" matches any payload) with one or more frames, separated by ";". A later rule for the same
 * match replaces the earlier one. Rules live until the app restarts, apart from [DEFAULT_RULES].
 * Every step is written to the Log tab and logcat (tag CmfCompanion).
 */
class ProtocolLab(private val watch: WatchManager) {
    class Frame(val op: Op, val payload: ByteArray) {
        override fun toString() = "$op ${Bytes.hex(payload)}".trim()
    }

    private class Rule(val op: Op, val prefix: ByteArray?, val replies: List<Frame>) {
        fun key() = "$op ${prefix?.let(Bytes::hex) ?: "*"}"
        fun matches(m: Message) = m.op == op && (prefix == null || m.payload.startsWith(prefix))
        override fun toString() = "${key()} => ${replies.joinToString("; ")}"
    }

    private val rules = LinkedHashMap<String, Rule>()

    init {
        DEFAULT_RULES.forEach { addRule(it, log = false) }
    }

    /** Called for every frame from the watch. Returns true if a rule answered it. */
    fun onWatchMessage(m: Message): Boolean {
        val rule = synchronized(rules) { rules.values.lastOrNull { it.matches(m) } } ?: return false
        DebugLog.info("lab: $m matched ${rule.key()}")
        rule.replies.forEach(::send)
        return true
    }

    fun send(f: Frame) = watch.launchWithSession("lab send $f") { it.send(f.op, f.payload) }

    fun addRule(text: String, log: Boolean = true) {
        val (match, reply) = text.split("=>").map { it.trim() }.takeIf { it.size == 2 }
            ?: throw IllegalArgumentException("rule needs 'OP PAYLOAD => OP PAYLOAD'")
        val parts = match.split(Regex("\\s+"))
        val op = parseOp(parts[0])
        val prefix = parts.getOrNull(1)?.takeIf { it != "*" }?.let(Bytes::fromHex)
        val rule = Rule(op, prefix, reply.split(";").map { parseFrame(it) })
        synchronized(rules) { rules[rule.key()] = rule }
        if (log) DebugLog.info("lab rule: $rule")
    }

    fun handle(intent: Intent) {
        try {
            intent.getStringExtra("send")?.let { s -> s.split(";").forEach { send(parseFrame(it)) } }
            intent.getStringExtra("rule")?.let { addRule(it) }
            if (intent.getBooleanExtra("clear", false)) {
                synchronized(rules) { rules.clear() }
                DebugLog.info("lab: rules cleared")
            }
            if (intent.getBooleanExtra("list", false)) {
                val all = synchronized(rules) { rules.values.toList() }
                DebugLog.info("lab: ${all.size} rules" + all.joinToString("") { "\n  $it" })
            }
        } catch (e: Exception) {
            DebugLog.warn("lab: ${e.message}")
        }
    }

    fun register(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = handle(intent)
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, PERMISSION, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, PERMISSION, null)
        }
    }

    companion object {
        const val ACTION = "dev.cmfopen.companion.LAB"
        const val PERMISSION = "android.permission.DUMP"

        /**
         * Essential News (firmware 1.0.1.77, 2026-11-26): opening it on the watch sends FFFF:A0D5 01
         * once a second until answered. Answering FFFF:90D5 01 drew a follow-up A0D5 a5, and the
         * sequence that reached the player screen was 90D5 01, then an empty 90D5. The watch then
         * opened A2DP to the phone by itself.
         */
        val DEFAULT_RULES = listOf("FFFFA0D5 01 => FFFF90D5 01", "FFFFA0D5 a5 => FFFF90D5")

        fun parseOp(hex: String): Op {
            require(hex.length == 8) { "opcode '$hex' must be 8 hex digits, e.g. FFFF90D5" }
            return Op(hex.substring(0, 4).toInt(16), hex.substring(4).toInt(16))
        }

        fun parseFrame(text: String): Frame {
            val parts = text.trim().split(Regex("\\s+"))
            return Frame(parseOp(parts[0]), parts.getOrNull(1)?.let(Bytes::fromHex) ?: ByteArray(0))
        }

        private fun ByteArray.startsWith(p: ByteArray) = size >= p.size && p.indices.all { this[it] == p[it] }
    }
}
