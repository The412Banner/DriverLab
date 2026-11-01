package com.driverlab.core

import android.content.Context
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.util.TreeMap
import java.util.zip.GZIPInputStream

/**
 * Reads a freedreno .rd capture (gzip or plain) and collects every register
 * write the command streams make: pkt4 writes and register bunches, following
 * indirect buffers, draw-state groups and preambles (same walk as cffdump,
 * buffers reset per submit).
 */
object RdCapture {
    data class Summary(
        val chipId: Long,
        val submits: Int,
        val missingIbs: Int,
        /** register offset -> distinct values in first-write order with counts */
        val regs: Map<Int, List<Pair<Long, Int>>>,
        val error: String?,
    ) {
        val gen: String
            get() = when ((chipId ushr 24).toInt() and 0xff) {
                0x44, 0x08 -> "a8xx"
                0x43, 0x07 -> "a7xx"
                else -> "a6xx"
            }
    }

    private const val RD_GPUADDR = 3
    private const val RD_CMDSTREAM_ADDR = 6
    private const val RD_BUFFER_CONTENTS = 12
    private const val RD_CHIP_ID = 14
    private const val CP_IB = 0x3f
    private const val CP_IB_PFD = 0x37
    private const val CP_IB_CHAIN = 0x57
    private const val CP_SET_DRAW_STATE = 0x43
    private const val CP_SET_AMBLE = 0x55
    private const val CP_CONTEXT_REG_BUNCH = 0x5c
    private const val CP_NON_CONTEXT_REG_BUNCH = 0x5d

    private val cache = object : LinkedHashMap<String, Summary>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Summary>?) = size > 6
    }

    fun summary(f: File): Summary {
        val key = f.absolutePath + ":" + f.length() + ":" + f.lastModified()
        synchronized(cache) { cache[key]?.let { return it } }
        val s = parse(f)
        synchronized(cache) { cache[key] = s }
        return s
    }

    private fun open(f: File): InputStream {
        val raw = f.inputStream().buffered(1 shl 16)
        raw.mark(2)
        val b0 = raw.read()
        val b1 = raw.read()
        raw.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 1 shl 16) else raw
    }

    private class Walker {
        val buffers = TreeMap<Long, ByteArray>()
        val values = HashMap<Int, LinkedHashMap<Long, Int>>()
        var missing = 0

        fun dw(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xff) or ((buf[off + 1].toInt() and 0xff) shl 8) or
                ((buf[off + 2].toInt() and 0xff) shl 16) or ((buf[off + 3].toInt() and 0xff) shl 24)

        fun record(reg: Int, v: Int) {
            val m = values.getOrPut(reg) { LinkedHashMap() }
            val key = v.toLong() and 0xffffffffL
            m[key] = (m[key] ?: 0) + 1
        }

        fun walk(addr: Long, ndw: Int, depth: Int, seen: HashSet<Long>) {
            if (depth > 6 || ndw <= 0 || ndw > (1 shl 22)) return
            if (!seen.add(addr xor (ndw.toLong() shl 48))) return
            val e = buffers.floorEntry(addr)
            if (e == null) { missing++; return }
            val off = (addr - e.key)
            val buf = e.value
            if (off < 0 || off + ndw.toLong() * 4 > buf.size) { missing++; return }
            var i = 0
            val base = off.toInt()
            fun at(k: Int) = dw(buf, base + k * 4)
            while (i < ndw) {
                val h = at(i)
                when (h ushr 28) {
                    4 -> {
                        val cnt = h and 0x7f
                        val reg = (h ushr 8) and 0x7ffff
                        for (k in 0 until cnt) if (i + 1 + k < ndw) record(reg + k, at(i + 1 + k))
                        i += 1 + cnt
                    }
                    7 -> {
                        val cnt = h and 0x3fff
                        val op = (h ushr 16) and 0x7f
                        val p0 = i + 1
                        fun p(k: Int) = if (p0 + k < ndw) at(p0 + k) else 0
                        fun addr64(lo: Int, hi: Int) = (lo.toLong() and 0xffffffffL) or ((hi.toLong() and 0xffffffffL) shl 32)
                        when (op) {
                            CP_IB, CP_IB_PFD, CP_IB_CHAIN -> if (cnt >= 3) walk(addr64(p(0), p(1)), p(2) and 0xfffff, depth + 1, seen)
                            CP_SET_DRAW_STATE -> {
                                var g = 0
                                while (g + 2 < cnt) {
                                    val w = p(g)
                                    val c = w and 0xffff
                                    val disable = (w ushr 17) and 1
                                    if (c > 0 && disable == 0) walk(addr64(p(g + 1), p(g + 2)), c, depth + 1, seen)
                                    g += 3
                                }
                            }
                            CP_SET_AMBLE -> if (cnt >= 3) walk(addr64(p(0), p(1)), p(2) and 0xfffff, depth + 1, seen)
                            CP_CONTEXT_REG_BUNCH, CP_NON_CONTEXT_REG_BUNCH -> {
                                var g = cnt % 2
                                while (g + 1 < cnt) {
                                    val r = p(g)
                                    if (r in 0..0x7ffff) record(r, p(g + 1))
                                    g += 2
                                }
                            }
                        }
                        i += 1 + cnt
                    }
                    else -> i++
                }
            }
        }
    }

    fun parse(f: File): Summary {
        var chip = 0L
        var submits = 0
        val w = Walker()
        var lastAddr = 0L
        var needReset = false
        var error: String? = null
        try {
            DataInputStream(open(f)).use { input ->
                val hdr = ByteArray(8)
                while (true) {
                    try { input.readFully(hdr) } catch (e: EOFException) { break }
                    val type = le(hdr, 0)
                    val size = le(hdr, 4)
                    if (type == -1 && size == -1) continue
                    if (size < 0 || size > (512 shl 20)) { error = "corrupt section"; break }
                    val data = ByteArray(size)
                    try { input.readFully(data) } catch (e: EOFException) { error = "file ends early"; break }
                    when (type) {
                        RD_GPUADDR -> {
                            if (needReset) { w.buffers.clear(); needReset = false }
                            if (size >= 8) {
                                val lo = le(data, 0).toLong() and 0xffffffffL
                                val hi = if (size >= 12) le(data, 8).toLong() and 0xffffffffL else 0L
                                lastAddr = lo or (hi shl 32)
                            }
                        }
                        RD_BUFFER_CONTENTS -> w.buffers[lastAddr] = data
                        RD_CMDSTREAM_ADDR -> {
                            if (size >= 8) {
                                val lo = le(data, 0).toLong() and 0xffffffffL
                                val ndw = le(data, 4)
                                val hi = if (size >= 12) le(data, 8).toLong() and 0xffffffffL else 0L
                                w.walk(lo or (hi shl 32), ndw, 0, HashSet())
                            }
                            submits++
                            needReset = true
                        }
                        RD_CHIP_ID -> if (size >= 8 && chip == 0L) {
                            chip = (le(data, 0).toLong() and 0xffffffffL) or ((le(data, 4).toLong() and 0xffffffffL) shl 32)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
        val regs = w.values.mapValues { (_, m) -> m.entries.map { it.key to it.value } }
        return Summary(chip, submits, w.missing, regs, error)
    }

    private fun le(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    data class DiffRow(val offset: Int, val name: String, val a: List<Long>, val b: List<Long>) {
        val same: Boolean get() = a.toSet() == b.toSet()
    }

    fun diff(a: Summary, b: Summary, names: Map<Int, String>): List<DiffRow> {
        val keys = (a.regs.keys + b.regs.keys).toSortedSet()
        return keys.map { k ->
            DiffRow(k, names[k] ?: "REG_" + k.toString(16), a.regs[k]?.map { it.first } ?: emptyList(), b.regs[k]?.map { it.first } ?: emptyList())
        }
    }
}

/** Register names generated from Mesa's a6xx.xml (assets/regs/<gen>.tsv). */
object RegNames {
    private val tables = HashMap<String, Map<Int, String>>()

    fun get(context: Context, gen: String): Map<Int, String> = synchronized(tables) {
        tables.getOrPut(gen) {
            runCatching {
                context.assets.open("regs/$gen.tsv").bufferedReader().readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .associate { l -> l.substringBefore('\t').toInt(16) to l.substringAfter('\t') }
            }.getOrDefault(emptyMap())
        }
    }
}
