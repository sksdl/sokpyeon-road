package app.sokpyeon.road.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import app.sokpyeon.road.car.CarLink
import app.sokpyeon.road.car.CarValues
import app.sokpyeon.road.car.NaviLink
import app.sokpyeon.road.car.Voice
import app.sokpyeon.road.core.Break
import app.sokpyeon.road.core.Guidance
import app.sokpyeon.road.core.LiveDoseMeter
import app.sokpyeon.road.core.Planner
import app.sokpyeon.road.core.Route
import app.sokpyeon.road.core.RouteData
import app.sokpyeon.road.core.Trip
import app.sokpyeon.road.core.TripState

/**
 * 속편한길: 경로별 아이 멀미 누적을 보여 주고, 기준선 80% 전에 쉬어 갈 곳을 경유지로 넣고,
 * 굽은 길 800m 전에 말로 알리고, "속 안 좋아"라는 말을 기록한다.
 * 화면은 차량용으로 큰 글씨·큰 버튼 위주로 짠다.
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var data: RouteData
    private lateinit var car: CarLink
    private lateinit var navi: NaviLink
    private lateinit var voice: Voice

    // 상태
    private var dest = "sokcho"
    private lateinit var route: Route
    private var thr = 25.0
    private var nextThr = 25.0
    private var plan: List<Break> = emptyList()
    private lateinit var trip: Trip
    private var T = 0.0
    private var playing = false
    private var speed = 30
    private var driving = false
    private var prevTd = 0.0
    private var follow = false          // true면 시연 대신 FusedLocation 위치를 경로에 맞춰 따라간다
    private var carValues = CarValues()
    private var sickCount = 0
    private var arrowUntil = 0L
    private val logLines = ArrayDeque<String>()
    private var navStatus = "연결 대기"
    private lateinit var meter: LiveDoseMeter     // 위치 따라가기 때 실측 누적
    private var meterOn = false
    private var lastFixT = -1L
    private var prevLat = 0.0
    private var prevLon = 0.0

    // 뷰
    private lateinit var root: LinearLayout
    private lateinit var carChips: TextView
    private lateinit var modeChip: TextView
    private lateinit var logView: TextView
    private lateinit var planScreen: LinearLayout
    private lateinit var driveScreen: LinearLayout
    private lateinit var planMap: MapView
    private lateinit var driveMap: MapView
    private lateinit var cards: LinearLayout
    private lateinit var destRow: LinearLayout
    private lateinit var thrNote: TextView
    private lateinit var gaugeTitle: TextView
    private lateinit var gaugeNum: TextView
    private lateinit var gaugeBar: ProgressBar
    private lateinit var gaugeHint: TextView
    private lateinit var nextCurve: TextView
    private lateinit var nextCurveSub: TextView
    private lateinit var nextRest: TextView
    private lateinit var nextRestSub: TextView
    private lateinit var voiceBox: TextView
    private lateinit var arrow: TextView
    private lateinit var restBox: TextView
    private lateinit var playBtn: Button
    private lateinit var speedBtn: Button
    private lateinit var followBtn: Button
    private lateinit var clock: TextView
    private lateinit var seek: android.widget.SeekBar

    private val dp by lazy { resources.displayMetrics.density }
    private fun px(v: Int) = (v * dp).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = RouteData.parse(assets.open("routes.json").bufferedReader().use { it.readText() })
        thr = getSharedPreferences("sok", Context.MODE_PRIVATE).getFloat("thr", data.childThr.toFloat()).toDouble()
        nextThr = thr
        meter = LiveDoseMeter(data.model.tRest * 60)
        buildUi()
        selectDest("sokcho")

        val need = listOf(Manifest.permission.RECORD_AUDIO, "pleos.car.permission.FUSED_LOCATION", "android.car.permission.CAR_SPEED")
            .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        car = CarLink(applicationContext, ::log) { v -> carValues = v; onCarValues() }
        navi = NaviLink(applicationContext, ::log) { st -> navStatus = st; showLog() }
        voice = Voice(applicationContext, ::log) { text, final -> onHeard(text, final) }
        car.start(); navi.start(); voice.start()
        if (checkSelfPermission("pleos.car.permission.FUSED_LOCATION") == android.content.pm.PackageManager.PERMISSION_GRANTED) car.startLocation()
        ui.post(ticker)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            if (p == "pleos.car.permission.FUSED_LOCATION" && grantResults.getOrNull(i) == android.content.pm.PackageManager.PERMISSION_GRANTED) car.startLocation()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        car.stop(); navi.stop(); voice.stop()
        super.onDestroy()
    }

    // ---------------- 계획 ----------------

    private fun selectDest(d: String) {
        dest = d
        val best = recommend(d)
        selectRoute(best)
        destRow.getChildAt(0).alpha = if (d == "sokcho") 1f else 0.5f
        destRow.getChildAt(1).alpha = if (d == "gangneung") 1f else 0.5f
    }

    private fun resetThr() {
        thr = data.childThr; nextThr = thr
        getSharedPreferences("sok", Context.MODE_PRIVATE).edit().remove("thr").apply()
        selectDest(dest)
    }

    private fun selectRoute(r: Route) {
        route = r
        plan = Planner.planBreaks(r, data.model, thr)
        trip = Trip(r, data.model, plan)
        drawPlan()
    }

    private class Verdict(val tag: String, val color: Int, val text: String, val rests: Int)

    private fun verdict(r: Route): Verdict {
        val p = Planner.planBreaks(r, data.model, thr)
        val rests = p.count { it.type == "rest" }
        val noStop = p.any { it.type == "no_stop" }
        val over = r.over.firstOrNull()
        if (over != null && over.dose >= thr)
            return Verdict("비추천", COLOR_BAD, "${km(over.s0)} 지점부터 ${km(over.lenM)} 이어지는 굽은 길 하나에서만 ${f1(over.dose)}이 쌓여요. 쉬어도 기준선을 넘어요.", rests)
        val end = Trip(r, data.model, p).stateAt(Double.MAX_VALUE / 4).dose
        if (noStop || end > 0.8 * thr)
            return Verdict("주의", COLOR_WARN, "휴게소 ${rests}곳에서 쉬어도 끝에서 ${f1(end)}까지 올라가요. 마지막 굽은 길에서는 알림을 촘촘히 드려요.", rests)
        return Verdict("괜찮음", COLOR_OK, if (rests > 0) "휴게소 ${rests}곳에서 15분씩 쉬면 ${f1(end)}에서 끝나요." else "쉬지 않고 가도 ${f1(end)}에서 끝나 기준선 ${f1(thr)}보다 낮아요.", rests)
    }

    private fun recommend(d: String): Route {
        fun score(r: Route): Double {
            val v = verdict(r)
            return (if (v.tag == "비추천") 1e6 else if (v.tag == "주의") 1e4 else 0.0) + r.minutes + v.rests * 15
        }
        return data.routesTo(d).minByOrNull { score(it) }!!
    }

    private fun drawPlan() {
        planMap.setData(data.routesTo(dest), route, plan, -1.0)
        cards.removeAllViews()
        for (r in data.routesTo(dest)) {
            val v = verdict(r)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(18), px(12), px(18), px(12))
                background = rounded(if (r === route) "#15221F" else "#181D26", 14, if (r === route) "#3CCFB4" else "#181D26")
                setOnClickListener { selectRoute(r) }
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(tv(r.name, 22, "#EEF2F6", bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(tv(" ${v.tag} ", 16, "#0B0E13", bold = true).apply { background = rounded(hex(v.color), 99, hex(v.color)) })
            card.addView(head)
            card.addView(tv("${fmtMin(r.minutes)} · ${f1(r.km)}km · 쉬지 않으면 멀미 ${f1(r.msdv)} · 쉬면 ${f1(r.effEnd)}", 16, "#97A2B3"))
            card.addView(tv(v.text, 16, "#EEF2F6"))
            cards.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) })
        }
        val rests = plan.filter { it.type == "rest" }
        cards.addView(tv(
            if (rests.isEmpty()) "쉴 곳 없이 갈 수 있어요" else "쉬어 갈 곳: " + rests.joinToString(" → ") { "${it.name} ${km(it.s)}" },
            17, "#EEF2F6",
        ))
        thrNote.text = "아이 기준선 ${f1(thr)} · 80%(${f1(thr * 0.8)})에 닿기 전에 쉬는 곳을 경유지로 넣어요"
    }

    // ---------------- 주행 ----------------

    private fun startDrive(followLocation: Boolean) {
        follow = followLocation
        trip = Trip(route, data.model, plan)
        T = 0.0; prevTd = 0.0; sickCount = 0; driving = true; playing = !follow; meterOn = false
        planScreen.visibility = View.GONE; driveScreen.visibility = View.VISIBLE
        followBtn.text = if (follow) "위치 따라가기 중" else "위치 따라가기"
        playBtn.visibility = if (follow) View.GONE else View.VISIBLE
        speedBtn.visibility = if (follow) View.GONE else View.VISIBLE
        modeChip.text = if (follow) "차량 위치로 주행" else "시연 주행"
        onCarValues()
        navi.requestWithWaypoints(route, plan)
        say("속편한길 안내를 시작할게요. ${ro(route.name)} ${route.destName}까지 갑니다. " + plan.count { it.type == "rest" }.let { if (it == 0) "쉬지 않고 가도 기준선보다 낮아요." else "휴게소 ${it}곳에서 쉬어 가요." })
        renderDrive()
    }

    private fun stopDrive() {
        driving = false; playing = false
        driveScreen.visibility = View.GONE; planScreen.visibility = View.VISIBLE
        modeChip.text = "경로 선택"
        onCarValues()
        navi.cancel()
        thr = nextThr          // 이번 여행에서 배운 기준선은 다음 계획부터 쓴다
        selectDest(dest)
    }

    private var lastTick = 0L
    private val ticker = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = if (lastTick == 0L) 0.1 else (now - lastTick) / 1000.0
            lastTick = now
            if (driving) {
                if (follow) followLocation()
                else if (playing) {
                    T = minOf(trip.total, T + dt * speed)
                    if (T >= trip.total) { playing = false; say("목적지에 도착했어요. 멀미 누적은 ${f1(trip.stateAt(T).dose)}로 끝났어요.") }
                }
                renderDrive()
            }
            ui.postDelayed(this, 100)
        }
    }

    /** 차량 위치를 경로에 맞춰 여정 시각을 정한다. 경로에서 2km 넘게 벗어나면 따라가지 않는다. */
    private fun followLocation() {
        val lat = carValues.lat ?: return
        val lon = carValues.lon ?: return
        val s = route.matchS(lon, lat)
        var lo = 0.0; var hi = trip.total
        repeat(30) { val mid = (lo + hi) / 2; if (trip.stateAt(mid).s >= s) hi = mid else lo = mid }
        T = hi
    }

    private fun renderDrive() {
        val S: TripState = trip.stateAt(T)
        clock.text = "여정 ${fmtMin(T / 60)} / ${fmtMin(trip.total / 60)}"
        seek.progress = (T / trip.total * 1000).toInt()
        val live = follow && meterOn
        val shown = if (live) meter.dose else S.dose
        val max = thr * 1.3
        gaugeTitle.text = if (live) "멀미 누적(실측)" else "멀미 누적 예보"
        gaugeNum.text = "${f1(shown)} / ${f1(thr)}"
        gaugeBar.progress = (minOf(1.0, shown / max) * 1000).toInt()
        gaugeBar.progressDrawable.setTint(Color.parseColor(if (shown >= thr) "#F2716D" else if (shown >= 0.8 * thr) "#F2B84B" else "#3CCFB4"))
        gaugeHint.text = if (live) {
            val end2 = trip.stateAt(trip.total).dose.let { it * it }
            val arrive = Math.sqrt(maxOf(0.0, meter.cum2 + end2 - S.dose * S.dose))
            "좌우 ${"%.1f".format(meter.lastAy)}m/s² · 모형 ${f1(S.dose)} · 도착 예보 ${f1(arrive)}" + if (meter.estimated) " · 예보값으로 계산 중" else ""
        } else "쉬기 권장 ${f1(thr * 0.8)} · 기준선 ${f1(thr)}"
        driveMap.setData(data.routesTo(dest), route, plan, S.s)

        if (S.resting) {
            val st = S.stop!!
            restBox.visibility = View.VISIBLE
            restBox.text = "${st.name}에서 쉬는 중  %02d:%02d\n멀미 누적 ${f1(S.before)} → ${f1(S.dose)} · 창문을 열고 바깥 공기를 쐬어 주세요".format((S.left / 60).toInt(), (S.left % 60).toInt())
        } else {
            restBox.visibility = View.GONE
            for (n in Guidance.cuesBetween(route, prevTd, S.td)) {
                if (n.kind == "voice") say(n.text) else {
                    arrow.text = if (n.side == 'L') "←" else "→"
                    arrow.visibility = View.VISIBLE
                    arrowUntil = System.currentTimeMillis() + 1500
                    android.util.Log.i(CarLink.TAG, "CHIME: ${n.text}")
                    voice.chime(n.side == 'L')
                    voiceBox.text = n.text
                }
            }
            prevTd = S.td
        }
        if (arrow.visibility == View.VISIBLE && System.currentTimeMillis() > arrowUntil) arrow.visibility = View.INVISIBLE

        val spd = when {
            !follow -> "시연 ${Math.round(S.v)}km/h"
            meterOn -> "속도 ${Math.round(meter.lastSpeed * 3.6)}km/h"      // 실측 누적에 쓴 속도
            else -> "차속 ${carValues.speedKmh?.let { Math.round(it) } ?: 0}km/h"
        }
        val cur = route.sections.firstOrNull { S.s >= it.s0 && S.s <= it.s1 }
        val nxt = route.sections.firstOrNull { it.s0 > S.s }
        if (cur != null) { nextCurve.text = "굽은 길 안 · ${f1((cur.s1 - S.s) / 1000)}km 남음"; nextCurveSub.text = "굽이 ${cur.turns}번 구간 · $spd" }
        else if (nxt != null) { nextCurve.text = "다음 굽은 길 ${if (nxt.s0 - S.s < 50) "곧 시작" else f1((nxt.s0 - S.s) / 1000) + "km"}"; nextCurveSub.text = "굽이 ${nxt.turns}번 · ${km(nxt.lenM)} 이어짐 · $spd" }
        else { nextCurve.text = "남은 굽은 길 없음"; nextCurveSub.text = spd }

        val nr = plan.filter { it.type == "rest" && it.s > S.s + 1 }.minByOrNull { it.s }
        val ns = plan.firstOrNull { it.type == "no_stop" && it.s > S.s - 20000 }
        if (nr != null) { nextRest.text = nr.name; nextRestSub.text = "${f1((nr.s - S.s) / 1000)}km · 15분 쉬기 예정${if (nr.familyToilet != null) " · 가족사랑화장실" else ""}" }
        else if (ns != null && S.dose > 0.7 * thr) { nextRest.text = "남은 굽은 길에 쉴 곳이 없어요"; nextRestSub.text = "속도를 조금 줄이고 창문을 살짝 열어 주세요" }
        else { nextRest.text = "남은 길은 기준선 아래로 예보돼요"; nextRestSub.text = "남은 거리 ${f1((route.km * 1000 - S.s) / 1000)}km" }
    }

    // ---------------- 말 ----------------

    private fun say(text: String) { android.util.Log.i(CarLink.TAG, "SAY: $text"); voiceBox.text = text; voice.speak(text) }

    /** STT 결과. "속 안 좋아" 같은 말이면 기록한다. */
    private fun onHeard(text: String, final: Boolean) {
        voiceBox.text = "\"$text\""
        if (final || Guidance.isSickPhrase(text)) {
            log("STT: $text")
            if (Guidance.isSickPhrase(text) && driving) { voice.stopListening(); childReport(text) }
        }
    }

    private fun childReport(text: String) {
        if (!driving) return
        val S0 = trip.stateAt(T)
        val S = if (follow && meterOn) app.sokpyeon.road.core.TripState(S0.resting, S0.stop, S0.left, S0.before, S0.td, S0.s, S0.v, S0.ay, meter.dose) else S0
        sickCount++
        val cand = Guidance.nearerStop(route, S.s, plan)
        var msg = "\"$text\" 기록했어요. 지금 멀미 누적 ${f1(S.dose)}."
        if (cand != null) {
            val next = plan.filter { it.type == "rest" && it.s > S.s }.minByOrNull { it.s }
            val t = route.series.timeAtS(cand.s)
            val keep = plan.filter { it !== next }.toMutableList()
            keep += Break("rest", cand.name, cand.kind, cand.s, t, 0.0, 0.0, cand.familyToilet)
            plan = keep.sortedBy { it.s }
            val keepT = T
            trip = Trip(route, data.model, plan)
            T = keepT
            navi.requestWithWaypoints(route, plan.filter { it.s > S.s })
            msg += " ${f1((cand.s - S.s) / 1000)}km 앞 ${ro(cand.name)} 경유지를 바꿀게요."
        }
        // 쉬는 중에 한 말은 기록만 하고, 달리다 말한 경우에만 다음 여행 기준선을 낮춘다. 이번 여정의 기준선은 그대로 둔다.
        if (!S.resting) {
            val learned = Guidance.learnedThreshold(thr, S.dose)
            if (learned < thr) {
                getSharedPreferences("sok", Context.MODE_PRIVATE).edit().putFloat("thr", learned.toFloat()).apply()
                nextThr = learned
                msg += " 다음 여행부터 기준선을 ${ro(f1(learned))} 낮출게요."
            }
        }
        if (carValues.recirculation == true) msg += " 내기순환이 켜져 있어요. 바깥 공기로 바꿔 주세요."
        say(msg)
    }

    // ---------------- 차량 값 ----------------

    private fun onCarValues() {
        val v = carValues
        feedMeter(v)
        // 차속이 0인데 위치는 움직이면(에뮬레이터에서 위치만 넣을 때) 위치로 구한 속도를 같이 보여 준다
        val posNote = if (driving && follow && meterOn && meter.lastPosSpeed * 3.6 > 5 && (v.speedKmh ?: 0.0) < 3) " (위치로 ${Math.round(meter.lastPosSpeed * 3.6)}km/h)" else ""
        val loc = if (v.lat != null && v.lon != null) "%.3f, %.3f".format(v.lat, v.lon) else "-"
        fun yn(b: Boolean?, y: String, n: String) = when (b) { true -> y; false -> n; null -> "-" }
        val tag = if (driving && !follow) "차량 값(시연과 별개)" else "차량 값"
        carChips.text = "$tag   기어 ${v.gear ?: "-"}   차속 ${v.speedKmh?.let { "%.0fkm/h".format(it) } ?: "-"}$posNote   조향각 ${v.steeringDeg?.let { "%.0f°".format(it) } ?: "-"}   2열 우측 ${yn(v.rearRightSeated, "착석", "빈자리")}   내기순환 ${yn(v.recirculation, "켜짐", "꺼짐")}   위치 $loc"
        // 급한 회전(조향각 180° 이상)을 차량에서 직접 받으면 알림음
        if (driving && follow && v.steeringDeg != null && Guidance.isSharpTurn(v.steeringDeg)) {
            voice.chime(v.steeringDeg < 0)
            arrow.text = if (v.steeringDeg < 0) "←" else "→"; arrow.visibility = View.VISIBLE; arrowUntil = System.currentTimeMillis() + 1500
        }
    }

    /** 위치 따라가기 중이면 새 위치를 실측 누적에 넣는다. */
    private fun feedMeter(v: CarValues) {
        if (!driving || !follow) return
        val t = v.fixTimeMs ?: return
        val lat = v.lat ?: return
        val lon = v.lon ?: return
        if (t == lastFixT) return
        lastFixT = t
        if (!meterOn) {
            followLocation()
            meter.start(trip.stateAt(T).dose.let { it * it })   // 경로 중간에서 시작하면 그 지점까지는 모형값
            meterOn = true
        } else {
            val pLat = prevLat; val pLon = prevLon
            meter.addFix(t, lat, lon, v.speedKmh?.div(3.6)) {
                val ser = route.series
                val t0 = ser.timeAtS(route.matchS(pLon, pLat)); val t1 = ser.timeAtS(route.matchS(lon, lat))
                ser.at(ser.dose, t1).let { it * it } - ser.at(ser.dose, t0).let { it * it }
            }
            android.util.Log.i(CarLink.TAG, "LIVE: dose=%.2f ay=%.2f est=%s".format(meter.dose, meter.lastAy, meter.estimated))
        }
        if (meter.fixes == 0) meter.addFix(t, lat, lon, v.speedKmh?.div(3.6)) { 0.0 }
        prevLat = lat; prevLon = lon
    }

    /** 아래 상태줄: 내비 상태(이벤트로 정리한 값)와 그 밖의 최근 알림 한 줄. */
    private fun log(m: String) {
        if (!m.startsWith("내비")) { logLines.addLast(m); while (logLines.size > 1) logLines.removeFirst() }
        showLog()
    }

    private fun showLog() {
        logView.text = "내비: $navStatus" + (logLines.lastOrNull()?.let { "   |   $it" } ?: "")
    }

    // ---------------- UI 조립 ----------------

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0B0E13")) }
        // 상단 줄
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(24), px(10), px(24), px(10)) }
        bar.addView(tv("속편한길", 24, "#EEF2F6", bold = true))
        modeChip = tv("경로 선택", 16, "#3CCFB4").apply { setPadding(px(16), 0, px(16), 0) }
        bar.addView(modeChip)
        carChips = tv("차량 값 연결 대기", 16, "#97A2B3")
        bar.addView(carChips, LinearLayout.LayoutParams(0, -2, 1f))
        clock = tv("", 16, "#97A2B3")
        bar.addView(clock)
        root.addView(bar)

        // 계획 화면
        planScreen = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(24), px(6), px(24), px(6)) }
        planMap = MapView(this)
        planScreen.addView(planMap, LinearLayout.LayoutParams(0, -1, 1.15f).apply { rightMargin = px(18) })
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        destRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        destRow.addView(bigButton("속초 시내", "#202734", "#EEF2F6") { selectDest("sokcho") }, LinearLayout.LayoutParams(0, px(56), 1f).apply { rightMargin = px(8) })
        destRow.addView(bigButton("강릉 시내", "#202734", "#EEF2F6") { selectDest("gangneung") }, LinearLayout.LayoutParams(0, px(56), 1f))
        right.addView(destRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) })
        cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f))
        val thrRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        thrNote = tv("", 15, "#97A2B3")
        thrRow.addView(thrNote, LinearLayout.LayoutParams(0, -2, 1f))
        thrRow.addView(bigButton("기준선 되돌리기", "#202734", "#97A2B3") { resetThr() }.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }, LinearLayout.LayoutParams(px(150), px(40)))
        right.addView(thrRow)
        val go = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        go.addView(bigButton("이 길로 안내 시작 (시연 주행)", "#3CCFB4", "#06231E") { startDrive(false) }, LinearLayout.LayoutParams(0, px(68), 1.4f).apply { rightMargin = px(8) })
        go.addView(bigButton("차량 위치로 시작", "#202734", "#EEF2F6") { startDrive(true) }, LinearLayout.LayoutParams(0, px(68), 1f))
        right.addView(go, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(8) })
        planScreen.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(planScreen, LinearLayout.LayoutParams(-1, 0, 1f))

        // 주행 화면
        driveScreen = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; setPadding(px(24), px(6), px(24), px(6)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gauge = card()
        gaugeTitle = tv("멀미 누적 예보", 17, "#97A2B3"); gauge.addView(gaugeTitle)
        gaugeNum = tv("0.0 / 25.0", 52, "#EEF2F6", bold = true); gauge.addView(gaugeNum)
        gaugeBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        gauge.addView(gaugeBar, LinearLayout.LayoutParams(-1, px(16)).apply { topMargin = px(6) })
        gaugeHint = tv("", 15, "#97A2B3"); gauge.addView(gaugeHint)
        left.addView(gauge, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) })
        val c1 = card(); c1.addView(tv("굽은 길", 15, "#97A2B3")); nextCurve = tv("", 26, "#EEF2F6", bold = true); c1.addView(nextCurve); nextCurveSub = tv("", 16, "#97A2B3"); c1.addView(nextCurveSub)
        left.addView(c1, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) })
        val c2 = card(); c2.addView(tv("다음 쉼터", 15, "#97A2B3")); nextRest = tv("", 26, "#EEF2F6", bold = true); c2.addView(nextRest); nextRestSub = tv("", 16, "#97A2B3"); c2.addView(nextRestSub)
        left.addView(c2, LinearLayout.LayoutParams(-1, -2))
        driveScreen.addView(left, LinearLayout.LayoutParams(0, -1, 1f).apply { rightMargin = px(18) })

        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val mapBox = FrameLayout(this)
        driveMap = MapView(this)
        mapBox.addView(driveMap, FrameLayout.LayoutParams(-1, -1))
        arrow = tv("→", 120, "#F2B84B", bold = true).apply { visibility = View.INVISIBLE; gravity = Gravity.CENTER; setShadowLayer(12f, 0f, 0f, Color.BLACK) }
        mapBox.addView(arrow, FrameLayout.LayoutParams(-1, -1))
        restBox = tv("", 24, "#EEF2F6", bold = true).apply { gravity = Gravity.CENTER; visibility = View.GONE; setBackgroundColor(Color.parseColor("#E6112119")); setPadding(px(20), px(20), px(20), px(20)) }
        mapBox.addView(restBox, FrameLayout.LayoutParams(-1, -1))
        mid.addView(mapBox, LinearLayout.LayoutParams(-1, 0, 1f))
        voiceBox = tv("안내 대기 중", 20, "#EEF2F6").apply { setPadding(px(16), px(10), px(16), px(10)); background = rounded("#181D26", 14, "#2B3442"); minLines = 2 }
        mid.addView(voiceBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        seek = android.widget.SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser || !driving || follow) return
                    T = p / 1000.0 * trip.total
                    prevTd = trip.stateAt(T).td      // 건너뛴 구간의 안내는 다시 말하지 않는다
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        mid.addView(seek, LinearLayout.LayoutParams(-1, px(36)).apply { topMargin = px(4) })
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(bigButton("속 안 좋아", "#F2B84B", "#2B1B00") { childReport("속 안 좋아") }, LinearLayout.LayoutParams(0, px(72), 1.3f).apply { rightMargin = px(8) })
        btns.addView(bigButton("말로 알리기", "#202734", "#EEF2F6") { voice.listen() }, LinearLayout.LayoutParams(0, px(72), 1f).apply { rightMargin = px(8) })
        playBtn = bigButton("일시정지", "#202734", "#EEF2F6") { playing = !playing; playBtn.text = if (playing) "일시정지" else "계속" }
        btns.addView(playBtn, LinearLayout.LayoutParams(0, px(72), 0.9f).apply { rightMargin = px(8) })
        speedBtn = bigButton("x30", "#202734", "#EEF2F6") { speed = when (speed) { 30 -> 60; 60 -> 120; 120 -> 10; else -> 30 }; speedBtn.text = "x$speed" }
        btns.addView(speedBtn, LinearLayout.LayoutParams(0, px(72), 0.6f).apply { rightMargin = px(8) })
        followBtn = bigButton("위치 따라가기", "#202734", "#EEF2F6") { follow = !follow; meterOn = false; playing = !follow; followBtn.text = if (follow) "위치 따라가기 중" else "위치 따라가기"; playBtn.visibility = if (follow) View.GONE else View.VISIBLE; speedBtn.visibility = playBtn.visibility
            modeChip.text = if (follow) "차량 위치로 주행" else "시연 주행"; onCarValues() }
        btns.addView(followBtn, LinearLayout.LayoutParams(0, px(72), 1f).apply { rightMargin = px(8) })
        btns.addView(bigButton("끝내기", "#40201F", "#F2716D") { stopDrive() }, LinearLayout.LayoutParams(0, px(72), 0.7f))
        mid.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(10) })
        driveScreen.addView(mid, LinearLayout.LayoutParams(0, -1, 2.1f))
        root.addView(driveScreen, LinearLayout.LayoutParams(-1, 0, 1f))

        logView = tv("", 13, "#5D6878").apply { setPadding(px(24), px(4), px(24), px(8)); maxLines = 2 }
        root.addView(logView)
        setContentView(root)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(px(20), px(14), px(20), px(14)); background = rounded("#181D26", 18, "#181D26")
    }

    private fun tv(s: String, sp: Int, color: String, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat()); setTextColor(Color.parseColor(color))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun bigButton(label: String, bg: String, fg: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(Color.parseColor(fg)); typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false; background = rounded(bg, 16, bg); setOnClickListener { onClick() }
    }

    private fun rounded(fill: String, radiusDp: Int, stroke: String) = GradientDrawable().apply {
        setColor(Color.parseColor(fill)); cornerRadius = radiusDp * dp; setStroke(px(2), Color.parseColor(stroke))
    }

    private fun hex(c: Int) = "#%06X".format(0xFFFFFF and c)
    private fun f1(v: Double) = "%.1f".format(v)
    /** 숫자·이름 뒤 '으로/로'. 숫자는 끝자리 0·3·6(영·삼·육)만, 한글은 받침이 있고 ㄹ이 아닐 때 '으로'. */
    private fun ro(n: String): String {
        val c = n.lastOrNull { it.isLetterOrDigit() } ?: return n + "로"
        val ro = when {
            c in "036" -> "으로"
            c.isDigit() -> "로"
            c in '가'..'힣' -> { val jong = (c - '가') % 28; if (jong == 0 || jong == 8) "로" else "으로" }
            else -> "로"
        }
        return n + ro
    }
    private fun km(m: Double) = "%.1fkm".format(m / 1000)
    private fun fmtMin(m: Double): String { val x = Math.round(m).toInt(); return if (x >= 60) "${x / 60}시간 ${x % 60}분" else "${x}분" }

    companion object {
        const val COLOR_OK = 0xFF3CCFB4.toInt()
        const val COLOR_WARN = 0xFFF2B84B.toInt()
        const val COLOR_BAD = 0xFFF2716D.toInt()
    }
}
