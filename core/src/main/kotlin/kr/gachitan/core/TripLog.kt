package kr.gachitan.core

import java.util.Locale

/**
 * 정산을 확정한 운행 1회의 기록. '기록' 화면의 목록과 정산 상세가 이 값 하나로 그려집니다.
 *
 * 저장하는 것은 정산 결과(사람별 거리·금액, 구간별 탑승 인원, 비용 근거)입니다.
 * 앱은 송금을 확인할 수 없으므로 '받음'은 운전자가 직접 표시한 값([paidIds])일 뿐입니다.
 */
data class TripLog(
    val id: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val distanceKm: Double,
    val totalWon: Long,
    val energyWon: Long,
    val extrasWon: Long,
    val basis: CostBasis,
    val wonPerKm: Double,
    val kmPerUnit: Double,
    val unitsUsed: Double,
    val rechargeExcluded: Boolean,
    /** 운전자도 구간 비용을 나눠 냈는지. */
    val driverIncluded: Boolean,
    /** 반올림 때문에 운전자가 더(+) 또는 덜(-) 부담한 금액. */
    val roundingWon: Long,
    val shares: List<LogShare>,
    val segments: List<LogSegment>,
    val extras: List<LogExtra>,
    /** 묶음 정산에 들어간 운행이면 묶음 이름. 이 경우 운행별 송금 요청을 만들지 않습니다. */
    val bundleTitle: String?,
    /** [energyWon] 중 차량 유지비(선택 설정). 0이면 쓰지 않은 운행입니다. */
    val wearWon: Long = 0,
    /** 운전자가 '받음'으로 표시한 동승자 id. 앱이 송금을 확인한 것이 아닙니다. */
    val paidIds: Set<String> = emptySet()
) {
    /** 아직 '받음' 표시가 없는 동승자 금액의 합. */
    val unpaidWon: Long get() = payers.filter { it.riderId !in paidIds }.sumOf { it.amountWon }
    val allPaid: Boolean get() = payers.isNotEmpty() && payers.all { it.riderId in paidIds }
    fun withPaid(riderId: String, paid: Boolean): TripLog = copy(paidIds = if (paid) paidIds + riderId else paidIds - riderId)
    /** 유지비를 뺀 에너지비(추정). */
    val fuelOnlyWon: Long get() = energyWon - wearWon
    val driver: LogShare? get() = shares.firstOrNull { it.isDriver }
    /** 돈을 보낼 사람(운전자 제외, 0원 제외). */
    val payers: List<LogShare> get() = shares.filter { !it.isDriver && it.amountWon > 0 }
    val inBundle: Boolean get() = bundleTitle != null

    /** 탑승 인원이 같은 연속 구간을 하나로 합친 막대. 거리 0인 구간은 뺍니다. */
    fun bands(): List<Band> {
        val names = shares.associate { it.riderId to it.name }
        val merged = mutableListOf<Pair<Double, Set<String>>>()
        segments.filter { it.km > 0.0 }.forEach { s ->
            val last = merged.lastOrNull()
            if (last != null && last.second == s.riderIds) merged[merged.lastIndex] = (last.first + s.km) to last.second
            else merged += s.km to s.riderIds
        }
        return merged.mapIndexed { i, (km, ids) ->
            val prev = merged.getOrNull(i - 1)?.second
            val change = if (prev == null) null else {
                val off = (prev - ids).mapNotNull { names[it] }.map { "$it 하차" }
                val on = (ids - prev).mapNotNull { names[it] }.map { "$it 탑승" }
                (off + on).joinToString(" · ").ifEmpty { null }
            }
            Band(km = km, people = ids.size, changeBefore = change)
        }
    }

    /** 처음과 마지막 구간의 인원(예: 3명 → 2명). 바뀐 적이 없으면 한 값입니다. */
    fun peopleChange(): Pair<Int, Int> {
        val b = bands()
        val first = b.firstOrNull()?.people ?: shares.size
        val last = b.lastOrNull()?.people ?: shares.size
        return first to last
    }

    data class Band(val km: Double, val people: Int, val changeBefore: String?)

    companion object {
        fun from(settlement: Settlement, id: String, bundleTitle: String?): TripLog {
            val r = settlement.record
            val c = settlement.tripCost
            return TripLog(
                id = id,
                startedAtMillis = r.startedAtMillis,
                endedAtMillis = r.endedAtMillis,
                distanceKm = r.distanceKm,
                totalWon = settlement.grandTotalWon,
                energyWon = settlement.energyWon,
                extrasWon = settlement.extrasWon,
                basis = c.basis,
                wonPerKm = c.wonPerKm,
                kmPerUnit = c.kmPerUnit,
                unitsUsed = c.unitsUsed,
                rechargeExcluded = c.rechargedDuringTrip,
                driverIncluded = settlement.segmentCharges.any { r.driver.id in it.payerIds },
                roundingWon = settlement.roundingAdjustmentWon,
                shares = settlement.shares.map {
                    LogShare(it.rider.id, it.rider.name, it.distanceKm, it.amountWon, it.isDriver, it.rider.id.startsWith("guest-"))
                },
                segments = r.segments.map { LogSegment(it.distanceKm, it.occupantIds) },
                extras = r.extras.map { LogExtra(it.label, it.amountWon) },
                bundleTitle = bundleTitle,
                wearWon = settlement.wearWon
            )
        }
    }
}

data class LogShare(
    val riderId: String,
    val name: String,
    val distanceKm: Double,
    val amountWon: Long,
    val isDriver: Boolean,
    /** 이름을 붙이지 않은 동승자(좌석 이름으로 표시). */
    val unnamed: Boolean = false
)

/** 구간. [riderIds]에는 운전자도 들어 있습니다. */
data class LogSegment(val km: Double, val riderIds: Set<String>)

data class LogExtra(val label: String, val amountWon: Long)

/**
 * TripLog 목록을 한 줄씩 저장합니다(사람이 읽을 수 있는 형식, 암호화는 저장소가 맡습니다).
 * 알 수 없는 줄은 건너뛰고, 깨진 기록 하나 때문에 나머지를 잃지 않습니다.
 */
object TripLogCodec {
    private const val VERSION = "L1"
    private fun e(s: String) = TextEscape.esc(s)
    private fun u(s: String) = TextEscape.unesc(s)
    private fun n(d: Double) = String.format(Locale.ROOT, "%.4f", d)

    fun encode(logs: List<TripLog>): String = buildString {
        append(VERSION).append('\n')
        logs.forEach { l ->
            append(listOf("T", e(l.id), l.startedAtMillis, l.endedAtMillis, n(l.distanceKm), l.totalWon, l.energyWon, l.extrasWon,
                l.basis.name, n(l.wonPerKm), n(l.kmPerUnit), n(l.unitsUsed), l.rechargeExcluded, l.driverIncluded, l.roundingWon,
                e(l.bundleTitle ?: ""), l.wearWon).joinToString("|")).append('\n')
            l.shares.forEach { s ->
                append(listOf("S", e(s.riderId), e(s.name), n(s.distanceKm), s.amountWon, s.isDriver, s.unnamed).joinToString("|")).append('\n')
            }
            l.segments.forEach { g ->
                append("G|").append(n(g.km)).append('|').append(g.riderIds.joinToString(",") { e(it) }).append('\n')
            }
            l.extras.forEach { x -> append("X|").append(e(x.label)).append('|').append(x.amountWon).append('\n') }
            l.paidIds.forEach { id -> append("P|").append(e(id)).append('\n') }
        }
    }

    fun decode(text: String): List<TripLog> {
        val lines = text.lines()
        if (lines.firstOrNull() != VERSION) return emptyList()
        val out = mutableListOf<TripLog>()
        var cur: TripLog? = null
        fun flush() { cur?.let(out::add); cur = null }
        lines.drop(1).filter { it.isNotBlank() }.forEach { line ->
            val p = line.split("|")
            runCatching {
                when (p[0]) {
                    "T" -> {
                        flush()
                        cur = TripLog(
                            id = u(p[1]), startedAtMillis = p[2].toLong(), endedAtMillis = p[3].toLong(), distanceKm = p[4].toDouble(),
                            totalWon = p[5].toLong(), energyWon = p[6].toLong(), extrasWon = p[7].toLong(),
                            basis = CostBasis.valueOf(p[8]), wonPerKm = p[9].toDouble(), kmPerUnit = p[10].toDouble(),
                            unitsUsed = p[11].toDouble(), rechargeExcluded = p[12].toBoolean(), driverIncluded = p[13].toBoolean(),
                            roundingWon = p[14].toLong(), shares = emptyList(), segments = emptyList(), extras = emptyList(),
                            bundleTitle = u(p[15]).ifEmpty { null },
                            wearWon = p.getOrNull(16)?.toLongOrNull() ?: 0L   // 1.3 이전 기록에는 없음
                        )
                    }
                    "S" -> cur = cur?.let { it.copy(shares = it.shares + LogShare(u(p[1]), u(p[2]), p[3].toDouble(), p[4].toLong(),
                        p[5].toBoolean(), p.getOrNull(6)?.toBoolean() ?: false)) }
                    "G" -> cur = cur?.let { it.copy(segments = it.segments + LogSegment(p[1].toDouble(),
                        p.getOrNull(2).orEmpty().split(",").filter { s -> s.isNotEmpty() }.map(::u).toSet())) }
                    "X" -> cur = cur?.let { it.copy(extras = it.extras + LogExtra(u(p[1]), p[2].toLong())) }
                    "P" -> cur = cur?.let { it.copy(paidIds = it.paidIds + u(p[1])) }
                    else -> Unit
                }
            }
        }
        flush()
        return out.filter { it.shares.any { s -> s.isDriver } }
    }
}
