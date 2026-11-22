package com.screentrans.app

import android.content.Context

/** 앱 설정 (폰 안에만 저장) */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 기본 번역: "free"(구글 폰 내장, 무료) 또는 "claude" */
    var engine: String
        get() = sp.getString("engine", "free") ?: "free"
        set(v) = sp.edit().putString("engine", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v.trim()).apply()

    /** Claude 모델: 빠르고 싼 haiku / 더 자연스러운 sonnet */
    var model: String
        get() = sp.getString("model", "claude-haiku-5-5") ?: "claude-haiku-5-5"
        set(v) = sp.edit().putString("model", v).apply()

    /** Claude에 화면 이미지도 같이 보내서 장면을 보고 번역 (더 자연스럽지만 비용 증가) */
    var sendImage: Boolean
        get() = sp.getBoolean("sendImage", true)
        set(v) = sp.edit().putBoolean("sendImage", v).apply()

    var memo: String
        get() = sp.getString("memo", "") ?: ""
        set(v) = sp.edit().putString("memo", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bx", -1)
        set(v) = sp.edit().putInt("bx", v).apply()
    var bubbleY: Int
        get() = sp.getInt("by", 400)
        set(v) = sp.edit().putInt("by", v).apply()

    val hasClaude get() = apiKey.startsWith("sk-")
}
