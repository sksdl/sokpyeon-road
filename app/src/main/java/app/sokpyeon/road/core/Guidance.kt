package app.sokpyeon.road.core

import kotlin.math.abs

/** 음성·알림음 한 건. kind: "voice"(말로 안내), "chime"(굽이 직전 좌우 알림음). */
data class Notice(val kind: String, val side: Char?, val text: String)

object Guidance {
    /** 직전 주행 시각 prevTd 뒤부터 td까지 지나간 안내를 순서대로 돌려준다(굽은 길 800m 전 예고, 급한 굽이 알림). */
    fun cuesBetween(route: Route, prevTd: Double, td: Double): List<Notice> =
        route.cues.filter { it.t > prevTd && it.t <= td }.map { c ->
            if (c.kind == "section") {
                Notice("voice", null, c.text.replaceFirst("0.8km 앞부터", "800m 앞부터") + ". 뒤에 앉은 사람은 창밖 먼 곳을 봐 주세요.")
            } else {
                val side = if (c.text.startsWith("왼쪽")) 'L' else 'R'
                Notice("chime", side, if (side == 'L') "왼쪽으로 크게 돕니다" else "오른쪽으로 크게 돕니다")
            }
        }

    /** 아이가 "속 안 좋아"라고 했을 때 앞당길 쉴 곳. 다음 예정 휴게소보다 3km 넘게 앞에 있고 0.5~15km 앞인 곳 중 가장 가까운 곳. */
    fun nearerStop(route: Route, s: Double, plan: List<Break>): Stop? {
        val next = plan.filter { it.type == "rest" && it.s > s }.minByOrNull { it.s }
        return route.stops.filter { it.s > s + 500 && it.s < s + 15000 && (next == null || next.s - it.s > 3000) }.minByOrNull { it.s }
    }

    /** "속 안 좋아" 같은 말을 알아듣는다. */
    fun isSickPhrase(text: String): Boolean {
        val t = text.replace(" ", "")
        return listOf("속안좋", "속이안좋", "멀미", "울렁", "토할", "토나", "메스꺼", "어지러").any { t.contains(it) }
    }

    /** 기준선 학습: 말한 순간의 누적에서 한 번 쉬어 둔 여유(약 10%)를 뺀 값을 새 기준선으로 삼되 12 아래로는 내리지 않는다. */
    fun learnedThreshold(current: Double, doseAtReport: Double): Double =
        if (doseAtReport < current) maxOf(12.0, Math.round(doseAtReport * 0.95 * 10) / 10.0) else current

    fun isSharpTurn(angleDeg: Float): Boolean = abs(angleDeg) >= 180f
}
