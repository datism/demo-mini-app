package com.demo.imsdc.ui

import android.content.Context
import org.json.JSONObject

/**
 * The app's own phonebook: a name for a SIP number (1002, 1003, …), kept in SharedPreferences.
 * Only what is shown uses it; SIP always uses the number.
 */
object Phonebook {
    private const val PREFS = "phonebook"
    private const val KEY = "entries"

    /** "sip:+841002@host" -> "1002": the same person whichever way the number is written. */
    fun normalize(number: String): String =
        number.trim().removePrefix("sip:").substringBefore('@').substringBefore(';').removePrefix("+84").trim()

    private fun load(ctx: Context): JSONObject = try {
        JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun save(ctx: Context, obj: JSONObject) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, obj.toString()).apply()
    }

    /** (number, name), sorted by name. */
    fun all(ctx: Context): List<Pair<String, String>> {
        val obj = load(ctx)
        return obj.keys().asSequence().map { it to obj.optString(it) }.sortedBy { it.second.lowercase() }.toList()
    }

    fun nameOf(ctx: Context, number: String): String? =
        load(ctx).optString(normalize(number)).takeIf { it.isNotBlank() }

    /** The saved name, or the number when none is saved. */
    fun label(ctx: Context, number: String): String = nameOf(ctx, number) ?: number

    fun put(ctx: Context, number: String, name: String) {
        val n = normalize(number)
        if (n.isEmpty() || name.isBlank()) return
        save(ctx, load(ctx).put(n, name.trim()))
    }

    fun remove(ctx: Context, number: String) {
        val obj = load(ctx); obj.remove(normalize(number)); save(ctx, obj)
    }
}
