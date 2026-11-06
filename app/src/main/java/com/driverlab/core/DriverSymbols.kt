package com.driverlab.core

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * Facts read straight from a 64-bit little-endian ELF file (every arm64 driver library): its GNU
 * build ID, and the code pointers stored in its data.
 *
 * Code pointers are the start addresses of functions that are only reached through tables (Vulkan
 * entry points, format packers, compiler folding tables). Nothing calls them directly, so the
 * engine's own analysis misses them: 2,471 of 2,897 missed functions on the Banners-AIO r2 driver.
 * They come from the dynamic relocations: RELATIVE (the value is the addend), ABS64 / GLOB_DAT /
 * JUMP_SLOT against a defined symbol, and RELR-packed relocations (the value is stored at the
 * relocated place). Only values that land 4-byte aligned in an executable section are kept.
 */
object ElfFacts {
    private const val SHT_SYMTAB_DYN = 11
    private const val SHT_NOTE = 7
    private const val SHT_NOBITS = 8
    private const val SHT_RELA = 4
    private const val SHT_RELR = 19
    private const val SHT_ANDROID_RELR = 0x6fffff00
    private const val R_ABS64 = 257
    private const val R_GLOB_DAT = 1025
    private const val R_JUMP_SLOT = 1026
    private const val R_RELATIVE = 1027

    private class Sec(val type: Int, val flags: Long, val addr: Long, val off: Long, val size: Long, val link: Int, val entsize: Long)

    private class Elf(val buf: ByteBuffer, val secs: List<Sec>) {
        fun u32(o: Long) = buf.getInt(o.toInt()).toLong() and 0xffffffffL
        fun u64(o: Long) = buf.getLong(o.toInt())
        fun read64(va: Long): Long? {
            val s = secs.firstOrNull { it.type != SHT_NOBITS && it.addr != 0L && va >= it.addr && va + 8 <= it.addr + it.size } ?: return null
            return u64(s.off + va - s.addr)
        }
    }

    private fun open(file: File): Elf? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.getInt(0) != 0x464c457f || buf.get(4).toInt() != 2 || buf.get(5).toInt() != 1) return null
            val shoff = buf.getLong(0x28)
            val shentsize = buf.getShort(0x3A).toInt() and 0xffff
            val shnum = buf.getShort(0x3C).toInt() and 0xffff
            val secs = (0 until shnum).map { i ->
                val o = (shoff + i.toLong() * shentsize).toInt()
                Sec(buf.getInt(o + 4), buf.getLong(o + 8), buf.getLong(o + 16), buf.getLong(o + 24), buf.getLong(o + 32),
                    buf.getInt(o + 40), buf.getLong(o + 56))
            }
            Elf(buf, secs)
        }
    }.getOrNull()

    /** GNU build ID as lowercase hex, or null when the file has none. */
    fun buildId(file: File): String? {
        val e = open(file) ?: return null
        for (s in e.secs.filter { it.type == SHT_NOTE }) {
            var p = s.off
            while (p + 12 <= s.off + s.size) {
                val nsz = e.u32(p); val dsz = e.u32(p + 4); val type = e.u32(p + 8)
                val d = p + 12 + (nsz + 3) / 4 * 4
                if (type == 3L && nsz == 4L && e.u32(p + 12) == 0x00554e47L) {          // "GNU\0"
                    return (0 until dsz.toInt()).joinToString("") { "%02x".format(e.buf.get((d + it).toInt())) }
                }
                p = d + (dsz + 3) / 4 * 4
            }
        }
        return null
    }

    /** Start addresses of functions reached through code pointers in the file's data (see the class notes). */
    fun codePointers(file: File): Set<Long> {
        val e = open(file) ?: return emptySet()
        val exe = e.secs.filter { it.flags and 4L != 0L && it.size > 0 }.map { it.addr until it.addr + it.size }
        fun inCode(v: Long) = v and 3L == 0L && exe.any { v in it }
        fun symValue(symtab: Int, idx: Long): Long? {
            val s = e.secs.getOrNull(symtab) ?: return null
            val o = s.off + idx * 24
            val shndx = e.buf.getShort((o + 6).toInt()).toInt() and 0xffff
            return if (shndx == 0) null else e.u64(o + 8)
        }
        val out = HashSet<Long>()
        for (s in e.secs) {
            if (s.type == SHT_RELA && s.entsize == 24L) {
                for (i in 0 until s.size / 24) {
                    val o = s.off + i * 24
                    val info = e.u64(o + 8)
                    val addend = e.u64(o + 16)
                    val type = (info and 0xffffffffL).toInt()
                    val sym = info ushr 32
                    val v = when {
                        type == R_RELATIVE -> addend
                        (type == R_ABS64 || type == R_GLOB_DAT || type == R_JUMP_SLOT) && sym != 0L -> symValue(s.link, sym)?.plus(addend)
                        else -> null
                    }
                    if (v != null && inCode(v)) out.add(v)
                }
            } else if (s.type == SHT_RELR || s.type == SHT_ANDROID_RELR) {
                var base = 0L
                for (i in 0 until s.size / 8) {
                    val entry = e.u64(s.off + i * 8)
                    if (entry and 1L == 0L) {
                        e.read64(entry)?.let { if (inCode(it)) out.add(it) }
                        base = entry + 8
                    } else {
                        for (k in 1 until 64) if ((entry ushr k) and 1L != 0L) e.read64(base + (k - 1) * 8L)?.let { if (inCode(it)) out.add(it) }
                        base += 63 * 8
                    }
                }
            }
        }
        return out
    }
}

/** One function of a names file: [file] is relative to the Mesa tree ("build:" = made during the build). */
data class NamedFn(val addr: Long, val size: Long, val name: String, val symbol: String, val file: String, val line: Int)

/**
 * A DriverLab names file (symbols-<build ID>.tsv.gz, published with each Banners-Turnip release): the
 * real function names and Mesa source lines of one stripped driver file, matched by its build ID.
 */
class NamesFile(val header: Map<String, String>, val fns: List<NamedFn>) {
    val buildId: String get() = header["build-id"].orEmpty()
    val mesa: String get() = header["mesa"].orEmpty()
    val release: String get() = header["release"].orEmpty()
    val driver: String get() = header["driver"].orEmpty()
    private val byAddr = fns.associateBy { it.addr }
    operator fun get(addr: Long): NamedFn? = byAddr[addr]

    /** The name each function gets in the engine and the lists: [engineName], made unique with its address. */
    val engineNames: Map<Long, String> by lazy {
        val used = HashSet<String>()
        fns.associate { fn ->
            var n = engineName(fn)
            if (!used.add(n)) n = "${n}_%x".format(fn.addr).also { used.add(it) }
            fn.addr to n
        }
    }

    /** Mesa source link for [fn], or null for generated files and unknown commits. */
    fun sourceUrl(fn: NamedFn): String? {
        if (fn.file.isEmpty() || fn.file.startsWith("build:") || mesa.length < 7) return null
        return "https://gitlab.freedesktop.org/mesa/mesa/-/blob/$mesa/${fn.file}#L${fn.line}"
    }

    companion object {
        const val PREFIX = "symbols-"
        const val SUFFIX = ".tsv.gz"

        fun read(file: File): NamesFile = file.inputStream().use { read(it) }

        fun read(input: InputStream): NamesFile {
            val header = LinkedHashMap<String, String>()
            val fns = ArrayList<NamedFn>()
            GZIPInputStream(input.buffered(1 shl 16)).bufferedReader().useLines { lines ->
                for (l in lines) {
                    if (l.startsWith("# ")) {
                        val p = l.substring(2).split('\t', limit = 2)
                        if (p.size == 2) header[p[0]] = p[1]
                        continue
                    }
                    val p = l.split('\t')
                    if (p.size < 6) continue
                    val addr = p[0].toLongOrNull(16) ?: continue
                    fns.add(NamedFn(addr, p[1].toLongOrNull() ?: 0L, p[2], p[3], p[4], p[5].toIntOrNull() ?: 0))
                }
            }
            require(header.containsKey("build-id") && fns.isNotEmpty()) { "Not a DriverLab names file." }
            return NamesFile(header, fns)
        }

        /**
         * A name rizin accepts and that stays readable in decompiled code: the demangled name without its
         * parameter list and return type, "::" as ".", other characters as "_" (tu_CmdDrawIndexed<(chip)8>
         * becomes tu_CmdDrawIndexed__chip_8). Made unique per file by [engineNames].
         */
        fun engineName(fn: NamedFn): String {
            var n = fn.name
            var depth = 0
            var cut = -1
            for (i in n.indices.reversed()) {                      // last top-level "(...)" = the parameters
                when (n[i]) {
                    ')' -> depth++
                    '(' -> { depth--; if (depth == 0) { cut = i; break } }
                }
            }
            if (cut > 0 && n.endsWith(")")) n = n.substring(0, cut)
            depth = 0
            var space = -1
            for (i in n.indices) {                                // return type before a template name
                when (n[i]) { '<', '(' -> depth++; '>', ')' -> depth--; ' ' -> if (depth == 0) space = i }
            }
            if (space > 0) n = n.substring(space + 1)
            val oddEnd = n.isNotEmpty() && !n.last().isLetterOrDigit() && n.last() != '_'    // "...>" of a template
            n = n.replace("::", ".").replace(Regex("[^A-Za-z0-9_.]"), "_").replace(Regex("_{3,}"), "__")
            if (oddEnd) n = n.trimEnd('_')
            return n.ifEmpty { fn.symbol.replace(Regex("[^A-Za-z0-9_.]"), "_") }.take(120)
        }
    }
}
