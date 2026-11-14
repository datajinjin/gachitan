package kr.gachitan.ui.preview

import kr.gachitan.core.EnergyKind
import kr.gachitan.core.Gear
import kr.gachitan.core.PricingConfig
import kr.gachitan.core.Rider
import kr.gachitan.core.SeatPosition
import kr.gachitan.core.SettlementCalculator
import kr.gachitan.core.TripLog
import kr.gachitan.core.TripRecorder
import kr.gachitan.core.VehicleEvent

/**
 * 미리보기·화면 검증 전용 예시 운행. 실제 운행 기록·저장소와 섞이지 않습니다(앱 실행 중에는 어디서도 부르지 않음).
 *
 * 디자인 시안의 계산 사례를 실제 정산 엔진으로 재현합니다. 숫자를 손으로 적어 넣지 않습니다.
 * - km당 60원(효율 5km/kWh × 300원/kWh), 운전자 포함
 * - 처음 30km: 나·준호·민지 3명 → 각 600원
 * - 다음 50km: 나·준호 2명 → 각 1,500원
 * - 합계 80km, 4,800원 = 나 2,100 / 준호 2,100 / 민지 600
 */
object SampleTrip {
    /** 2026-11-11 14:10 KST. */
    const val START_MILLIS = 1_794_373_800_000L

    val pricing = PricingConfig(
        energyKind = EnergyKind.ELECTRIC, unitPriceWon = 300.0, capacityUnits = 70.0,
        fallbackKmPerUnit = 5.0, driverPaysShare = true, roundingUnitWon = 10
    )

    fun log(id: String = "sample"): TripLog {
        var t = START_MILLIS
        fun tick(minutes: Long) = (t + minutes * 60_000).also { t = it }
        val rec = TripRecorder(Rider("driver", "나")).apply {
            assign(SeatPosition.FRONT_PASSENGER, Rider("junho", "준호"))
            assign(SeatPosition.REAR_LEFT, Rider("minji", "민지"))
            onEvent(VehicleEvent.OdometerChanged(12_000.0, t))
            onEvent(VehicleEvent.SeatOccupancyChanged(SeatPosition.FRONT_PASSENGER, true, t))
            onEvent(VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, true, t))
            onEvent(VehicleEvent.GearChanged(Gear.DRIVE, t))
            onEvent(VehicleEvent.OdometerChanged(12_030.0, tick(30)))
            onEvent(VehicleEvent.GearChanged(Gear.PARK, tick(2)))
            onEvent(VehicleEvent.SeatOccupancyChanged(SeatPosition.REAR_LEFT, false, tick(1)))
            onEvent(VehicleEvent.GearChanged(Gear.DRIVE, tick(1)))
            onEvent(VehicleEvent.OdometerChanged(12_080.0, tick(44)))
            onEvent(VehicleEvent.GearChanged(Gear.PARK, tick(2)))
        }
        return TripLog.from(SettlementCalculator.settle(rec.finish(), pricing), id, bundleTitle = null)
    }
}
