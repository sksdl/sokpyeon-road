package app.sokpyeon.road.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class LiveDoseTest {
    @Test fun wfMatchesIsoTable() {
        // ISO 2631-1 표 A의 Wf 값(0.1~0.63Hz). 10Hz 이산 필터가 0.02 안에서 맞아야 한다
        val ref = mapOf(0.1 to 0.695, 0.125 to 0.895, 0.16 to 1.006, 0.2 to 0.992, 0.25 to 0.854,
            0.315 to 0.619, 0.4 to 0.384, 0.5 to 0.224, 0.63 to 0.116)
        val wf = WfFilter(10.0)
        for ((f, g) in ref) assertEquals("f=$f", g, wf.gainAt(f), 0.02)
    }

    /** 위도·경도로 바꾼 가상 주행 경로. heading(t)를 주면 1초마다 위치를 만든다. */
    private fun drive(meter: LiveDoseMeter, seconds: Int, v: Double, heading: (Double) -> Double, speed: Double? = v) {
        var x = 0.0; var y = 0.0
        val lat0 = 37.5; val lon0 = 127.0
        val k = cos(Math.toRadians(lat0))
        meter.start(0.0)
        val step = 0.01
        var t = 0.0
        var next = 0.0
        while (t <= seconds) {
            if (t >= next - 1e-9) {
                val lat = lat0 + Math.toDegrees(y / LiveDoseMeter.R)
                val lon = lon0 + Math.toDegrees(x / (LiveDoseMeter.R * k))
                meter.addFix((t * 1000).toLong(), lat, lon, speed) { 0.0 }
                next += 1.0
            }
            val h = heading(t)
            x += v * sin(h) * step; y += v * cos(h) * step
            t += step
        }
    }

    @Test fun sCurveDoseMatchesTheory() {
        // 좌우로 번갈아 도는 길: 0.16Hz, 좌우 가속도 진폭 1.5m/s². 이론값 aw_rms = 1.5/√2 × Wf(0.16)
        val v = 15.0; val f = 0.16; val amp = 1.5
        val headingAmp = amp / (v * 2 * PI * f)
        val m = LiveDoseMeter()
        drive(m, 600, v, { t -> headingAmp * sin(2 * PI * f * t) })
        val wf = WfFilter(10.0).gainAt(f)
        val theory = sqrt((amp / sqrt(2.0) * wf).let { it * it } * 600)
        // 위치가 1초마다 들어오면 방향 차분과 선형 보간이 0.16Hz 성분을 sinc⁴만큼(약 16%) 깎는다
        val x = PI * f * 1.0
        val chain = (sin(x) / x).let { it * it * it * it }
        assertEquals(theory * chain, m.dose, theory * chain * 0.05)
        assertTrue(m.dose > theory * 0.8)
    }

    @Test fun straightRoadStaysLow() {
        val m = LiveDoseMeter()
        drive(m, 600, 25.0, { 0.3 })
        assertTrue(m.dose < 1.0)
    }

    @Test fun slowerCurveGivesLessDose() {
        // 같은 길(같은 곡률)을 천천히 돌면 a = v²κ라 누적이 덜 오른다
        val kappa = 1.0 / 120
        fun run(v: Double): Double {
            val m = LiveDoseMeter()
            // 곡률이 0.08Hz 주기로 좌우를 바꾸는 길을 거리 기준으로 만든다
            drive(m, 300, v, { t -> 0.6 * sin(2 * PI * (v * t) / 400.0) })
            return m.dose
        }
        assertTrue(run(10.0) < run(15.0))
    }

    @Test fun gapIsFilledWithModel() {
        val m = LiveDoseMeter()
        m.start(4.0)
        m.addFix(0, 37.5, 127.0, 20.0) { 0.0 }
        m.addFix(5000, 37.501, 127.0, 20.0) { 21.0 }   // 5초 끊김
        assertTrue(m.estimated)
        assertEquals(25.0, m.cum2, 1e-9)
        assertEquals(5.0, m.dose, 1e-9)
    }

    @Test fun standingStillRecoversLikeRest() {
        val m = LiveDoseMeter()
        m.start(100.0)
        for (i in 0..900) m.addFix(i * 1000L, 37.5, 127.0, 0.0) { 0.0 }
        assertEquals(100.0 * exp(-1.0), m.cum2, 1.0)
    }

    @Test fun routeReplayIsSameOrderAsModel() {
        // 미시령터널 길의 굽은 길 구간(굽이 11번)을 모형 속도로 다시 달려 본다
        val data = RouteData.parse(File("src/main/assets/routes.json").readText())
        val c = data.byId("C")
        val sec = c.sections.maxByOrNull { it.turns }!!
        val s0 = sec.s0 - 300; val s1 = sec.s1 + 300
        val t0 = c.series.timeAtS(s0); val t1 = c.series.timeAtS(s1)
        val m = LiveDoseMeter()
        m.start(0.0)
        var t = t0
        while (t <= t1) {
            val s = c.series.at(c.series.s, t)
            val (lon, lat) = pointOnPoly(c, s)
            m.addFix(((t - t0) * 1000).toLong(), lat, lon, c.series.at(c.series.v, t) / 3.6) { 0.0 }
            t += 1.0
        }
        val d2 = { tt: Double -> c.series.at(c.series.dose, tt).let { it * it } }
        val model = sqrt(d2(t1) - d2(t0))
        println("굽은 길 ${sec.turns}번 구간: 실측 재생 ${"%.2f".format(m.dose)}, 모형 ${"%.2f".format(model)}")
        assertTrue(m.dose > model * 0.5 && m.dose < model * 2.0)
    }

    private fun pointOnPoly(r: Route, s: Double): Pair<Double, Double> {
        var i = 0
        while (i < r.polyS.size - 2 && r.polyS[i + 1] < s) i++
        val f = ((s - r.polyS[i]) / (r.polyS[i + 1] - r.polyS[i])).coerceIn(0.0, 1.0)
        return (r.lon[i] + f * (r.lon[i + 1] - r.lon[i])) to (r.lat[i] + f * (r.lat[i + 1] - r.lat[i]))
    }
}
