package com.demo.imsdc.ui

import android.content.Context
import android.util.Log

/** The recent-calls list shown on the home screen (newest first, up to 50), kept in shared preferences. */
object CallHistory {
    private const val TAG = "CallHistory"
    private const val MAX = 50

    fun save(ctx: Context, number: String, isIncoming: Boolean) {
        val sp = ctx.getSharedPreferences("ims_dc_history", Context.MODE_PRIVATE)
        try {
            val arr = org.json.JSONArray(sp.getString("history", "[]") ?: "[]")
            val newArr = org.json.JSONArray()
            newArr.put(org.json.JSONObject().apply {
                put("number", number)
                put("isIncoming", isIncoming)
                put("timestamp", System.currentTimeMillis())
            })
            for (i in 0 until arr.length()) {
                if (newArr.length() >= MAX) break
                newArr.put(arr.getJSONObject(i))
            }
            sp.edit().putString("history", newArr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lưu lịch sử: ${e.message}")
        }
    }
}
