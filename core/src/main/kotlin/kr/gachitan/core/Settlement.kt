package kr.gachitan.core

import kotlin.math.floor

/** 사용자가 정하는 정산 규칙. 값은 모두 앱 설정에서 바꿀 수 있습니다. */
data class PricingConfig(
    val energyKind: EnergyKind,
    /** 원/kWh(전기) 또는 원/L(연료). */
    val unitPriceWon: Double,
    /** 배터리 용량(kWh) 또는 연료탱크 용량(L). 모르면 null. */
    val capacityUnits: Double?,
    /** 차량 효율 값을 받지 못했을 때 쓰는 기본 효율(km/kWh 또는 km/L). */
    val fallbackKmPerUnit: Double,
    /** 운전자도 n분의 1에 포함할지. */
    val driverPaysShare: Boolean = true,
    /** 동승자 금액을 이 단위로 반올림합니다. 남는 차액은 운전자가 흡수합니다. */
    val roundingUnitWon: Long = 10,
    /**
     * 차량 유지비(원/km, 선택). 타이어·소모품·감가처럼 에너지비 밖에서 운전자가 떠안는 비용입니다.
     * 0이면 쓰지 않습니다. 에너지비와 똑같이 거리·탑승 인원으로 나눕니다.
     */
    val wearWonPerKm: Double = 0.0
) {
    init {
        require(wearWonPerKm >= 0) { "유지비는 0 이상이어야 합니다." }
        require(unitPriceWon >= 0) { "단가는 0 이상이어야 합니다." }
        require(fallbackKmPerUnit > 0) { "기본 효율은 0보다 커야 합니다." }
        require(roundingUnitWon >= 1) { "반올림 단위는 1원 이상이어야 합니다." }
    }
}

/** 에너지 비용을 무엇으로 계산했는지. 화면에 그대로 보여 줍니다. 세 가지 모두 '추정'입니다. */
enum class CostBasis(val description: String) {
    VEHICLE_EFFICIENCY("차량이 보고한 효율"),
    MEASURED_ENERGY_USE("잔량 감소량"),
    USER_EFFICIENCY("설정한 기본 효율")
}

/**
 * 에너지 비용(추정). 실제 충전·주유 영수증이 아니라 효율·잔량과 설정 단가로 계산한 값입니다.
 * @property kmPerUnit 계산에 실제로 쓴 평균 효율
 */
data class TripCost(
    val totalWon: Double,
    val wonPerKm: Double,
    val unitsUsed: Double,
    val basis: CostBasis,
    val kmPerUnit: Double,
    /** 기록 중 충전·주유가 있었는지(잔량 방식에서는 늘어난 양을 빼지 않고 감소량만 더했습니다). */
    val rechargedDuringTrip: Boolean = false,
    /** [totalWon] 중 차량 유지비(거리 × km당 유지비). 나머지가 에너지비입니다. */
    val wearWon: Double = 0.0
)

data class RiderShare(
    val rider: Rider,
    val distanceKm: Double,
    /** 반올림 전 정확한 몫(원). */
    val exactWon: Double,
    /** 실제로 보낼 금액(원). 운전자는 '본인 부담액'입니다. */
    val amountWon: Long,
    val isDriver: Boolean,
    /** 이 사람 몫 중 입력한 확정 비용(통행료 등). */
    val extrasWon: Double = 0.0
)

data class SegmentCharge(
    val segment: Segment,
    val costWon: Double,
    val payerIds: Set<String>
)

data class Settlement(
    val record: TripRecord,
    val tripCost: TripCost,
    /** 입력한 확정 비용 합계(통행료·주차비). */
    val extrasWon: Long,
    val shares: List<RiderShare>,
    val segmentCharges: List<SegmentCharge>
) {
    /** 운전자에게 보내야 할 사람들(운전자 제외, 0원 제외). */
    val payers: List<RiderShare> get() = shares.filter { !it.isDriver && it.amountWon > 0 }
    val driverShare: RiderShare get() = shares.first { it.isDriver }
    val grandTotalWon: Long get() = shares.sumOf { it.amountWon }
    /** 차량 비용(에너지비 추정 + 유지비) 원 단위. */
    val energyWon: Long get() = grandTotalWon - extrasWon
    /** 그중 차량 유지비. */
    val wearWon: Long get() = Math.round(tripCost.wearWon)
    /** 반올림으로 운전자가 더(+) 또는 덜(-) 부담한 금액. */
    val roundingAdjustmentWon: Long get() = driverShare.amountWon - Math.round(driverShare.exactWon)
}

object CostCalculator {
    /** 잔량 방식은 감소량이 이 값 이상일 때만 씁니다. 잔량은 보통 1% 단위라 짧은 운행에서는 오차가 큽니다. */
    const val MIN_MEASURED_DROP_PERCENT = 3.0

    /**
     * 운행 전체 에너지 비용(추정). 우선순위:
     * 1) 차량이 보고한 효율: 보고 시점마다 그 뒤 거리에 그 값을 적용합니다(한 번 받은 값을 끝까지 쓰지 않음).
     * 2) 잔량 감소량 합계: 중간 충전이 있어도 감소한 양만 더합니다. 감소가 [MIN_MEASURED_DROP_PERCENT] 미만이면 쓰지 않습니다.
     * 3) 사용자 기본 효율
     */
    fun tripCost(record: TripRecord, config: PricingConfig): TripCost {
        val distance = record.distanceKm
        val kind = config.energyKind
        val recharged = kind in record.energyRecharged
        if (distance <= 0.0) return TripCost(0.0, 0.0, 0.0, CostBasis.USER_EFFICIENCY, config.fallbackKmPerUnit, recharged)

        val samples = record.efficiencySamples[kind].orEmpty().filter { it.kmPerUnit > 0 }.sortedBy { it.atKm }
        if (samples.isNotEmpty()) {
            val units = record.segments.sumOf { unitsOver(it.startKm, it.endKm, samples) }
            if (units > 0) return fromUnits(units, distance, config, CostBasis.VEHICLE_EFFICIENCY, recharged)
        }

        val used = record.energyUsedPercent[kind]
            ?: run {
                // 이전 형식의 기록: 시작·종료 잔량 차이(충전이 없었을 때만).
                val start = record.energyStartPercent[kind]; val end = record.energyEndPercent[kind]
                if (start != null && end != null && start > end && !recharged) start - end else null
            }
        val capacity = config.capacityUnits
        if (used != null && used >= MIN_MEASURED_DROP_PERCENT && capacity != null && capacity > 0.0) {
            return fromUnits(used / 100.0 * capacity, distance, config, CostBasis.MEASURED_ENERGY_USE, recharged)
        }

        return fromUnits(distance / config.fallbackKmPerUnit, distance, config, CostBasis.USER_EFFICIENCY, recharged)
    }

    /** [from, to] 거리에 효율 이력을 구간별로 적용한 사용량. 첫 보고 전 거리에는 첫 값을 씁니다. */
    internal fun unitsOver(from: Double, to: Double, samples: List<EfficiencySample>): Double {
        if (to <= from) return 0.0
        var units = 0.0
        var cursor = from
        for (i in samples.indices) {
            val eff = samples[i].kmPerUnit
            val nextAt = samples.getOrNull(i + 1)?.atKm ?: Double.MAX_VALUE
            val segEnd = minOf(to, nextAt)
            if (segEnd > cursor) { units += (segEnd - cursor) / eff; cursor = segEnd }
            if (cursor >= to) break
        }
        return units
    }

    private fun fromUnits(units: Double, distance: Double, config: PricingConfig, basis: CostBasis, recharged: Boolean): TripCost {
        val wear = distance * config.wearWonPerKm
        val total = units * config.unitPriceWon + wear
        return TripCost(total, total / distance, units, basis, distance / units, recharged, wear)
    }
}

object SettlementCalculator {

    fun settle(record: TripRecord, config: PricingConfig): Settlement {
        val cost = CostCalculator.tripCost(record, config)
        val driverId = record.driver.id
        val exact = linkedMapOf<String, Double>().apply {
            record.riders.forEach { put(it.id, 0.0) }
            putIfAbsent(driverId, 0.0)
        }
        val extrasBy = mutableMapOf<String, Double>()
        val distanceBy = mutableMapOf<String, Double>()

        fun payersOf(ids: Set<String>): Set<String> {
            val known = ids.filter { it in exact }.toSet()
            val candidates = if (config.driverPaysShare) known else known - driverId
            // 아무도 낼 사람이 없는 경우(운전자 혼자)는 운전자 본인 몫입니다.
            return candidates.ifEmpty { setOf(driverId) }
        }

        val charges = record.segments.map { segment ->
            val segmentCost = cost.wonPerKm * segment.distanceKm
            val payers = payersOf(segment.occupantIds)
            payers.forEach { id -> exact[id] = (exact[id] ?: 0.0) + segmentCost / payers.size }
            segment.occupantIds.forEach { id -> distanceBy[id] = (distanceBy[id] ?: 0.0) + segment.distanceKm }
            SegmentCharge(segment, segmentCost, payers)
        }

        // 통행료·주차비: 입력할 때 고른 사람(기본값은 방금 달려온 구간의 탑승자)끼리 나눕니다.
        record.extras.forEach { extra ->
            val chosen = extra.payerIds.ifEmpty { segmentAt(record.segments, extra.atKm)?.occupantIds ?: setOf(driverId) }
            val payers = payersOf(chosen)
            payers.forEach { id ->
                val part = extra.amountWon.toDouble() / payers.size
                exact[id] = (exact[id] ?: 0.0) + part
                extrasBy[id] = (extrasBy[id] ?: 0.0) + part
            }
        }
        val extrasWon = record.extras.sumOf { it.amountWon }

        // 동승자는 반올림 단위로 맞추고, 남는 차액은 운전자가 흡수해 총액을 정확히 보존합니다.
        // 반올림으로 동승자 합계가 실제 총액을 넘으면(운전자가 돈을 남기게 되면) 올림 폭이 큰 사람부터
        // 한 단위씩 내려 합계가 총액을 넘지 않게 합니다. 그래서 운전자 부담은 0원 아래로 내려가지 않습니다.
        val total = Math.round(cost.totalWon + extrasWon)
        val passengerAmounts = capToTotal(
            exact.filterKeys { it != driverId }.mapValues { (_, won) -> roundHalfUp(won, config.roundingUnitWon) },
            exact, total, config.roundingUnitWon
        )
        val driverAmount = total - passengerAmounts.values.sum()

        val riderById = record.riders.associateBy { it.id } + (driverId to record.driver)
        val shares = exact.keys.map { id ->
            RiderShare(
                rider = riderById[id] ?: Rider(id, "동승자"),
                distanceKm = distanceBy[id] ?: 0.0,
                exactWon = exact[id] ?: 0.0,
                amountWon = if (id == driverId) driverAmount else passengerAmounts.getValue(id),
                isDriver = id == driverId,
                extrasWon = extrasBy[id] ?: 0.0
            )
        }.sortedWith(compareBy<RiderShare> { !it.isDriver }.thenByDescending { it.amountWon })

        return Settlement(record, cost, extrasWon, shares, charges)
    }

    private fun segmentAt(segments: List<Segment>, km: Double): Segment? =
        segments.lastOrNull { km > it.startKm && km <= it.endKm }
            ?: segments.firstOrNull { km >= it.startKm && km < it.endKm }

    /** 반올림한 동승자 금액의 합이 [total]을 넘지 않게 올림 폭이 큰 사람부터 한 단위씩 내립니다. 같은 입력이면 같은 결과입니다. */
    internal fun capToTotal(rounded: Map<String, Long>, exact: Map<String, Double>, total: Long, unit: Long): Map<String, Long> {
        val out = rounded.toMutableMap()
        var excess = out.values.sum() - total
        while (excess > 0) {
            val id = out.entries.filter { it.value > 0 }
                .maxWithOrNull(compareBy<Map.Entry<String, Long>> { it.value - (exact[it.key] ?: 0.0) }.thenBy { it.key })
                ?.key ?: break
            val cut = minOf(unit, out.getValue(id))
            out[id] = out.getValue(id) - cut
            excess -= cut
        }
        return out
    }

    internal fun roundHalfUp(won: Double, unit: Long): Long =
        (floor(won / unit + 0.5) * unit).toLong()
}
