package app.sokpyeon.road.core

import org.json.JSONArray
import org.json.JSONObject

/** 굽은 길 구간. s0·s1은 경로 시작에서 잰 거리(m). */
data class Section(val s0: Double, val s1: Double, val lenM: Double, val turns: Int)

/** 안내 지점. t는 주행 시간(초), s는 거리(m). kind는 "section"(800m 앞 예고) 또는 "turn"(급한 굽이). */
data class Cue(val t: Double, val s: Double, val kind: String, val text: String)

/** 쉴 수 있는 곳(휴게소·졸음쉼터). */
data class Stop(val name: String, val kind: String, val s: Double, val familyToilet: String?)

/** 쉬어도 기준선을 넘는 굽은 길. */
data class OverSection(val s0: Double, val lenM: Double, val dose: Double)

/** 2초 간격 주행 기록. dose는 멀미 누적(MSDV, 휴식 없이 달렸을 때). */
class Series(val t: DoubleArray, val s: DoubleArray, val v: DoubleArray, val ay: DoubleArray, val dose: DoubleArray) {
    val size get() = t.size
    val endT get() = t[t.size - 1]

    /** 시각 t의 선형 보간값. */
    fun at(arr: DoubleArray, time: Double): Double = interp(arr, t, time)

    /** 거리 s에 닿는 시각. */
    fun timeAtS(dist: Double): Double = interp(t, s, dist)
}

class Route(
    val id: String,
    val dest: String,
    val destName: String,
    val name: String,
    val km: Double,
    val minutes: Double,
    val msdv: Double,
    val effEnd: Double,
    val lon: DoubleArray,
    val lat: DoubleArray,
    val polyS: DoubleArray,
    val sections: List<Section>,
    val cues: List<Cue>,
    val stops: List<Stop>,
    val over: List<OverSection>,
    val series: Series,
) {
    val destLat get() = lat[lat.size - 1]
    val destLon get() = lon[lon.size - 1]

    /** 경로 거리 s의 좌표 (경도, 위도). */
    fun pointAt(dist: Double): Pair<Double, Double> {
        var i = 0
        while (i < polyS.size - 1 && polyS[i + 1] < dist) i++
        return lon[i] to lat[i]
    }

    /** 위치에서 가장 가까운 경로 지점의 거리 s(m). 실주행 위치를 경로 위에 맞출 때 쓴다. */
    fun matchS(longitude: Double, latitude: Double): Double {
        val k = Math.cos(Math.toRadians(latitude))
        var best = 0
        var bd = Double.MAX_VALUE
        for (i in lon.indices) {
            val dx = (lon[i] - longitude) * k
            val dy = lat[i] - latitude
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return polyS[best]
    }
}

class Model(val aLat: Double, val cueLeadM: Double, val restMin: Double, val tRest: Double)

class RouteData(val model: Model, val childThr: Double, val routes: List<Route>) {
    fun routesTo(dest: String) = routes.filter { it.dest == dest }
    fun byId(id: String) = routes.first { it.id == id }

    companion object {
        fun parse(json: String): RouteData {
            val o = JSONObject(json)
            val m = o.getJSONObject("model")
            val model = Model(m.getDouble("A_LAT"), m.getDouble("CUE_LEAD_M"), m.getDouble("REST_MIN"), m.getDouble("T_REST"))
            val routes = o.getJSONArray("routes").let { a -> (0 until a.length()).map { parseRoute(a.getJSONObject(it)) } }
            return RouteData(model, o.getDouble("child_thr"), routes)
        }

        private fun doubles(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }

        private fun parseRoute(o: JSONObject): Route {
            val poly = o.getJSONArray("poly")
            val n = poly.length()
            val lon = DoubleArray(n) { poly.getJSONArray(it).getDouble(0) }
            val lat = DoubleArray(n) { poly.getJSONArray(it).getDouble(1) }
            val sr = o.getJSONObject("series")
            val series = Series(doubles(sr.getJSONArray("t")), doubles(sr.getJSONArray("s")), doubles(sr.getJSONArray("v")),
                doubles(sr.getJSONArray("ay")), doubles(sr.getJSONArray("dose")))
            fun <T> list(key: String, f: (JSONObject) -> T): List<T> =
                o.getJSONArray(key).let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }
            return Route(
                id = o.getString("id"), dest = o.getString("dest"), destName = o.getString("dest_name"), name = o.getString("name"),
                km = o.getDouble("km"), minutes = o.getDouble("min"), msdv = o.getDouble("msdv"), effEnd = o.getDouble("eff_end"),
                lon = lon, lat = lat, polyS = doubles(o.getJSONArray("poly_s")),
                sections = list("sections") { Section(it.getDouble("s0"), it.getDouble("s1"), it.getDouble("len_m"), it.getInt("turns")) },
                cues = list("cues") { Cue(it.getDouble("t"), it.getDouble("s"), it.getString("kind"), it.getString("text")) },
                stops = list("stops") { Stop(it.getString("name"), it.getString("kind"), it.getDouble("s"), it.optString("fam").takeIf { f -> f.isNotEmpty() && f != "null" }) },
                over = list("over_sections") { OverSection(it.getDouble("s0"), it.getDouble("len_m"), it.getDouble("dose")) },
                series = series,
            )
        }
    }
}

/** arr[i]를 x[i] 기준으로 보간한다. x는 오름차순. */
fun interp(arr: DoubleArray, x: DoubleArray, v: Double): Double {
    val hi0 = x.size - 1
    if (v <= x[0]) return arr[0]
    if (v >= x[hi0]) return arr[hi0]
    var lo = 0
    var hi = hi0
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (x[mid] <= v) lo = mid else hi = mid
    }
    val f = (v - x[lo]) / (x[hi] - x[lo])
    return arr[lo] + f * (arr[hi] - arr[lo])
}
