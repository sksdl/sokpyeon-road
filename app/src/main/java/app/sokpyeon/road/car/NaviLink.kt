package app.sokpyeon.road.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.sokpyeon.road.core.Break
import app.sokpyeon.road.core.Route

/** NaviHelper SDK로 내비에 목적지와 휴게소 경유지를 넣는다. */
class NaviLink(private val context: Context, private val log: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = say("내비: 경로 탐색 시작")
        override fun onRoutingCompleted() = say("내비: 경로 탐색 완료")
        override fun onWaypointChanged(info: ai.pleos.playground.navi.data.WaypointChangedInfo) = say("내비: 경유지 변경됨")
        override fun onWayPointArrived(info: ai.pleos.playground.navi.data.WaypointArrivedInfo) = say("내비: 경유지 도착")
        override fun onDestinationChanged() = say("내비: 목적지 변경됨")
        override fun onRouteCancelled() = say("내비: 경로 취소")
        override fun onError(errorCode: NaviErrorCode) = say("내비 오류: $errorCode")
    }

    var ready = false
        private set

    fun start() {
        try {
            val n = NaviHelper(context)
            n.initialize({ ready = true; say("NaviHelper 초기화 완료") }, { t -> say("NaviHelper 초기화 실패: ${t.message}") })
            n.addListener(listener)
            navi = n
        } catch (t: Throwable) {
            say("NaviHelper 시작 실패: ${t.javaClass.simpleName} ${t.message}")
            Log.w(CarLink.TAG, "navi start", t)
        }
    }

    fun stop() {
        try {
            navi?.let { it.removeListener(listener); it.release() }
        } catch (t: Throwable) {
            Log.w(CarLink.TAG, "navi stop", t)
        }
        navi = null
        ready = false
    }

    /** 목적지와 쉬어 갈 곳(최대 2곳)을 경유지로 넣어 경로를 요청한다. 아이 기준선 80%에 닿기 전 휴게소가 경유지가 된다. */
    fun requestWithWaypoints(route: Route, plan: List<Break>) {
        val n = navi ?: return say("내비 연결 전이라 경로 요청을 건너뜀")
        val rests = plan.filter { it.type == "rest" }.sortedBy { it.s }.take(2)
        try {
            val dest = RouteInfo(
                latitude = route.destLat, longitude = route.destLon,
                poiName = route.destName, poiId = "sokpyeon-${route.dest}", address = route.destName,
                poiSubId = "0", routeOption = RouteOption.RECOMMENDED,
            )
            val wps = rests.mapIndexed { i, b ->
                val (lon, lat) = route.pointAt(b.s)
                RequestWaypointInfo(
                    latitude = lat, longitude = lon, poiName = b.name,
                    waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    poiId = "sokpyeon-rest-${b.s.toInt()}", address = b.name, poiSubId = "0",
                )
            }
            if (wps.isEmpty()) n.requestRoute(dest) else n.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, wps))
            say("내비에 요청: ${route.destName} · 경유지 ${rests.joinToString { it.name }.ifEmpty { "없음" }}")
        } catch (t: Throwable) {
            say("내비 요청 실패: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    fun cancel() {
        try { navi?.cancelRoute() } catch (t: Throwable) { Log.w(CarLink.TAG, "cancel", t) }
    }

    private fun say(m: String) { Log.i(CarLink.TAG, m); main.post { log(m) } }
}
