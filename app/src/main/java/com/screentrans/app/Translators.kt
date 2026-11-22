package com.screentrans.app

import android.graphics.Bitmap
import android.util.Base64
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 같은 문장은 다시 번역하지 않음 (스크롤 번역 때 비용·시간 절약) */
object Cache {
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 800
    }
    @Synchronized fun get(k: String) = map[k]
    @Synchronized fun put(k: String, v: String) { map[k] = v }
}

/** 무료: 구글 ML Kit 폰 내장 번역 (처음 한 번 언어 모델을 받아야 함, 언어당 약 30MB) */
class FreeTranslator {
    private val clients = HashMap<String, Translator>()
    private fun client(lang: String): Translator = clients.getOrPut(lang) {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(if (lang == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.KOREAN)
                .build()
        )
    }

    suspend fun prepare(lang: String) {
        client(lang).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
    }

    suspend fun translate(blocks: List<Block>) {
        for (b in blocks) {
            val key = "free|" + b.text
            val cached = Cache.get(key)
            if (cached != null) { b.ko = cached; continue }
            prepare(b.lang)
            val r = client(b.lang).translate(b.text).await()
            Cache.put(key, r)
            b.ko = r
        }
    }

    fun close() { clients.values.forEach { it.close() }; clients.clear() }
}

/** Claude: 장면을 보고 사람처럼 번역. API 키 필요 (쓴 만큼 요금) */
private val REFUSAL = Regex("(죄송|번역할 수 없|번역하지 않|도와드릴 수 없|제공할 수 없|I can(?:no|')t|I'm unable|I won'?t)", RegexOption.IGNORE_CASE)

class ClaudeTranslator(private val prefs: Prefs) {
    private val voices = LinkedHashMap<String, String>()   // 인물 → 말투 메모 (실행 중 유지)

    suspend fun translate(blocks: List<Block>, screen: Bitmap?) = withContext(Dispatchers.IO) {
        val todo = blocks.filter { b -> Cache.get("claude|" + b.text)?.also { b.ko = it } == null }
        if (todo.isEmpty()) return@withContext
        val list = StringBuilder()
        todo.forEachIndexed { i, b ->
            val budget = if (b.lang == "ja") (b.text.count { !it.isWhitespace() } * 1.1).toInt().coerceAtLeast(3)
                         else (b.text.length / 2.2).toInt().coerceAtLeast(3)
            list.append(i + 1).append(". [").append(if (b.vertical) "세로" else "가로").append(" · 약 ").append(budget).append("자 이내] ")
                .append(b.text.replace('\n', ' ')).append('\n')
        }
        val memo = prefs.memo.trim()
        val v = voices.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        val prompt = buildString {
            append("(용도: 사용자가 자기 휴대폰 화면의 외국어를 개인적으로 읽기 위해 글자 자리에 한국어를 겹쳐 보여주는 번역 앱입니다.)\n")
            append("당신은 일본어·영어 만화와 게임을 한국어로 옮기는 최고 수준의 번역가입니다. ")
            if (screen != null) append("첨부 이미지는 현재 화면입니다. 장면과 인물 관계를 보고 ")
            append("아래 목록의 각 항목을 자연스러운 한국어로 옮기세요.\n")
            append(" - 정식 출간물처럼 자연스럽게. 직역투 금지. 원문의 말투(반말/존댓말, 캐릭터 성격)를 살릴 것.\n")
            append(" - 'N자 이내'는 원문과 같은 크기 글씨로 같은 칸에 들어가야 하므로 뜻을 살려 그 길이 안쪽으로.\n")
            append(" - 효과음·의성어는 한국어 느낌으로. 인식이 조금 틀린 글자는 문맥으로 바로잡아 번역.\n")
            if (memo.isNotEmpty()) append("사용자 메모(반드시 반영): ").append(memo).append('\n')
            if (v.isNotEmpty()) append("등장인물 말투(일관되게):\n").append(v).append('\n')
            append("\n목록:\n").append(list)
            append("\nJSON만 출력: {\"voices\":{\"인물\":\"말투 한 줄 메모\"},\"t\":[{\"id\":번호,\"ko\":\"번역\"}]}")
        }
        val content = JSONArray()
        if (screen != null) {
            content.put(JSONObject().put("type", "image").put("source", JSONObject()
                .put("type", "base64").put("media_type", "image/jpeg").put("data", jpeg(screen, 1024))))
        }
        content.put(JSONObject().put("type", "text").put("text", prompt))
        val body = JSONObject()
            .put("model", prefs.model)
            .put("max_tokens", 4000)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000; conn.readTimeout = 90000
        conn.doOutput = true
        conn.setRequestProperty("content-type", "application/json")
        conn.setRequestProperty("x-api-key", prefs.apiKey)
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(200))
            throw RuntimeException(when (code) {
                401 -> "API 키가 올바르지 않아요"
                429 -> "요청이 너무 많아요. 잠시 후 다시 해주세요"
                529, 503 -> "Claude가 바빠요. 잠시 후 다시 해주세요"
                else -> "Claude 오류($code): $msg"
            })
        }
        val out = JSONObject(text).getJSONArray("content")
        val answer = StringBuilder()
        for (i in 0 until out.length()) { val c = out.getJSONObject(i); if (c.optString("type") == "text") answer.append(c.optString("text")) }
        val map = parse(answer.toString())
        // 번역 대신 거절·설명 문구가 온 칸은 번역 없음으로 처리 → 무료 번역이 채움 (캐시에도 넣지 않음)
        todo.forEachIndexed { i, b -> map[i + 1]?.let { if (!REFUSAL.containsMatchIn(it)) { b.ko = it; Cache.put("claude|" + b.text, it) } } }
    }

    private fun parse(s: String): Map<Int, String> {
        val m = HashMap<Int, String>()
        val a = s.indexOf('{'); val z = s.lastIndexOf('}')
        if (a >= 0 && z > a) runCatching {
            val o = JSONObject(s.substring(a, z + 1))
            o.optJSONArray("t")?.let { arr -> for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); val ko = x.optString("ko"); if (ko.isNotBlank()) m[x.optInt("id")] = ko } }
            o.optJSONObject("voices")?.let { vs -> vs.keys().forEach { k -> voices[k] = vs.optString(k) }; while (voices.size > 15) voices.remove(voices.keys.first()) }
        }
        if (m.isEmpty()) Regex("\"id\"\\s*:\\s*\"?(\\d+)\"?\\s*,\\s*\"ko\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(s).forEach {
            m[it.groupValues[1].toInt()] = it.groupValues[2].replace("\\\"", "\"").replace("\\n", "\n")
        }
        return m
    }

    private fun jpeg(b: Bitmap, maxL: Int): String {
        val k = minOf(1f, maxL.toFloat() / maxOf(b.width, b.height))
        val s = if (k < 1f) Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true) else b
        val bos = ByteArrayOutputStream()
        s.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        if (s !== b) s.recycle()
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
