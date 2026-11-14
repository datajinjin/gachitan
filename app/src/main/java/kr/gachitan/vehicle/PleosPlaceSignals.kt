package kr.gachitan.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.ChargerOperator
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kr.gachitan.core.Place
import kr.gachitan.core.PlaceSource
import kr.gachitan.core.VehicleEvent
import java.util.Locale
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible

/**
 * 여행일지용 장소 수집.
 *
 * 1) NaviHelper: 목적지·경유지 도착 이벤트 → 장소 이름·주소·좌표 (출처 NAVI)
 * 2) FusedLocation: 기어가 바뀔 때(출발·정차) 마지막 위치 1회 조회 → 좌표 (출처 LOCATION)
 * 3) Geocoder: 좌표 → 주소 이름. 기기에 주소 변환 서비스가 있을 때만 동작합니다.
 *
 * 세 가지 모두 없어도 앱은 동작합니다. 장소 이름은 사람이 정차 화면에서 붙일 수 있습니다.
 * 호출 방식은 Pleos 공식 NaviHelper·FusedLocation 샘플 앱과 같습니다.
 */
class PleosPlaceSignals(
    context: Context,
    private val scope: CoroutineScope,
    private val onStatus: (SignalStatus) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val appContext = context.applicationContext
    private val _events = MutableSharedFlow<VehicleEvent>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: Flow<VehicleEvent> = _events.asSharedFlow()

    private var navi: NaviHelper? = null
    private var location: FusedLocation? = null

    // ------------------------------------------------------------------ 위치

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            val lat = location.latitude
            val lon = location.longitude
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
            onStatus(SignalStatus(LOCATION, true, "%.5f, %.5f".format(Locale.ROOT, lat, lon)))
            emit(Place(latitude = lat, longitude = lon, source = PlaceSource.LOCATION))
            geocode(lat, lon)
        }

        override fun onError(exception: Exception) {
            onStatus(SignalStatus(LOCATION, false, error = exception.message ?: exception.javaClass.simpleName))
        }
    }

    // ------------------------------------------------------------------ 내비

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            arrived("목적지 도착", destinationArrivedInfo)
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) =
            arrived("경유지 도착", waypointArrivedInfo)
        override fun onError(errorCode: NaviErrorCode) {
            onStatus(SignalStatus(NAVI, false, error = errorCode.toString()))
        }

        // 아래 이벤트는 이 앱에서 쓰지 않습니다(인터페이스 구현용).
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = Unit
        override fun onRouteEnded() = Unit
        override fun onRoutingCompleted() = Unit
        override fun onReRouteEnded() = Unit
        override fun onRouteOptionChanged(routeOption: RouteOption) = Unit
        override fun onRouteCancelled() = Unit
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = Unit
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = Unit
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = Unit
        override fun onDestinationInfo(destinationInfo: DestinationInfo) = Unit
        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = Unit
        override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) = Unit
        override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) = Unit
        override fun onCurrentRoadSpeedLimit(speedLimit: Int) = Unit
        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) = Unit
        override fun onDrivingInfo(drivingInfo: DrivingInfo) = Unit
        override fun onBookMarkChanged() = Unit
        override fun onDestinationChanged() = Unit
        override fun onNavigationStatus(status: NavigationStatus) = Unit
        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) = Unit
        override fun onChargerOperatorInfo(chargerOperatorList: List<ChargerOperator>) = Unit
        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) = Unit
        override fun onTripInfo(tripInfo: TripInfo) = Unit
    }

    private fun arrived(label: String, info: Any) {
        val place = PlaceExtractor.extract(info, PlaceSource.NAVI)
        onStatus(SignalStatus(NAVI, true, "$label: ${place?.displayName("?") ?: info.toString().take(40)}"))
        place?.let(::emit)
    }

    // ------------------------------------------------------------------ 수명

    fun start() {
        try {
            val helper = NaviHelper(appContext)
            helper.initialize()
            helper.addListener(naviListener)
            navi = helper
            onStatus(SignalStatus(NAVI, true, "initialized"))
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper unavailable", e)
            onStatus(SignalStatus(NAVI, false, error = "연동 내비 앱 없음 또는 초기화 실패: ${e.message ?: e.javaClass.simpleName}"))
        }
        try {
            val fused = FusedLocation(appContext)
            if (!fused.isSupported()) {
                onStatus(SignalStatus(LOCATION, false, error = "이 차량은 FusedLocation 미지원"))
            } else {
                fused.initialize()
                location = fused
                onStatus(SignalStatus(LOCATION, true, "initialized"))
                requestFix()
            }
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation unavailable", e)
            onStatus(SignalStatus(LOCATION, false, error = e.message ?: e.javaClass.simpleName))
        }
        onStatus(SignalStatus(GEOCODER, Geocoder.isPresent(), if (Geocoder.isPresent()) "사용 가능" else null,
            if (Geocoder.isPresent()) null else "주소 변환 서비스 없음 (이름은 직접 붙이기)"))
    }

    /** 출발·정차 순간에 한 번씩만 위치를 묻습니다(상시 구독 없음 → 주행 중 CPU 절약). */
    fun requestFix() {
        runCatching { location?.getLastKnownLocation(locationListener) }
            .onFailure { onStatus(SignalStatus(LOCATION, false, error = it.message)) }
    }

    fun stop() {
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        runCatching { location?.release() }
        navi = null; location = null
    }

    private fun emit(place: Place) {
        _events.tryEmit(VehicleEvent.PlaceObserved(place, clock()))
    }

    @Suppress("DEPRECATION")
    private fun geocode(lat: Double, lon: Double) {
        if (!Geocoder.isPresent()) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val a = Geocoder(appContext, Locale.KOREA).getFromLocation(lat, lon, 1)?.firstOrNull() ?: return@runCatching
                // 짧은 이름(동·건물명) 우선, 없으면 전체 주소.
                val name = listOfNotNull(a.featureName?.takeIf { f -> f.any(Char::isLetter) }, a.thoroughfare, a.subLocality, a.locality)
                    .firstOrNull()
                emit(Place(name = name ?: a.getAddressLine(0), address = a.getAddressLine(0),
                    latitude = lat, longitude = lon, source = PlaceSource.LOCATION))
            }.onFailure { Log.w(TAG, "geocode failed", it) }
        }
    }

    private companion object {
        const val TAG = "PleosPlaceSignals"
        const val NAVI = "내비 연동 (NaviHelper)"
        const val LOCATION = "위치 (FusedLocation)"
        const val GEOCODER = "주소 변환 (Geocoder)"
    }
}

/**
 * 내비 데이터 객체에서 장소 정보를 꺼냅니다. 필드 이름이 버전마다 조금 달라도 동작하도록
 * 속성 이름을 넓게 찾고, 마지막으로 data class의 toString(예: "poiName=..., latitude=...")을 읽습니다.
 */
internal object PlaceExtractor {
    private val nameKeys = listOf("poiName", "name", "destinationName", "placeName", "title")
    private val addressKeys = listOf("address", "roadAddress", "addr")
    private val latKeys = listOf("latitude", "lat")
    private val lonKeys = listOf("longitude", "lon", "lng")

    fun extract(obj: Any, source: PlaceSource): Place? {
        val props = properties(obj)
        val nested = props.values.filterNotNull()
            .filter { it.javaClass.name.startsWith("ai.pleos.") }
            .map(::properties)
        val all = listOf(props) + nested
        fun pick(keys: List<String>) = keys.firstNotNullOfOrNull { k -> all.firstNotNullOfOrNull { it[k] } }

        val text = obj.toString()
        val name = (pick(nameKeys) as? String) ?: regex(text, nameKeys)
        val address = (pick(addressKeys) as? String) ?: regex(text, addressKeys)
        val lat = (pick(latKeys) as? Number)?.toDouble() ?: regex(text, latKeys)?.toDoubleOrNull()
        val lon = (pick(lonKeys) as? Number)?.toDouble() ?: regex(text, lonKeys)?.toDoubleOrNull()
        if (name.isNullOrBlank() && address.isNullOrBlank() && (lat == null || lon == null)) return null
        return Place(name?.takeIf { it.isNotBlank() }, address?.takeIf { it.isNotBlank() }, lat, lon, source)
    }

    private fun properties(obj: Any): Map<String, Any?> = runCatching {
        obj::class.memberProperties.associate { p ->
            p.isAccessible = true
            p.name to runCatching { p.getter.call(obj) }.getOrNull()
        }
    }.getOrDefault(emptyMap())

    private fun regex(text: String, keys: List<String>): String? = keys.firstNotNullOfOrNull { k ->
        Regex("\\b$k=([^,)]+)").find(text)?.groupValues?.get(1)?.trim()?.takeIf { it != "null" && it.isNotEmpty() }
    }
}
