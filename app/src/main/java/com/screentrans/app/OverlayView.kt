package com.screentrans.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 다른 앱 위에 깔리는 투명한 층: 원래 글자 자리에 번역을 그림 (터치는 아래 앱으로 그대로 통과) */
class OverlayView(ctx: Context) : View(ctx) {
    private var blocks: List<Block> = emptyList()
    private val loc = IntArray(2)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    val showing get() = blocks.isNotEmpty()

    fun show(list: List<Block>) { blocks = list.filter { !it.ko.isNullOrBlank() }; invalidate() }
    fun clear() { blocks = emptyList(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (blocks.isEmpty()) return
        // 캡처 좌표(화면 전체 기준) → 이 층의 좌표
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        for (b in blocks) drawBlock(canvas, b)
        canvas.restore()
    }

    private fun drawBlock(c: Canvas, b: Block) {
        val text = b.ko!!.trim()
        val pad = max(2f, b.lineSize * 0.12f)
        val r = RectF(b.rect.left - pad, b.rect.top - pad, b.rect.right + pad, b.rect.bottom + pad)
        val lum = 0.299 * Color.red(b.bg) + 0.587 * Color.green(b.bg) + 0.114 * Color.blue(b.bg)
        val dark = lum < 128
        // 배경: 말풍선·단색이면 그 색으로 덮고, 그림 위 글자면 반투명으로 살짝 가림
        fill.color = if (b.flat) b.bg else if (dark) Color.argb(215, 15, 15, 20) else Color.argb(215, 250, 250, 250)
        val rad = min(b.lineSize * 0.3f, 18f)
        c.drawRoundRect(r, rad, rad, fill)
        tp.color = if (dark) Color.WHITE else Color.rgb(17, 17, 17)
        val withStroke = !b.flat
        stroke.color = if (dark) Color.argb(230, 0, 0, 0) else Color.argb(230, 255, 255, 255)

        val maxSize = b.lineSize * 1.0f     // 원문 글자 크기를 넘지 않게
        val inner = RectF(r.left + pad, r.top + pad, r.right - pad, r.bottom - pad)
        val nChars = text.count { !it.isWhitespace() }
        // 세로 원문 + 짧은 번역이면 세로로, 아니면 가로로 (둘 중 글자가 더 크게 들어가는 쪽)
        val vSize = if (b.vertical && nChars <= 40) fitVertical(text, inner, maxSize) else 0f
        val hFit = fitHorizontal(text, inner, maxSize)
        if (vSize > 0 && vSize >= hFit.first * 1.1f) drawVertical(c, text, inner, vSize, withStroke)
        else drawHorizontal(c, hFit.second, inner, withStroke)
    }

    private fun mkLayout(text: String, w: Int, size: Float, p: TextPaint): StaticLayout {
        p.textSize = size
        return StaticLayout.Builder.obtain(text, 0, text.length, p, max(1, w))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).setIncludePad(false).build()
    }

    private fun fitHorizontal(text: String, r: RectF, maxSize: Float): Pair<Float, StaticLayout> {
        var lo = 6f; var hi = max(lo, maxSize)
        var best = mkLayout(text, r.width().toInt(), lo, tp)
        for (i in 0 until 14) {
            val mid = (lo + hi) / 2
            val l = mkLayout(text, r.width().toInt(), mid, tp)
            if (l.height <= r.height() && widestOk(l, r.width())) { lo = mid; best = l } else hi = mid
        }
        val l = mkLayout(text, r.width().toInt(), lo, tp)
        return Pair(lo, if (l.height <= r.height()) l else best)
    }

    private fun widestOk(l: StaticLayout, w: Float): Boolean {
        for (i in 0 until l.lineCount) if (l.getLineWidth(i) > w + 1) return false
        return true
    }

    private fun drawHorizontal(c: Canvas, l: StaticLayout, r: RectF, withStroke: Boolean) {
        c.save()
        c.translate(r.left, r.centerY() - l.height / 2f)
        if (withStroke) {
            stroke.textSize = tp.textSize; stroke.strokeWidth = max(2f, tp.textSize / 6f)
            val sl = StaticLayout.Builder.obtain(l.text, 0, l.text.length, stroke, l.width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.05f).setIncludePad(false).build()
            sl.draw(c)
        }
        l.draw(c)
        c.restore()
    }

    /** 세로쓰기로 몇 px까지 들어가는지 (0이면 안 들어감) */
    private fun fitVertical(text: String, r: RectF, maxSize: Float): Float {
        val n = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        var lo = 0f; var hi = maxSize
        for (i in 0 until 14) {
            val s = (lo + hi) / 2
            val perCol = floor(r.height() / (s * 1.04f)).toInt()
            val ok = perCol >= 1 && ceil(n / perCol.toFloat()) * s * 1.2f <= r.width()
            if (ok) lo = s else hi = s
        }
        return if (lo >= 6f) lo else 0f
    }

    private fun drawVertical(c: Canvas, text: String, r: RectF, s: Float, withStroke: Boolean) {
        tp.textSize = s; stroke.textSize = s; stroke.strokeWidth = max(2f, s / 6f)
        tp.textAlign = Paint.Align.CENTER; stroke.textAlign = Paint.Align.CENTER
        val perCol = max(1, floor(r.height() / (s * 1.04f)).toInt())
        val chars = text.filter { !it.isWhitespace() }
        val cols = ceil(chars.length / perCol.toFloat()).toInt()
        // 원문처럼 오른쪽 위에서 시작
        var x = r.right - s * 0.6f - max(0f, (r.width() - cols * s * 1.2f) * 0.15f)
        var i = 0
        while (i < chars.length) {
            var y = r.top + s * 0.9f
            for (k in 0 until perCol) {
                if (i >= chars.length) break
                val ch = chars[i].toString()
                val rotate = ch in listOf("ー", "-", "~", "〜", "…", "(", ")", "（", "）", "「", "」")
                if (rotate) { c.save(); c.rotate(90f, x, y - s * 0.35f) }
                if (withStroke) c.drawText(ch, x, y, stroke)
                c.drawText(ch, x, y, tp)
                if (rotate) c.restore()
                y += s * 1.04f; i++
            }
            x -= s * 1.2f
        }
        tp.textAlign = Paint.Align.LEFT; stroke.textAlign = Paint.Align.LEFT
    }
}
