package com.screentrans.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max

/** 화면에서 찾은 글자 덩어리 하나 */
data class Block(
    val rect: Rect,
    val text: String,
    val vertical: Boolean,
    val lang: String,        // "ja" 또는 "en"
    val lineSize: Float,     // 원문 한 줄(세로면 한 열)의 글자 크기 추정(px)
    val lines: Int,
    var ko: String? = null,
    var bg: Int = Color.WHITE,
    var flat: Boolean = true,
)

object Ocr {
    // 일본어 인식기는 영어(라틴 문자)도 함께 읽음. 폰 안에서 돌아가서 무료·빠름·위치 정확
    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }

    private val JA = Regex("[\\u3040-\\u30ff\\u4e00-\\u9fff]")
    private val LETTER = Regex("[A-Za-z\\u3040-\\u30ff\\u4e00-\\u9fff]")
    private val HANGUL = Regex("[\\uac00-\\ud7a3]")

    suspend fun recognize(bmp: Bitmap, exclude: Rect?): List<Block> {
        val res = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
        val out = ArrayList<Block>()
        for (tb in res.textBlocks) {
            val box = tb.boundingBox ?: continue
            if (exclude != null && Rect.intersects(box, exclude) && box.width() < exclude.width() * 3) continue
            val lines = tb.lines.filter { it.boundingBox != null && it.text.isNotBlank() }
            if (lines.isEmpty()) continue
            // 세로쓰기 판단: 줄 상자가 세로로 길쭉한 경우가 많으면 세로
            val vCount = lines.count { val b = it.boundingBox!!; b.height() > b.width() * 1.5f && it.text.length >= 2 }
            val vertical = vCount * 2 > lines.size
            // 읽는 순서: 세로는 오른쪽 열부터, 가로는 위에서부터
            val ordered = if (vertical) lines.sortedByDescending { it.boundingBox!!.centerX() }
                          else lines.sortedBy { it.boundingBox!!.top }
            val text = joinLines(ordered.map { it.text })
            val letters = LETTER.findAll(text).count()
            if (letters == 0) continue
            if (HANGUL.findAll(text).count() > letters) continue   // 이미 한국어
            val sizes = ordered.map { val b = it.boundingBox!!; if (vertical) b.width().toFloat() else b.height().toFloat() }.sorted()
            val lineSize = sizes[sizes.size / 2]
            if (lineSize < 6f) continue
            out.add(Block(Rect(box), text, vertical, if (JA.containsMatchIn(text)) "ja" else "en", lineSize, ordered.size))
        }
        mergeNeighbors(out)
        for (b in out) sampleBackground(bmp, b)
        return out
    }

    private fun joinLines(ls: List<String>): String {
        val sb = StringBuilder()
        for (l in ls) {
            val t = l.trim()
            if (sb.isNotEmpty() && sb.last().isLetterOrDigit() && sb.last().code < 0x3000 && t.firstOrNull()?.let { it.code < 0x3000 } == true) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    /** ML Kit이 한 문단을 여러 덩어리로 나눈 경우: 같은 방향, 거의 붙어 있고 정렬된 것끼리 합침 */
    private fun mergeNeighbors(bs: MutableList<Block>) {
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in bs.indices) for (j in i + 1 until bs.size) {
                val a = bs[i]; val b = bs[j]
                if (a.vertical != b.vertical || a.lang != b.lang) continue
                val size = max(a.lineSize, b.lineSize)
                if (abs(a.lineSize - b.lineSize) > size * 0.25f) continue
                val near = if (!a.vertical) {
                    val gap = max(a.rect.top, b.rect.top) - minOf(a.rect.bottom, b.rect.bottom)
                    val ox = minOf(a.rect.right, b.rect.right) - max(a.rect.left, b.rect.left)
                    gap in -2..(size * 0.7f).toInt() && ox > minOf(a.rect.width(), b.rect.width()) * 0.5f && abs(a.rect.left - b.rect.left) < size * 1.5f
                } else {
                    val gap = max(a.rect.left, b.rect.left) - minOf(a.rect.right, b.rect.right)
                    val oy = minOf(a.rect.bottom, b.rect.bottom) - max(a.rect.top, b.rect.top)
                    gap in -2..(size * 0.6f).toInt() && oy > minOf(a.rect.height(), b.rect.height()) * 0.5f && abs(a.rect.top - b.rect.top) < size * 1.5f
                }
                if (!near) continue
                val first = if (a.vertical) (if (a.rect.right >= b.rect.right) a else b) else (if (a.rect.top <= b.rect.top) a else b)
                val second = if (first === a) b else a
                val r = Rect(a.rect); r.union(b.rect)
                bs[i] = Block(r, joinLines(listOf(first.text, second.text)), a.vertical, a.lang, (a.lineSize + b.lineSize) / 2, a.lines + b.lines)
                bs.removeAt(j)
                changed = true
                break@loop
            }
        }
    }

    /** 글자 상자 바깥 테두리 색: 대부분 같은 색이면 말풍선/단색 배경(덮기), 아니면 그림 위 글자(반투명 처리) */
    private fun sampleBackground(bmp: Bitmap, b: Block) {
        val pad = 4
        val l = (b.rect.left - pad).coerceIn(0, bmp.width - 1); val r = (b.rect.right + pad).coerceIn(0, bmp.width - 1)
        val t = (b.rect.top - pad).coerceIn(0, bmp.height - 1); val bo = (b.rect.bottom + pad).coerceIn(0, bmp.height - 1)
        val px = ArrayList<Int>()
        val stepX = max(1, (r - l) / 40); val stepY = max(1, (bo - t) / 40)
        var x = l; while (x <= r) { px.add(bmp.getPixel(x, t)); px.add(bmp.getPixel(x, bo)); x += stepX }
        var y = t; while (y <= bo) { px.add(bmp.getPixel(l, y)); px.add(bmp.getPixel(r, y)); y += stepY }
        if (px.isEmpty()) return
        // 가장 흔한 색(대략 16단계로 묶어서)
        val bins = HashMap<Int, IntArray>()
        for (c in px) {
            val k = (Color.red(c) shr 4 shl 8) or (Color.green(c) shr 4 shl 4) or (Color.blue(c) shr 4)
            val a = bins.getOrPut(k) { IntArray(4) }
            a[0]++; a[1] += Color.red(c); a[2] += Color.green(c); a[3] += Color.blue(c)
        }
        val best = bins.values.maxByOrNull { it[0] }!!
        val bg = Color.rgb(best[1] / best[0], best[2] / best[0], best[3] / best[0])
        val near = px.count { abs(Color.red(it) - Color.red(bg)) + abs(Color.green(it) - Color.green(bg)) + abs(Color.blue(it) - Color.blue(bg)) < 60 }
        b.bg = bg
        b.flat = near > px.size * 0.75
    }
}
