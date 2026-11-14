package kr.gachitan.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/**
 * 묶음 정산 = 여러 번의 운행(여행 며칠, 한 주 카풀) + 선택적인 공동 경비.
 *
 * 중복 청구 방지 정책: 묶음에 들어간 운행은 운행마다 송금을 요청하지 않습니다(앱이 운행별 QR을 보여 주지 않음).
 * 운행별로 계산된 몫(TripEntry.shares)은 장부에만 쌓이고, 묶음을 끝낼 때 한 번에 정리합니다.
 * 송금 정리는 '받을 사람과 보낼 사람을 큰 금액부터 맞붙이는' 방식이라 n명이면 많아야 n-1번입니다.
 * 이 방식이 모든 경우에 이론적 최소 횟수를 보장하지는 않습니다.
 */
data class Member(val id: String, val name: String)

data class StopEntry(val name: String, val km: Double, val atMillis: Long)

data class ShareEntry(val riderId: String, val name: String, val km: Double, val amountWon: Long)

/** 끝난 운행 1회의 요약. TripRecord + Settlement에서 만들어집니다. */
data class TripEntry(
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val distanceKm: Double,
    val costWon: Long,
    val basis: CostBasis,
    val driverId: String,
    val startName: String,
    val stops: List<StopEntry>,
    val shares: List<ShareEntry>
) {
    companion object {
        fun from(settlement: Settlement): TripEntry {
            val r = settlement.record
            return TripEntry(
                startedAtMillis = r.startedAtMillis,
                endedAtMillis = r.endedAtMillis,
                distanceKm = r.distanceKm,
                costWon = settlement.grandTotalWon,
                basis = settlement.tripCost.basis,
                driverId = r.driver.id,
                startName = r.startPlace?.displayName("출발지") ?: "출발지",
                stops = r.stops.mapIndexed { i, s ->
                    StopEntry(s.place?.displayName("정차 ${i + 1}") ?: "정차 ${i + 1}", s.km - r.startKm, s.atMillis)
                },
                shares = settlement.shares.map { ShareEntry(it.rider.id, it.rider.name, it.distanceKm, it.amountWon) }
            )
        }
    }
}

/**
 * 경비 분류. 사람이 고른 이름(label)을 그대로 저장하고, 분류는 이름에서 정합니다(저장 형식을 바꾸지 않음).
 *
 * [rideBound] 분류(통행료·주차비)는 '그때 차에 타고 있던 사람'이 나누는 비용이라 운행 화면에서
 * 그 구간 탑승자를 기본으로 고릅니다. 나머지(숙박·식비 등)는 여행 전체 인원이 기본입니다.
 * 주유·충전 영수증은 경비로 받지 않습니다. 차량 비용(에너지비)이 이미 운행마다 계산되어 두 번 청구되기 때문입니다.
 */
enum class ExpenseCategory(val label: String, val rideBound: Boolean, val aliases: List<String> = emptyList()) {
    TOLL("통행료", true, listOf("톨비", "하이패스", "고속도로")),
    PARKING("주차비", true, listOf("주차")),
    WASH("세차비", true, listOf("세차")),
    LODGING("숙박", false, listOf("숙소", "호텔", "펜션", "캠핑")),
    FOOD("식비", false, listOf("식사", "밥", "점심", "저녁", "아침", "간식")),
    CAFE("카페", false, listOf("커피", "음료")),
    ADMISSION("입장료", false, listOf("티켓", "관람", "입장")),
    OTHER("기타", false);

    companion object {
        /** 경비 이름 → 분류. 프리셋 이름이면 그대로, 직접 쓴 이름은 비슷한 말로 찾고, 없으면 기타. */
        fun of(label: String): ExpenseCategory {
            val l = label.trim()
            entries.firstOrNull { it.label == l }?.let { return it }
            return entries.firstOrNull { c -> c != OTHER && (l.contains(c.label) || c.aliases.any { l.contains(it) }) } ?: OTHER
        }
        /** 운행 중(정차 화면)에 입력하는 비용의 프리셋. */
        val rideExtras: List<ExpenseCategory> = listOf(TOLL, PARKING, WASH, OTHER)
        /** 여행 공동 경비의 프리셋. */
        val journeyExpenses: List<ExpenseCategory> = listOf(LODGING, FOOD, CAFE, ADMISSION, TOLL, PARKING, OTHER)
    }
}

/** 분류별 합계 한 줄. */
data class CategoryTotal(val category: ExpenseCategory, val amountWon: Long, val count: Int)

/** 공동 경비. [payerId]가 냈고, [participantIds]가 똑같이 나눕니다. */
data class SharedExpense(
    val id: String,
    val label: String,
    val amountWon: Long,
    val payerId: String,
    val participantIds: Set<String>,
    val atMillis: Long
)

data class Journey(
    val id: String,
    val title: String,
    val startedAtMillis: Long,
    val trips: List<TripEntry> = emptyList(),
    val expenses: List<SharedExpense> = emptyList(),
    val extraMembers: List<Member> = emptyList(),
    val endedAtMillis: Long? = null
) {
    val isEnded: Boolean get() = endedAtMillis != null

    /** 여행에 등장한 모든 사람. 같은 id는 가장 최근 이름으로 합칩니다. */
    fun members(): List<Member> {
        val byId = linkedMapOf<String, String>()
        trips.forEach { t -> t.shares.forEach { byId[it.riderId] = it.name } }
        extraMembers.forEach { byId[it.id] = it.name }
        return byId.map { (id, name) -> Member(id, name) }
    }

    fun withTrip(entry: TripEntry) = copy(trips = trips + entry)
    fun withExpense(e: SharedExpense) = copy(expenses = expenses + e)
    fun withoutExpense(id: String) = copy(expenses = expenses.filterNot { it.id == id })
    fun withMember(m: Member) = if (members().any { it.id == m.id }) this else copy(extraMembers = extraMembers + m)
}

data class DaySummary(
    val dayNumber: Int,
    val date: LocalDate,
    val trips: List<TripEntry>,
    val distanceKm: Double,
    val costWon: Long,
    /** 그날 들른 곳(출발지 → 정차 지점들). 연속 중복은 합칩니다. */
    val route: List<String>
)

data class PersonTotal(
    val member: Member,
    val distanceKm: Double,
    /** 차량 연료·전기·통행료 중 이 사람 몫. */
    val fuelShareWon: Long,
    /** 공동 경비 중 이 사람 몫. */
    val expenseShareWon: Long,
    /** 이 사람이 실제로 낸 돈(운전자는 연료비, 결제자는 경비). */
    val paidWon: Long,
    /** + 받을 돈, - 보낼 돈. 송금 목록과 정확히 일치합니다(반올림 후 값). */
    val balanceWon: Long,
    /** 반올림으로 이 사람이 떠안은 차액(+면 덜 받거나 더 냄). 보통 0이고, 가장 많이 받는 한 사람만 생깁니다. */
    val roundingAbsorbedWon: Long = 0
)

data class Transfer(val from: Member, val to: Member, val amountWon: Long)

data class JourneySummary(
    val journey: Journey,
    val days: List<DaySummary>,
    val people: List<PersonTotal>,
    val transfers: List<Transfer>,
    val totalKm: Double,
    val totalFuelWon: Long,
    val totalExpenseWon: Long
) {
    /** 공동 경비를 분류별로 묶은 합계(금액 큰 순). */
    val expenseByCategory: List<CategoryTotal>
        get() = journey.expenses.groupBy { ExpenseCategory.of(it.label) }
            .map { (c, list) -> CategoryTotal(c, list.sumOf { it.amountWon }, list.size) }
            .sortedByDescending { it.amountWon }

    /** 나눌 사람이 운전자 한 명뿐인지(아직 동승자가 탄 운행도, 추가한 사람도 없음). */
    val onlyOnePerson: Boolean get() = people.size <= 1
}

object JourneyCalculator {

    fun summarize(journey: Journey, zone: ZoneId, roundingUnitWon: Long = 10): JourneySummary {
        val members = journey.members()
        val byId = members.associateBy { it.id }

        // ---- 날짜별
        val trips = journey.trips.sortedBy { it.startedAtMillis }
        val firstDate = trips.firstOrNull()?.let { dateOf(it.startedAtMillis, zone) }
            ?: dateOf(journey.startedAtMillis, zone)
        val days = trips.groupBy { dateOf(it.startedAtMillis, zone) }.toSortedMap().map { (date, list) ->
            val route = mutableListOf<String>()
            list.forEach { t ->
                (listOf(t.startName) + t.stops.map { it.name }).forEach { name ->
                    if (route.lastOrNull() != name) route += name
                }
            }
            DaySummary(
                dayNumber = (date.toEpochDay() - firstDate.toEpochDay()).toInt() + 1,
                date = date,
                trips = list,
                distanceKm = list.sumOf { it.distanceKm },
                costWon = list.sumOf { it.costWon },
                route = route
            )
        }

        // ---- 장부: 낸 돈(paid)과 내야 할 돈(owed)
        val paid = mutableMapOf<String, Double>()
        val owedFuel = mutableMapOf<String, Double>()
        val owedExpense = mutableMapOf<String, Double>()
        val km = mutableMapOf<String, Double>()
        trips.forEach { t ->
            paid.add(t.driverId, t.costWon.toDouble())
            t.shares.forEach { s ->
                owedFuel.add(s.riderId, s.amountWon.toDouble())
                km.add(s.riderId, s.km)
            }
        }
        journey.expenses.forEach { e ->
            val participants = e.participantIds.ifEmpty { members.map { it.id }.toSet() }
            paid.add(e.payerId, e.amountWon.toDouble())
            participants.forEach { id -> owedExpense.add(id, e.amountWon.toDouble() / participants.size) }
        }

        val allIds = (members.map { it.id } + paid.keys + owedFuel.keys + owedExpense.keys).distinct()
        val exactBalance = allIds.associateWith { id ->
            (paid[id] ?: 0.0) - (owedFuel[id] ?: 0.0) - (owedExpense[id] ?: 0.0)
        }
        val balance = roundBalances(exactBalance, roundingUnitWon)
        fun member(id: String) = byId[id] ?: Member(id, "알 수 없음")

        val people = allIds.map { id ->
            val rounded = balance.getValue(id)
            PersonTotal(
                member = member(id),
                distanceKm = km[id] ?: 0.0,
                fuelShareWon = Math.round(owedFuel[id] ?: 0.0),
                expenseShareWon = Math.round(owedExpense[id] ?: 0.0),
                paidWon = Math.round(paid[id] ?: 0.0),
                balanceWon = rounded,
                roundingAbsorbedWon = Math.round(exactBalance.getValue(id)) - rounded
            )
        }.sortedByDescending { it.balanceWon }

        return JourneySummary(
            journey = journey,
            days = days,
            people = people,
            transfers = settleUp(balance).map { (f, t, a) -> Transfer(member(f), member(t), a) },
            totalKm = trips.sumOf { it.distanceKm },
            totalFuelWon = trips.sumOf { it.costWon },
            totalExpenseWon = journey.expenses.sumOf { it.amountWon }
        )
    }

    /**
     * 정확한 잔액(원 미만 포함)을 송금 가능한 정수 잔액으로 바꿉니다.
     * - 가장 많이 받을 사람(보통 차 주인)을 기준점으로 두고, 나머지는 [unit] 단위로 반올림합니다.
     * - 기준점은 나머지의 합을 상쇄하는 값이 되어 전체 합이 정확히 0입니다.
     * 그래서 송금 목록의 합계와 사람별 잔액이 1원도 어긋나지 않습니다.
     */
    internal fun roundBalances(exact: Map<String, Double>, unit: Long): Map<String, Long> {
        if (exact.isEmpty()) return emptyMap()
        val anchor = exact.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).first().key
        val others = exact.filterKeys { it != anchor }.mapValues { (_, v) -> roundHalfUpSigned(v, unit) }.toMutableMap()
        // 보낼 사람의 금액을 올림하면 기준점(받는 사람)이 실제보다 더 받게 됩니다. 그 경우 더 많이 올림된
        // 보낼 사람부터 한 단위씩 줄여, 받는 사람이 정확한 금액보다 더 받지 않게 합니다.
        val target = Math.round(exact.getValue(anchor))
        while (-others.values.sum() > target) {
            val id = others.entries.filter { it.value < 0 }
                .maxWithOrNull(compareBy<Map.Entry<String, Long>> { exact.getValue(it.key) - it.value }.thenBy { it.key })
                ?.key ?: break
            others[id] = minOf(0L, others.getValue(id) + unit)
        }
        return others + (anchor to -others.values.sum())
    }

    private fun roundHalfUpSigned(v: Double, unit: Long): Long {
        val sign = if (v < 0) -1 else 1
        return sign * (floor(kotlin.math.abs(v) / unit + 0.5) * unit).toLong()
    }

    /**
     * 정수 잔액을 송금 목록으로 바꿉니다. 받을 사람과 보낼 사람을 금액이 큰 순서로 맞붙입니다.
     * 결과의 사람별 (받은 합 - 보낸 합)은 입력 잔액과 정확히 같습니다. 같은 입력이면 항상 같은 결과입니다.
     * n명이면 많아야 n-1번이며, 모든 경우의 최소 횟수를 보장하지는 않습니다.
     */
    internal fun settleUp(balance: Map<String, Long>): List<Triple<String, String, Long>> {
        val creditors = balance.filterValues { it > 0 }.toList()
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }).toMutableList()
        val debtors = balance.filterValues { it < 0 }.mapValues { -it.value }.toList()
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }).toMutableList()
        val result = mutableListOf<Triple<String, String, Long>>()
        var ci = 0; var di = 0
        while (ci < creditors.size && di < debtors.size) {
            val (cId, cAmt) = creditors[ci]
            val (dId, dAmt) = debtors[di]
            val x = minOf(cAmt, dAmt)
            if (x > 0) result += Triple(dId, cId, x)
            creditors[ci] = cId to (cAmt - x)
            debtors[di] = dId to (dAmt - x)
            if (creditors[ci].second == 0L) ci++
            if (debtors[di].second == 0L) di++
        }
        return result
    }

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun MutableMap<String, Double>.add(id: String, v: Double) { this[id] = (this[id] ?: 0.0) + v }
}
