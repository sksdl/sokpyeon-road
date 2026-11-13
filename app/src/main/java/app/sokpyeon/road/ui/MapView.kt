package app.sokpyeon.road.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.sokpyeon.road.core.Break
import app.sokpyeon.road.core.Route
import kotlin.math.cos
import kotlin.math.min

/** 경로 선, 굽은 길, 쉬어 가는 곳, 현재 위치를 그리는 간단한 지도. */
class MapView(context: Context) : View(context) {
    private var routes: List<Route> = emptyList()
    private var selected: Route? = null
    private var plan: List<Break> = emptyList()
    private var posS: Double = -1.0
    private val dp = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EEF2F6"); textSize = 15 * dp }
    private val colors = mapOf("A" to "#5AA9FF", "B" to "#F2716D", "C" to "#3CCFB4", "D" to "#5AA9FF", "E" to "#F2B84B")

    fun setData(routes: List<Route>, selected: Route?, plan: List<Break>, posS: Double) {
        this.routes = routes; this.selected = selected; this.plan = plan; this.posS = posS
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.drawColor(Color.parseColor("#0E131A"))
        if (routes.isEmpty()) return
        var x0 = 1e9; var x1 = -1e9; var y0 = 1e9; var y1 = -1e9
        for (r in routes) for (i in r.lon.indices) { x0 = min(x0, r.lon[i]); x1 = maxOf(x1, r.lon[i]); y0 = min(y0, r.lat[i]); y1 = maxOf(y1, r.lat[i]) }
        x0 -= 0.06; x1 += 0.16; y0 -= 0.06; y1 += 0.06
        val k = cos(Math.toRadians((y0 + y1) / 2))
        val sc = min(width / ((x1 - x0) * k), height / (y1 - y0))
        val ox = (width - (x1 - x0) * k * sc) / 2
        val oy = (height - (y1 - y0) * sc) / 2
        fun px(lon: Double) = (ox + (lon - x0) * k * sc).toFloat()
        fun py(lat: Double) = (oy + (y1 - lat) * sc).toFloat()

        for (r in routes.sortedBy { if (it === selected) 1 else 0 }) {
            val sel = r === selected
            val p = Path()
            for (i in r.lon.indices) if (i == 0) p.moveTo(px(r.lon[i]), py(r.lat[i])) else p.lineTo(px(r.lon[i]), py(r.lat[i]))
            line.color = Color.parseColor(colors[r.id] ?: "#97A2B3")
            line.alpha = if (sel) 255 else 90
            line.strokeWidth = (if (sel) 5 else 3) * dp
            c.drawPath(p, line)
        }
        selected?.let { r ->
            // 굽은 길 구간은 노란색으로 덧그린다
            line.color = Color.parseColor("#FFD24A"); line.alpha = 255; line.strokeWidth = 8 * dp
            for (sc2 in r.sections) {
                val q = Path(); var first = true
                for (i in r.lon.indices) if (r.polyS[i] >= sc2.s0 && r.polyS[i] <= sc2.s1) {
                    if (first) { q.moveTo(px(r.lon[i]), py(r.lat[i])); first = false } else q.lineTo(px(r.lon[i]), py(r.lat[i]))
                }
                if (!first) c.drawPath(q, line)
            }
            fill.color = Color.parseColor("#97A2B3")
            for (s in r.stops) { val (lo, la) = r.pointAt(s.s); c.drawCircle(px(lo), py(la), 3.5f * dp, fill) }
            fill.color = Color.parseColor("#3CCFB4")
            for (b in plan.filter { it.type == "rest" }) {
                val (lo, la) = r.pointAt(b.s)
                c.drawCircle(px(lo), py(la), 8 * dp, fill)
                val left = px(lo) > width * 0.6f
                text.textAlign = if (left) Paint.Align.RIGHT else Paint.Align.LEFT
                c.drawText(b.name, px(lo) + (if (left) -11 else 11) * dp, py(la) + 22 * dp, text)
                text.textAlign = Paint.Align.LEFT
            }
            c.drawText("출발", px(r.lon[0]) + 12 * dp, py(r.lat[0]) + 6 * dp, text)
            text.textAlign = Paint.Align.RIGHT; c.drawText(r.destName, px(r.destLon) - 8 * dp, py(r.destLat) - 12 * dp, text); text.textAlign = Paint.Align.LEFT
            if (posS >= 0) {
                val (lo, la) = r.pointAt(posS)
                fill.color = Color.WHITE
                c.drawCircle(px(lo), py(la), 11 * dp, fill)
                fill.color = Color.parseColor("#5AA9FF")
                c.drawCircle(px(lo), py(la), 7 * dp, fill)
            }
        }
    }
}
