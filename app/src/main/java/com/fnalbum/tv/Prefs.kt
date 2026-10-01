package com.fnalbum.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 账号列表 + 界面设置的本地存储 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("fnalbum_tv", Context.MODE_PRIVATE)

    // ------------------------------------------------------------- 账号

    fun accounts(): MutableList<Account> {
        val raw = sp.getString(KEY_ACCOUNTS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Account(
                    host = o.optString("host"),
                    port = o.optInt("port", 5666),
                    user = o.optString("user"),
                    password = o.optString("password"),
                    ssl = o.optBoolean("ssl", false),
                    token = o.optString("token"),
                    alias = o.optString("alias")
                )
            }
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun saveAccount(account: Account) {
        val list = accounts()
        val idx = list.indexOfFirst { it.key == account.key }
        if (idx >= 0) list[idx] = account else list.add(account)
        persist(list)
    }

    fun deleteAccount(key: String) {
        val list = accounts().filter { it.key != key }.toMutableList()
        persist(list)
        if (currentKey == key) currentKey = list.firstOrNull()?.key ?: ""
    }

    private fun persist(list: List<Account>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(
                JSONObject().apply {
                    put("host", a.host)
                    put("port", a.port)
                    put("user", a.user)
                    put("password", a.password)
                    put("ssl", a.ssl)
                    put("token", a.token)
                    put("alias", a.alias)
                }
            )
        }
        sp.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    var currentKey: String
        get() = sp.getString(KEY_CURRENT, "").orEmpty()
        set(value) = sp.edit().putString(KEY_CURRENT, value).apply()

    fun currentAccount(): Account? {
        val key = currentKey
        if (key.isEmpty()) return null
        return accounts().firstOrNull { it.key == key }
    }

    // ------------------------------------------------------------- 设置

    /** 6 = 六宫格，9 = 九宫格，12 = 十二宫格 */
    var gridStyle: Int
        get() = sp.getInt(KEY_STYLE, 6).let { if (it == 6 || it == 9 || it == 12) it else 6 }
        set(value) = sp.edit().putInt(KEY_STYLE, value).apply()

    /** 0 表示"全部照片" */
    var albumId: Int
        get() = sp.getInt(KEY_ALBUM_ID, 0)
        set(value) = sp.edit().putInt(KEY_ALBUM_ID, value).apply()

    var albumName: String
        get() = sp.getString(KEY_ALBUM_NAME, "全部照片").orEmpty().ifEmpty { "全部照片" }
        set(value) = sp.edit().putString(KEY_ALBUM_NAME, value).apply()

    private companion object {
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_CURRENT = "current_key"
        const val KEY_STYLE = "grid_style"
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_ALBUM_NAME = "album_name"
    }
}
