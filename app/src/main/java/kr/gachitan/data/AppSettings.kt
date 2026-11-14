package kr.gachitan.data

import kr.gachitan.core.EnergyKind
import kr.gachitan.core.PricingConfig
import kr.gachitan.vehicle.OccupancyMapping

/**
 * 앱 설정. 단가 기본값은 예시이며 사용자가 실제 충전·주유 단가로 바꿔 씁니다.
 */
data class AppSettings(
    val driverName: String = "나",
    val energyKind: EnergyKind = EnergyKind.ELECTRIC,
    val unitPriceWon: Double = 350.0,        // 원/kWh (예시값)
    val capacityUnits: Double? = 70.0,       // kWh (예시값)
    val fallbackKmPerUnit: Double = 5.0,     // km/kWh (예시값)
    val driverPaysShare: Boolean = true,
    val roundingUnitWon: Long = 10,
    val tossId: String = "",
    val account: String = "",
    /** 여행일지 뷰어 주소(GitHub Pages 등). 비우면 QR에 텍스트 요약을 담습니다. */
    val viewerBaseUrl: String = "",
    val occupancyMapping: OccupancyMapping = OccupancyMapping.AAOS,
    /** 데모 모드: 실제 차량 신호 대신 화면 아래 시뮬레이터로 입력합니다. 기록도 따로 저장됩니다. */
    val demoMode: Boolean = false,
    val recentNames: List<String> = emptyList(),
    /** 화면 밝기 테마. 기본은 차량의 주간/야간 설정을 따릅니다. */
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** 차량 유지비(원/km). 0이면 끔. */
    val wearWonPerKm: Double = 0.0
) {
    fun pricing() = PricingConfig(
        energyKind = energyKind,
        unitPriceWon = unitPriceWon,
        capacityUnits = capacityUnits,
        fallbackKmPerUnit = fallbackKmPerUnit,
        driverPaysShare = driverPaysShare,
        roundingUnitWon = roundingUnitWon,
        wearWonPerKm = wearWonPerKm
    )

    /** 에너지 종류를 바꿀 때 단위가 다른 값들을 그 종류의 예시값으로 함께 바꿉니다. */
    fun withEnergyKind(kind: EnergyKind): AppSettings = when (kind) {
        EnergyKind.ELECTRIC -> copy(energyKind = kind, unitPriceWon = 350.0, capacityUnits = 70.0, fallbackKmPerUnit = 5.0)
        EnergyKind.FUEL -> copy(energyKind = kind, unitPriceWon = 1700.0, capacityUnits = 55.0, fallbackKmPerUnit = 12.0)
    }
}


/** 화면 테마. AUTO는 차량(시스템)의 주간/야간 모드를 따릅니다. */
enum class ThemeMode(val label: String) { AUTO("차량 설정 따라"), LIGHT("밝게"), DARK("어둡게") }

/** 세션이 설정을 읽고 쓰는 입구(테스트에서는 메모리 구현을 씁니다). */
interface SettingsSource {
    val current: AppSettings
    fun update(transform: (AppSettings) -> AppSettings)
    fun rememberNames(names: Collection<String>)
}

class InMemorySettings(initial: AppSettings = AppSettings()) : SettingsSource {
    override var current: AppSettings = initial
        private set
    override fun update(transform: (AppSettings) -> AppSettings) { current = transform(current) }
    override fun rememberNames(names: Collection<String>) = update { s ->
        s.copy(recentNames = (names.filter { it.isNotBlank() } + s.recentNames).distinct().take(8))
    }
}
