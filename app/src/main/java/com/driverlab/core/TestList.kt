package com.driverlab.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Writer

/**
 * Test lists ("caselist.txt"): one test name per line, the format the Vulkan CTS reads with
 * --deqp-caselist-file. Also accepted: whole groups as "group.*", blank lines, "#" comments,
 * and lines with a result after the name ("name,Fail" or "name Fail", as in Mesa CI lists).
 */
object TestList {
    /** Larger lists would need too much memory; whole groups ("group.*") cover big sets. */
    const val MAX_NAMES = 500_000

    fun parse(lines: Sequence<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (raw in lines) {
            val l = raw.trim().removePrefix("﻿")
            if (l.isEmpty() || l.startsWith("#")) continue
            val name = l.split(',', ' ', '\t').first().trim()
            if (name.isNotEmpty()) seen += name
            if (seen.size > MAX_NAMES) break
        }
        return seen.toList()
    }

    fun read(cr: ContentResolver, uri: Uri): List<String> =
        cr.openInputStream(uri)!!.bufferedReader().useLines { parse(it) }

    fun displayName(cr: ContentResolver, uri: Uri): String =
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "test list"

    /** Writes names one per line; returns how many. */
    suspend fun write(out: Writer, names: suspend (suspend (String) -> Unit) -> Unit): Long {
        var n = 0L
        names { out.write(it); out.write("\n"); n++ }
        out.flush()
        return n
    }
}
