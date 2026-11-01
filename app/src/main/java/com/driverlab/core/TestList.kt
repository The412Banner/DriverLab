package com.driverlab.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.Writer

/**
 * Test lists ("caselist.txt"): one test name per line, the format the Vulkan CTS reads with
 * --deqp-caselist-file. Also accepted: whole groups as "group.*", blank lines, "#" comments,
 * and lines with a result after the name ("name,Fail" or "name Fail", as in Mesa CI lists).
 * There is no size limit: lists are read as a stream (see [SelectionStore.loadList]).
 */
object TestList {
    /** The test name on one list line, or null for blank and comment lines. */
    fun nameOf(raw: String): String? {
        val l = raw.trim().removePrefix("﻿")
        if (l.isEmpty() || l.startsWith("#")) return null
        var end = l.length
        for (i in l.indices) {
            val c = l[i]
            if (c == ',' || c == ' ' || c == '\t') { end = i; break }
        }
        return l.substring(0, end).takeIf { it.isNotEmpty() }
    }

    /** Feeds every line of a document to [sink]. */
    suspend fun readLines(cr: ContentResolver, uri: Uri, sink: suspend (String) -> Unit) {
        cr.openInputStream(uri)!!.bufferedReader().useLines { seq -> for (l in seq) sink(l) }
    }

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
