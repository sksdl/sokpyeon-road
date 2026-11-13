package app.sokpyeon.road.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ISO 2631-1:1997 Wf 가중 필터(멀미용). 표준 부속서 A의 아날로그 전달함수 네 단(고역 통과, 저역 통과,
 * 가속도-속도 전이, 상향 계단)을 쌍선형 변환으로 이산화했다. 분석 코드(scipy.signal.bilinear)와 같은 방식이다.
 */
class WfFilter(val fs: Double = 10.0) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0; var z2 = 0.0
        fun step(x: Double): Double { val y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y }
        fun gain(w: Double): Double {   // w = 2πf/fs (라디안/표본)
            val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
            val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
            val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
            return hypot(nr, ni) / hypot(dr, di)
        }
    }

    private val sections: List<Biquad>

    init {
        fun w(f: Double) = 2 * PI * f
        val q1 = 1 / sqrt(2.0)
        val w1 = w(0.08); val w2 = w(0.63); val w4 = w(0.25); val w5 = w(0.0625); val w6 = w(0.1)
        val q4 = 0.86; val q5 = 0.80; val q6 = 0.80
        // 아날로그 (n2 s² + n1 s + n0) / (s² + d1 s + d0)
        sections = listOf(
            bilinear(1.0, 0.0, 0.0, w1 / q1, w1 * w1),             // 고역 통과
            bilinear(0.0, 0.0, w2 * w2, w2 / q1, w2 * w2),         // 저역 통과
            bilinear(0.0, 0.0, w4 * w4, w4 / q4, w4 * w4),         // 가속도-속도 전이
            bilinear(1.0, w5 / q5, w5 * w5, w6 / q6, w6 * w6),     // 상향 계단
        )
    }

    private fun bilinear(n2: Double, n1: Double, n0: Double, d1: Double, d0: Double): Biquad {
        val k = 2 * fs; val kk = k * k
        val a0 = kk + d1 * k + d0
        return Biquad(
            (n2 * kk + n1 * k + n0) / a0, (2 * n0 - 2 * n2 * kk) / a0, (n2 * kk - n1 * k + n0) / a0,
            (2 * d0 - 2 * kk) / a0, (kk - d1 * k + d0) / a0,
        )
    }

    fun step(x: Double): Double { var y = x; for (s in sections) y = s.step(y); return y }
    fun reset() { for (s in sections) { s.z1 = 0.0; s.z2 = 0.0 } }

    /** 주파수 f(Hz)에서의 이득. 표준표와 대조하는 시험에 쓴다. */
    fun gainAt(f: Double): Double { val w = 2 * PI * f / fs; return sections.fold(1.0) { g, s -> g * s.gain(w) } }
}

/**
 * 주행 중 실측 멀미 누적(MSDV).
 * 위치가 들어올 때마다 진행 방향 변화율 ψ'과 차속 v로 좌우 가속도 a = v·ψ'를 구하고,
 * 0.1초(10Hz) 간격으로 보간해 Wf를 건 뒤 제곱을 시간에 따라 더한다. 누적은 √(Σ aw²·dt).
 * 위치가 2초 넘게 끊기면 그 사이는 경로 모형값으로 채우고 estimated를 켠다.
 * 차가 서 있으면(0.5m/s 미만) 휴식과 같은 규칙으로 누적 제곱이 시간상수 tRestS로 줄어든다.
 */
class LiveDoseMeter(private val tRestS: Double = 900.0, private val fs: Double = 10.0) {
    private class Fix(val t: Double, val x: Double, val y: Double)

    private val wf = WfFilter(fs)
    private val dt = 1.0 / fs
    private var lat0 = Double.NaN
    private var last: Fix? = null
    private var lastBearing = Double.NaN
    private var lastBearingT = 0.0
    private var prevSpeed = 0.0
    private var sampleT = Double.NaN      // 마지막으로 필터에 넣은 표본 시각
    private var sampleA = 0.0             // 그 표본의 좌우 가속도

    var cum2 = 0.0; private set
    val dose get() = sqrt(cum2)
    var lastAy = 0.0; private set          // 가장 최근 좌우 가속도(m/s², 가중 전)
    var lastSpeed = 0.0; private set       // 계산에 쓴 속도(m/s). 차속이 없으면 위치 변화로 구한 값
    var lastPosSpeed = 0.0; private set    // 위치 변화로 구한 속도(m/s)
    var estimated = false; private set     // 마지막 구간을 모형값으로 채웠으면 true
    var fixes = 0; private set

    /** 따라가기를 시작할 때 부른다. initial2는 그 지점까지의 누적 제곱(경로 중간에서 시작하면 모형값). */
    fun start(initial2: Double) {
        wf.reset(); cum2 = initial2; last = null; lastBearing = Double.NaN; sampleT = Double.NaN
        sampleA = 0.0; lastAy = 0.0; estimated = false; fixes = 0; lat0 = Double.NaN
        lastSpeed = 0.0; lastPosSpeed = 0.0; prevSpeed = 0.0
    }

    /**
     * 위치 한 건을 넣는다. tMs는 위치 시각, speedMs는 차량 차속(없거나 0.5m/s 미만이면 위치 변화로 구한다).
     * modelGap2는 위치가 끊겼을 때 그 사이 모형 누적 제곱 증가분을 돌려준다.
     */
    fun addFix(tMs: Long, lat: Double, lon: Double, speedMs: Double?, modelGap2: () -> Double): Double {
        if (lat0.isNaN()) lat0 = lat
        val t = tMs / 1000.0
        val f = Fix(t, Math.toRadians(lon) * R * cos(Math.toRadians(lat0)), Math.toRadians(lat) * R)
        val p = last
        last = f
        fixes++
        if (p == null) { sampleT = t; sampleA = 0.0; return dose }
        val gap = t - p.t
        if (gap <= 0) return dose
        if (gap > MAX_GAP_S) {
            cum2 += maxOf(0.0, modelGap2())
            estimated = true
            lastBearing = Double.NaN; sampleT = t; sampleA = 0.0
            return dose
        }
        estimated = false
        val dist = hypot(f.x - p.x, f.y - p.y)
        val vPos = dist / gap
        val v = if (speedMs != null && speedMs >= 0.5) speedMs else vPos
        lastPosSpeed = vPos
        lastSpeed = if (vPos < 0.5) 0.0 else v
        if (vPos < 0.5) {
            // 서 있는 동안: 가속도 0, 누적 제곱은 휴식처럼 줄어든다
            emitUntil(t, 0.0)
            cum2 *= exp(-gap / tRestS)
            lastAy = 0.0
            return dose
        }
        val bearing = atan2(f.x - p.x, f.y - p.y)
        val mid = (p.t + t) / 2
        var a = 0.0
        if (!lastBearing.isNaN()) {
            var d = bearing - lastBearing
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            val rate = d / (mid - lastBearingT)
            a = (v + prevSpeed) / 2 * rate
        }
        lastBearing = bearing; lastBearingT = mid; prevSpeed = v
        lastAy = a
        emitUntil(p.t, a)    // 방향 변화율은 두 구간의 가운데인 앞 위치 시각의 값
        return dose
    }

    /** sampleT 다음부터 tEnd까지 0.1초 간격으로 가속도를 선형 보간해 필터에 넣고 누적한다. */
    private fun emitUntil(tEnd: Double, aEnd: Double) {
        if (sampleT.isNaN()) { sampleT = tEnd; sampleA = aEnd; return }
        val t0 = sampleT; val a0 = sampleA
        if (tEnd <= t0) return
        var tt = t0 + dt
        while (tt <= tEnd + 1e-9) {
            val a = a0 + (aEnd - a0) * (tt - t0) / (tEnd - t0)
            val aw = wf.step(a)
            cum2 += aw * aw * dt
            tt += dt
        }
        sampleT = tt - dt; sampleA = aEnd
    }

    companion object {
        const val R = 6371000.0
        const val MAX_GAP_S = 2.0
    }
}
