package org.audoiboo.tracker

import android.content.Context
import org.json.JSONObject

object BackupStore {
    fun exportJson(context: Context): String {
        val root = JSONObject()
        root.put("format", 1)
        root.put("tracker", context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", "[]"))
        root.put("settings", prefsToJson(context, "app_settings"))
        root.put("downloads", context.getSharedPreferences("managed_downloads", Context.MODE_PRIVATE).getString("items", "[]"))
        return root.toString(2)
    }

    fun importJson(context: Context, raw: String) {
        val root = JSONObject(raw)
        context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit()
            .putString("library", root.optString("tracker", "[]")).apply()
        jsonToPrefs(context, "app_settings", root.optJSONObject("settings") ?: JSONObject())
        context.getSharedPreferences("managed_downloads", Context.MODE_PRIVATE).edit()
            .putString("items", root.optString("downloads", "[]")).apply()
    }

    private fun prefsToJson(context: Context, name: String): JSONObject {
        val out = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            when (v) {
                is Boolean, is Int, is Long, is Float, is String -> out.put(k, v)
            }
        }
        return out
    }

    private fun jsonToPrefs(context: Context, name: String, obj: JSONObject) {
        val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = obj.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
            }
        }
        e.apply()
    }
}
