package kr.gachitan.core

/**
 * 좌석 위치. [sdkCode]는 Pleos Vehicle SDK `SeatValue` 상수 이름과 같습니다.
 * 한국 차량(좌핸들) 기준으로 운전석은 ROW_1_LEFT입니다.
 */
enum class SeatPosition(val label: String, val sdkCode: String) {
    DRIVER("운전석", "ROW_1_LEFT"),
    FRONT_PASSENGER("조수석", "ROW_1_RIGHT"),
    REAR_LEFT("뒷좌석 왼쪽", "ROW_2_LEFT"),
    REAR_CENTER("뒷좌석 가운데", "ROW_2_CENTER"),
    REAR_RIGHT("뒷좌석 오른쪽", "ROW_2_RIGHT");

    val isDriver: Boolean get() = this == DRIVER

    companion object {
        val passengerSeats: List<SeatPosition> = entries.filterNot { it.isDriver }
        fun fromSdkCode(code: String): SeatPosition? = entries.firstOrNull { it.sdkCode == code }
    }
}

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class EnergyKind { ELECTRIC, FUEL }

/** 사람. 이름은 기기 안에서만 쓰이고 서버로 보내지 않습니다. */
data class Rider(val id: String, val name: String)

/**
 * 차량에서 들어오는 사실(fact)들. 앱의 모든 계산은 이 이벤트만으로 재현됩니다.
 * 같은 이벤트 목록을 다시 넣으면 언제나 같은 정산 결과가 나옵니다(이벤트 소싱).
 */
sealed interface VehicleEvent {
    val atMillis: Long

    data class GearChanged(val gear: Gear, override val atMillis: Long) : VehicleEvent
    data class SeatOccupancyChanged(
        val seat: SeatPosition,
        val occupied: Boolean,
        override val atMillis: Long
    ) : VehicleEvent
    data class OdometerChanged(val km: Double, override val atMillis: Long) : VehicleEvent
    data class EnergyLevelChanged(
        val kind: EnergyKind,
        val percent: Double,
        override val atMillis: Long
    ) : VehicleEvent
    /** 차량이 보고한 효율. 전기차는 km/kWh, 내연기관은 km/L. */
    data class EfficiencyReported(
        val kind: EnergyKind,
        val kmPerUnit: Double,
        override val atMillis: Long
    ) : VehicleEvent
    /** 내비 도착 이벤트나 위치 SDK에서 받은 장소. */
    data class PlaceObserved(val place: Place, override val atMillis: Long) : VehicleEvent
}

/** 장소 정보의 출처. 숫자가 클수록 믿을 만한 출처입니다(사람이 직접 붙인 이름이 최우선). */
enum class PlaceSource(val priority: Int) { LOCATION(1), NAVI(2), MANUAL(3) }

/**
 * 장소. 좌표만 있을 수도, 이름만 있을 수도 있습니다.
 * 내비 목적지면 이름·주소가 오고, 위치 SDK면 좌표가 오고, 사람은 이름을 붙입니다.
 */
data class Place(
    val name: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val source: PlaceSource
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null

    fun displayName(fallback: String): String = when {
        !name.isNullOrBlank() -> name
        !address.isNullOrBlank() -> address
        latitude != null && longitude != null -> "%.4f, %.4f 부근".format(java.util.Locale.ROOT, latitude, longitude)
        else -> fallback
    }

    /** 두 장소가 같은 곳(반경 [meters] 안)인지. 좌표가 없으면 false. */
    fun isNear(other: Place, meters: Double = 200.0): Boolean {
        val a1 = latitude ?: return false; val o1 = longitude ?: return false
        val a2 = other.latitude ?: return false; val o2 = other.longitude ?: return false
        val r = 6_371_000.0
        val dLat = Math.toRadians(a2 - a1); val dLon = Math.toRadians(o2 - o1)
        val h = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h)) <= meters
    }

    /** 다른 장소 정보로 빈칸을 채우거나, 더 믿을 만한 출처면 덮어씁니다. */
    fun mergedWith(newer: Place): Place = when {
        newer.source.priority > source.priority -> newer.copy(
            latitude = newer.latitude ?: latitude, longitude = newer.longitude ?: longitude,
            address = newer.address ?: address
        )
        newer.source.priority == source.priority -> copy(
            name = newer.name ?: name, address = newer.address ?: address,
            latitude = newer.latitude ?: latitude, longitude = newer.longitude ?: longitude
        )
        else -> copy(name = name ?: newer.name, address = address ?: newer.address)
    }
}

/** 운행 중 기어 P로 멈춘 지점. 친구를 내려준 곳, 휴게소, 목적지가 여기 기록됩니다. */
data class Stop(val km: Double, val atMillis: Long, val place: Place?)

/** 탑승 구성이 바뀌지 않은 한 구간. */
data class Segment(
    val startKm: Double,
    val endKm: Double,
    val occupantIds: Set<String>
) {
    val distanceKm: Double get() = (endKm - startKm).coerceAtLeast(0.0)
}

/**
 * 통행료·주차비처럼 사람이 직접 입력하는 확정 비용.
 * [payerIds]는 이 비용을 나눌 사람(기록 안에서의 rider id). 비어 있으면 [atKm] 구간 탑승자끼리 나눕니다.
 */
data class ExtraCost(
    val label: String,
    val amountWon: Long,
    val atKm: Double,
    val payerIds: Set<String> = emptySet()
)

/**
 * 한 사람이 한 좌석에 앉아 있던 한 번의 기간.
 * 센서는 '누군가 앉았다'만 알려 줍니다. 누가 앉았는지는 사람이 붙인 이름([riderId])으로만 압니다.
 */
data class SittingRecord(
    val id: String,
    val seat: SeatPosition,
    val riderId: String,
    val startKm: Double,
    val endKm: Double
) {
    val distanceKm: Double get() = (endKm - startKm).coerceAtLeast(0.0)
}

/** 앱이 꺼져 있어 신호를 받지 못한 기간. 이 동안의 승하차는 기록에 없습니다. */
data class RecordGap(val fromMillis: Long, val toMillis: Long, val atKm: Double?)

/** 차량이 보고한 효율 한 번. [atKm] 이후 다음 보고까지의 거리에 적용합니다. */
data class EfficiencySample(val atKm: Double, val kmPerUnit: Double)
