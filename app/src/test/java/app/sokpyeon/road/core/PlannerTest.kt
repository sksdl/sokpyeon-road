package app.sokpyeon.road.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlannerTest {
    private val data = RouteData.parse(File("src/main/assets/routes.json").readText())

    @Test fun routesLoad() {
        assertEquals(5, data.routes.size)
        assertEquals(25.0, data.childThr, 0.0)
    }

    @Test fun hangyeryeongPlanMatchesAnalysis() {
        val b = data.byId("B")
        val plan = Planner.planBreaks(b, data.model, 25.0)
        // 분석 결과(03_prototype_data): 삼팔선휴게소 → 옥녀탕(졸음쉼터) → 한계령 구간에서 쉴 곳 없음
        assertEquals(listOf("삼팔선휴게소", "옥녀탕휴게소"), plan.filter { it.type == "rest" }.map { it.name })
        assertTrue(plan.any { it.type == "no_stop" })
    }

    @Test fun mitiryeongPlan() {
        val c = data.byId("C")
        val rests = Planner.planBreaks(c, data.model, 25.0).filter { it.type == "rest" }
        assertEquals(listOf("삼팔선휴게소", "설악백담휴게소"), rests.map { it.name })
    }

    @Test fun seoulYangyangNeedsNoRest() {
        val a = data.byId("A")
        assertTrue(Planner.planBreaks(a, data.model, 25.0).isEmpty())
    }

    @Test fun restLowersDose() {
        val c = data.byId("C")
        val trip = Trip(c, data.model, Planner.planBreaks(c, data.model, 25.0))
        val end = trip.stateAt(trip.total)
        assertEquals(c.effEnd, end.dose, 0.6)          // 분석값 18.0
        assertTrue(end.dose < c.msdv)                  // 쉬지 않은 27.0보다 낮다
        val r = Planner.planBreaks(c, data.model, 25.0).first()
        val mid = trip.stateAt(trip.segs.first { it.rest }.a + 60)
        assertTrue(mid.resting)
        assertEquals(r.name, mid.stop!!.name)
        assertTrue(mid.dose < mid.before)
    }

    @Test fun cuesAndPhrases() {
        val b = data.byId("B")
        val first = Guidance.cuesBetween(b, 0.0, 3600.0)
        assertTrue(first.any { it.kind == "voice" && it.text.startsWith("800m 앞부터") })
        assertTrue(Guidance.isSickPhrase("엄마 속 안 좋아"))
        assertFalse(Guidance.isSickPhrase("배고파"))
        assertEquals(19.0, Guidance.learnedThreshold(25.0, 20.0), 0.01)
        assertEquals(25.0, Guidance.learnedThreshold(25.0, 26.0), 0.01)
    }

    @Test fun matchRoutePoint() {
        val a = data.byId("A")
        val (lon, lat) = a.pointAt(50000.0)
        val s = a.matchS(lon, lat)
        assertEquals(50000.0, s, 200.0)
    }
}
