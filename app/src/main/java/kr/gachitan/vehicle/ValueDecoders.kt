package kr.gachitan.vehicle

/**
 * SDK가 돌려주는 원시 값을 앱의 의미로 바꿉니다.
 *
 * 좌석 탑승 값의 형식(정수·불리언·열거형)은 상세 문서에서 확인하지 못했습니다.
 * Pleos SDK는 AAOS Alignment 정책을 따르므로 기본값은 AAOS의 VehicleSeatOccupancyState
 * (0=알 수 없음, 1=비어 있음, 2=탑승)입니다. 다르면 설정 > 진단에서 원시 값을 보고 바꿀 수 있습니다.
 */
enum class OccupancyMapping(val label: String) {
    AAOS("AAOS 규격 (1=비어 있음, 2=탑승)"),
    BINARY("단순 규격 (0=비어 있음, 1=탑승)");
}

object ValueDecoders {

    fun occupied(raw: Any?, mapping: OccupancyMapping): Boolean? = when (raw) {
        null -> null
        is Boolean -> raw
        is Number -> when (mapping) {
            OccupancyMapping.AAOS -> when (raw.toInt()) { 2 -> true; 1 -> false; else -> null }
            OccupancyMapping.BINARY -> when (raw.toInt()) { 1 -> true; 0 -> false; else -> null }
        }
        is Enum<*> -> fromName(raw.name)
        else -> fromName(raw.toString())
    }

    private fun fromName(name: String): Boolean? {
        val n = name.uppercase()
        return when {
            "UNKNOWN" in n -> null
            "UNOCCUPIED" in n || "VACANT" in n || "EMPTY" in n || "NOT_OCCUPIED" in n -> false
            "OCCUPIED" in n -> true
            else -> null
        }
    }

    /** 숫자처럼 보이는 값을 Double로. 리스트나 래퍼 객체로 와도 첫 숫자를 찾습니다. */
    fun number(raw: Any?): Double? = when (raw) {
        null -> null
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is Iterable<*> -> raw.firstNotNullOfOrNull(::number)
        is Map<*, *> -> raw.values.firstNotNullOfOrNull(::number)
        else -> Regex("-?\\d+(\\.\\d+)?").find(raw.toString())?.value?.toDoubleOrNull()
    }

    fun describe(raw: Any?): String = when (raw) {
        null -> "null"
        is Map<*, *> -> raw.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
        else -> raw.toString()
    }.take(80)
}

/**
 * 진단 화면에 보이는 글. SDK 오류 원문(예외 이름, 속성 ID)을 사람이 읽는 말로 바꿉니다.
 * 원문은 로그(Logcat)에 그대로 남기고, 화면에는 '무엇을 대신 쓰는지'까지 함께 보여 줍니다.
 */
object SignalText {

    /** SDK 메서드 이름 → 진단 화면의 신호 이름. 성공·실패가 같은 줄에 모이도록 같은 이름을 씁니다. */
    fun labelFor(method: String): String = when {
        method.contains("SeatOccupancy") -> "좌석 탑승"
        method.contains("DistanceDriven") -> "계기판 누적거리 (Odometer)"
        method.contains("EvBatteryStateOfCharge") -> "배터리 잔량 (EvBattery SOC)"
        method.contains("EvEfficiency") -> "전비 (EvBattery)"
        method.contains("FuelPercent") -> "연료 잔량 (Fuel)"
        method.contains("FuelEconomy") -> "연비 (Fuel)"
        else -> method
    }

    /** 오류 원문 → 화면 문구. */
    fun friendlyError(name: String, error: String): String {
        val e = error.lowercase()
        val fallback = when {
            "효율" in name || "전비" in name || "연비" in name || "Efficiency" in name || "Economy" in name ->
                " · 설정한 기본 효율로 계산"
            "잔량" in name || "Percent" in name || "SOC" in name -> " · 잔량 대신 효율로 계산"
            "위치" in name || "Location" in name -> " · 장소 이름은 직접 붙이기"
            "주소" in name || "Geocoder" in name -> " · 장소 이름은 직접 붙이기"
            "좌석" in name || "Seat" in name -> " · 탑승자는 직접 확인"
            else -> ""
        }
        val reason = when {
            "not_supported" in e || "not supported" in e || "carpropertyconfig" in e || "nosuchmethod" in e ||
                "unsupported" in e || "이 차량에 없는" in e -> "이 차량에서 제공하지 않음"
            "permission" in e || "security" in e || "권한" in e -> "권한 없음"
            "주소 변환 서비스 없음" in e -> "주소 변환 서비스 없음"
            "timeout" in e -> "응답 없음"
            else -> "연결 실패"
        }
        return reason + fallback
    }

    /** 원시 값 → 화면 문구. 좌석은 탑승/비어 있음, 기어는 P·R·N·D. */
    fun friendlyValue(name: String, raw: String?): String? {
        raw ?: return null
        if ("좌석" in name) return when (raw.trim().lowercase()) {
            "true", "occupied" -> "탑승"
            "false", "vacant", "unoccupied" -> "비어 있음"
            else -> raw
        }
        return raw
    }
}
