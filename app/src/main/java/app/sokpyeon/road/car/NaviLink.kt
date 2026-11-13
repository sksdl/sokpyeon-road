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
class NaviLink(private val context: Context, private val log: (String) -> Unit, private val onStatus: (String) -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null
    @Volatile private var lastRequestAt = 0L     // 앱이 경로를 요청한 시각. 직후의 취소 이벤트는 이전 경로를 바꾼 것
    @Volatile private var cancelledByApp = false

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = status("경로 탐색 중", "내비: 경로 탐색 시작")
        override fun onRoutingCompleted() = status("경로 탐색 완료", "내비: 경로 탐색 완료")
        override fun onReRouteEnded() = status("경로 다시 찾음", "내비: 경로 재탐색 끝")
        override fun onWaypointChanged(info: ai.pleos.playground.navi.data.WaypointChangedInfo) = status(null, "내비: 경유지 변경됨")
        override fun onWayPointArrived(info: ai.pleos.playground.navi.data.WaypointArrivedInfo) = status("경유지 도착", "내비: 경유지 도착")
        override fun onDestinationArrived(info: ai.pleos.playground.navi.data.DestinationArrivedInfo) = status("목적지 도착", "내비: 목적지 도착")
        override fun onDestinationChanged() = status(null, "내비: 목적지 변경됨")
        override fun onNavigationStatus(navigationStatus: ai.pleos.playground.navi.constants.NavigationStatus) {
            Log.i(CarLink.TAG, "내비 상태값: $navigationStatus")
            if (navigationStatus == ai.pleos.playground.navi.constants.NavigationStatus.ROUTE_DRIVE) status("안내 중", null)
        }
        override fun onRouteCancelled() {
            val now = System.currentTimeMillis()
            when {
                // 경로를 새로 요청하면 내비가 이전 경로를 먼저 취소한다. 사용자가 취소한 것이 아니므로 상태를 바꾸지 않는다
                now - lastRequestAt < REPLACE_WINDOW_MS -> Log.i(CarLink.TAG, "내비: 이전 경로를 새 요청으로 바꾸면서 취소 이벤트")
                cancelledByApp -> status("안내 끝냄", "내비: 앱에서 안내를 끝냄")
                else -> status("경로 취소됨", "내비: 경로가 취소됨")
            }
        }
        override fun onError(errorCode: NaviErrorCode) = status("오류 $errorCode", "내비 오류: $errorCode")
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
            lastRequestAt = System.currentTimeMillis(); cancelledByApp = false
            if (wps.isEmpty()) n.requestRoute(dest) else n.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, wps))
            status("경로 요청함(경유지 ${rests.size}곳)", "내비에 요청: ${route.destName} · 경유지 ${rests.joinToString { it.name }.ifEmpty { "없음" }}")
        } catch (t: Throwable) {
            say("내비 요청 실패: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    fun cancel() {
        cancelledByApp = true
        try { navi?.cancelRoute() } catch (t: Throwable) { Log.w(CarLink.TAG, "cancel", t) }
    }

    private fun say(m: String) { Log.i(CarLink.TAG, m); main.post { log(m) } }

    private fun status(st: String?, m: String?) {
        if (m != null) Log.i(CarLink.TAG, m)
        main.post { if (st != null) onStatus(st); if (m != null) log(m) }
    }

    companion object { const val REPLACE_WINDOW_MS = 5000L }
}
