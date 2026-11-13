package app.sokpyeon.road.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/** 차량에서 읽는 값. 값이 아직 안 왔으면 null. */
data class CarValues(
    val gear: String? = null,
    val steeringDeg: Float? = null,
    val odometerKm: Float? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val heading: Float? = null,
    val speedKmh: Double? = null,
    val rearRightSeated: Boolean? = null,
    val recirculation: Boolean? = null,
)

/**
 * Pleos Connect SDK의 Vehicle(기어·조향각·주행거리)과 FusedLocation(위치)을 읽는다.
 * 콜백은 서비스 스레드에서 오므로 메인 스레드로 넘겨서 onChange를 부른다.
 */
class CarLink(private val context: Context, private val log: (String) -> Unit, private val onChange: (CarValues) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var fused: FusedLocation? = null
    @Volatile private var values = CarValues()
    private var lastFix: LocationData? = null

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = push { it.copy(gear = gearLabel(gear)) }
        override fun onFailed(e: Exception) = fail("기어", e)
    }
    private val steerListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = push { it.copy(steeringDeg = angle) }
        override fun onFailed(e: Exception) = fail("조향각", e)
    }
    private val odoListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(distance: Float?) = push { it.copy(odometerKm = distance) }
        override fun onFailed(e: Exception) = fail("주행거리", e)
    }
    private val rearRight = SeatArea(SeatValue.ROW_2_RIGHT)
    private var recircArea: SeatArea? = null
    private val seatListener = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupied: Boolean?) = push { it.copy(rearRightSeated = occupied) }
        override fun onFailed(e: Exception) = fail("2열 우측 착석", e)
    }
    private val recircListener = object : HvacRecirculationStateListener {
        override fun onHvacAutoRecirculationStateUpdated(on: Boolean?) = push { it.copy(recirculation = on) }
        override fun onFailed(e: Exception) = fail("내기순환", e)
    }
    private val locListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            val prev = lastFix
            lastFix = location
            val speed = if (speedPoll == null && prev != null && location.timestampMillis > prev.timestampMillis)
                haversineM(prev.latitude, prev.longitude, location.latitude, location.longitude) /
                    ((location.timestampMillis - prev.timestampMillis) / 1000.0) * 3.6 else null
            push { it.copy(lat = location.latitude, lon = location.longitude, heading = location.heading, speedKmh = speed ?: it.speedKmh) }
        }
        override fun onError(exception: Exception) = fail("위치", exception)
    }

    fun start() {
        startVehicle()
        startSpeed()
    }

    private var speedPoll: Runnable? = null

    /**
     * 차속은 Vehicle SDK에 없어서 AAOS의 CarPropertyManager(PERF_VEHICLE_SPEED)로 읽는다.
     * android.car가 앱 클래스패스에 없을 수 있어 반사로 부르고, 안 되면 위치 변화로 추정한 값을 쓴다.
     */
    private fun startSpeed() {
        try {
            val carCls = Class.forName("android.car.Car")
            val car = carCls.getMethod("createCar", Context::class.java).invoke(null, context) ?: run { log("차속: Car 서비스 없음, 위치로 추정"); return }
            val pm = carCls.getMethod("getCarManager", String::class.java).invoke(car, "property") ?: run { log("차속: property 서비스 없음, 위치로 추정"); return }
            val get = pm.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val r = object : Runnable {
                override fun run() {
                    try {
                        val ms = get.invoke(pm, PERF_VEHICLE_SPEED, 0) as Float
                        push { it.copy(speedKmh = ms * 3.6) }
                    } catch (t: Throwable) { Log.w(TAG, "speed read", t); main.postDelayed(this, 5000); return }
                    main.postDelayed(this, 500)
                }
            }
            speedPoll = r
            main.post(r)
            log("차속: AAOS PERF_VEHICLE_SPEED 읽는 중")
        } catch (t: Throwable) {
            log("차속: AAOS 접근 실패(${t.javaClass.simpleName}), 위치로 추정")
            Log.w(TAG, "speed start", t)
        }
    }

    private fun startVehicle() {
        try {
            val v = Vehicle(context)
            v.initialize()
            vehicle = v
            log("Vehicle SDK 초기화")
            // 현재값 한 번 조회 후 변화 구독
            v.getGear().getCurrentGearSelectionState({ g -> push { it.copy(gear = gearLabel(g)) } }, { e -> fail("기어 조회", e) })
            v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> push { it.copy(steeringDeg = a) } }, { e -> fail("조향각 조회", e) })
            v.getOdometer().getCurrentDistanceDriven({ d -> push { it.copy(odometerKm = d) } }, { e -> fail("주행거리 조회", e) })
            v.getSeat().getCurrentSeatOccupancy(rearRight, { o -> push { it.copy(rearRightSeated = o) } }, { e -> fail("2열 우측 착석 조회", e) })
            v.getSeat().registerSeatOccupancy(rearRight, seatListener)
            // 내기순환은 차량마다 영역이 달라서 지원하는 영역을 먼저 물어본다
            v.getHVAC().checkHvacRecirculationCapabilities({ areas: List<SeatArea> ->
                val a = areas.firstOrNull()
                if (a == null) log("내기순환: 지원 영역 없음") else {
                    recircArea = a
                    v.getHVAC().getHvacRecirculationState(a, { r -> push { it.copy(recirculation = r) } }, { e -> fail("내기순환 조회", e) })
                    v.getHVAC().registerHvacRecirculationState(a, recircListener)
                }
            }, { e -> fail("내기순환 지원 확인", e) })
            v.getGear().registerGearSelection(gearListener)
            v.getSteeringWheel().registerSteeringWheelAngle(steerListener)
            v.getOdometer().registerDistanceDriven(odoListener)
        } catch (t: Throwable) {
            log("Vehicle SDK 시작 실패: ${t.javaClass.simpleName} ${t.message}")
            Log.w(TAG, "vehicle start", t)
        }
    }

    /** FUSED_LOCATION은 dangerous 퍼미션이라 사용자가 허용한 뒤에 시작한다. */
    fun startLocation() {
        if (fused != null) return
        try {
            val f = FusedLocation(context)
            if (f.isSupported()) {
                f.initialize()
                f.getLastKnownLocation(locListener)
                f.registerFusedLocationCallback(locListener)
                fused = f
                log("FusedLocation 위치 수신 시작")
            } else log("FusedLocation: 이 환경은 지원하지 않음")
        } catch (t: Throwable) {
            log("FusedLocation 시작 실패: ${t.javaClass.simpleName} ${t.message}")
            Log.w(TAG, "fused start", t)
        }
    }

    fun stop() {
        speedPoll?.let { main.removeCallbacks(it) }
        try {
            vehicle?.let {
                it.getGear().unregisterGearSelection(gearListener)
                it.getSteeringWheel().unregisterSteeringWheelAngle(steerListener)
                it.getOdometer().unregisterDistanceDriven(odoListener)
                it.getSeat().unregisterSeatOccupancy(rearRight, seatListener)
                recircArea?.let { a -> it.getHVAC().unregisterHvacRecirculationState(a, recircListener) }
                it.release()
            }
            fused?.let { it.unregisterFusedLocationCallback(locListener); it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "stop", t)
        }
        vehicle = null
        fused = null
    }

    private fun push(f: (CarValues) -> CarValues) {
        values = f(values)
        val snap = values
        main.post { onChange(snap) }
    }

    private fun fail(what: String, e: Exception) {
        Log.w(TAG, "$what 실패", e)
        main.post { log("$what 실패: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()) }
    }

    private fun gearLabel(g: VehicleGear?) = when (g) {
        null -> "-"
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        VehicleGear.GEAR_UNKNOWN -> "-"
        else -> g.name.removePrefix("GEAR_")
    }

    companion object {
        const val TAG = "Sokpyeon"
        const val PERF_VEHICLE_SPEED = 291504647
        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
            return 2 * r * Math.asin(Math.sqrt(a))
        }
    }
}
