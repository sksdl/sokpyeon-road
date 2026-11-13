package app.sokpyeon.road.core

import kotlin.math.exp
import kotlin.math.sqrt

/** 휴식 계획 한 건. type은 "rest" 또는 "no_stop"(쉴 곳이 없는 채로 기준선에 닿는 지점). */
data class Break(val type: String, val name: String, val kind: String, val s: Double, val t: Double,
                 val doseBefore: Double, val doseAfter: Double, val familyToilet: String?)

object Planner {
    const val MARGIN = 0.8        // 기준선의 80%에 닿기 전에 쉰다
    const val MIN_GAP_S = 1200.0  // 쉬는 곳 사이 최소 20분

    /**
     * 누적 선량이 기준선의 80%에 닿기 전에 쉴 곳을 고른다. 아이를 태웠으니 휴게소를 졸음쉼터보다 먼저 고른다.
     * 쉴 때마다 누적(제곱)은 exp(-restMin/tRest)배로 줄고, 이후 구간에서 그만큼 빠진다.
     */
    fun planBreaks(route: Route, model: Model, thr: Double, stops: List<Stop> = route.stops): List<Break> {
        val ser = route.series
        val eff = DoubleArray(ser.size) { ser.dose[it] * ser.dose[it] }
        val limit = (MARGIN * thr) * (MARGIN * thr)
        val plan = mutableListOf<Break>()
        var lastT = 0.0
        var lastS = -1.0
        repeat(8) {
            var k = -1
            for (i in 0 until ser.size) if (eff[i] >= limit && ser.t[i] > lastT) { k = i; break }
            if (k < 0) return plan
            val cands = stops.filter { it.s > lastS && it.s < ser.s[k] && ser.timeAtS(it.s) - lastT >= MIN_GAP_S }
            val pick = (cands.filter { it.kind == "휴게소" }.ifEmpty { cands }).lastOrNull()
            if (pick == null) {
                plan += Break("no_stop", "", "", ser.s[k], ser.t[k], 0.0, 0.0, null)
                return plan
            }
            var j = 0
            while (j < ser.size - 1 && ser.s[j] < pick.s) j++
            val before = eff[j]
            val after = before * exp(-model.restMin / model.tRest)
            for (i in j until ser.size) eff[i] -= (before - after)
            plan += Break("rest", pick.name, pick.kind, pick.s, ser.t[j], r1(sqrt(before)), r1(sqrt(after)), pick.familyToilet)
            lastT = ser.t[j]
            lastS = pick.s
        }
        return plan
    }

    private fun r1(v: Double) = Math.round(v * 10) / 10.0
}

/** 여정(주행 + 휴식) 한 토막. */
class Seg(val rest: Boolean, val a: Double, val d0: Double, val d1: Double, val dur: Double, val stop: Break?)

/** 여정 시각 T의 상태. */
class TripState(
    val resting: Boolean, val stop: Break?, val left: Double, val before: Double,
    val td: Double, val s: Double, val v: Double, val ay: Double, val dose: Double,
)

class Trip(val route: Route, val model: Model, plan: List<Break>) {
    private val restS = model.restMin * 60
    private val tRestS = model.tRest * 60
    val segs = ArrayList<Seg>()
    val total: Double

    init {
        var tTrip = 0.0
        var tDrive = 0.0
        for (p in plan.filter { it.type == "rest" }.sortedBy { it.t }) {
            segs += Seg(false, tTrip, tDrive, p.t, 0.0, null); tTrip += p.t - tDrive; tDrive = p.t
            segs += Seg(true, tTrip, 0.0, 0.0, restS, p); tTrip += restS
        }
        val end = route.series.endT
        segs += Seg(false, tTrip, tDrive, end, 0.0, null); tTrip += end - tDrive
        total = tTrip
    }

    /** 누적(제곱)은 휴식 중 지수적으로 줄고, 다시 달리면 그 뒤 증가분만 더한다. */
    fun stateAt(T: Double): TripState {
        val ser = route.series
        fun d2(t: Double): Double { val v = ser.at(ser.dose, t); return v * v }
        var eff2 = 0.0
        var lastDrive = 0.0
        var resting = false
        var stop: Break? = null
        var left = 0.0
        var before = 0.0
        var td = -1.0
        for (g in segs) {
            if (!g.rest) {
                val tEnd = g.a + (g.d1 - g.d0)
                val x = if (T >= tEnd) g.d1 else g.d0 + (T - g.a)
                if (T < g.a) break
                eff2 += d2(x) - d2(g.d0)
                lastDrive = x
                if (T <= tEnd) { td = x; break }
            } else {
                if (T < g.a) break
                val tau = minOf(T - g.a, g.dur)
                before = eff2
                eff2 = before * exp(-tau / tRestS)
                if (T <= g.a + g.dur) { resting = true; stop = g.stop; left = g.dur - tau; break }
            }
        }
        val t = if (resting || td < 0) lastDrive else td
        return TripState(
            resting, stop, left, sqrt(before), t, ser.at(ser.s, t),
            if (resting) 0.0 else ser.at(ser.v, t), if (resting) 0.0 else ser.at(ser.ay, t),
            sqrt(maxOf(eff2, 0.0)),
        )
    }
}
