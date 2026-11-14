package kr.gachitan.ui.model

import kr.gachitan.core.CostBasis
import kr.gachitan.core.EnergyKind
import kr.gachitan.core.Formatting
import kr.gachitan.core.LogShare
import kr.gachitan.core.PaymentRequest
import kr.gachitan.core.TripLog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 정산·기록 화면의 문구 규칙. Android에 의존하지 않아 JVM 테스트로 검증합니다.
 * 금액·거리는 모두 [TripLog]에 저장된 값 그대로 쓰고, 화면에서 다시 계산하지 않습니다.
 */

/** "km당 60원 · 운전자 포함" 같은 한 줄 규칙. */
fun ruleText(log: TripLog): String = buildList {
    add(Formatting.wonPerKm(log.wonPerKm))
    add(if (log.driverIncluded) "운전자 포함" else "동승자만 분담")
    // 입력한 통행료 등은 총액 아래 내역(costBreakdown)에 따로 보여 줍니다.
}.joinToString(" · ")

/** 사람별 부담액 줄의 둘째 줄. */
fun shareSubtitle(share: LogShare, log: TripLog): String = buildList {
    add(Formatting.km(share.distanceKm))
    when {
        share.isDriver -> add(if (log.driverIncluded) "운전 · 본인 부담" else "운전")
        share.distanceKm < log.distanceKm - 0.05 -> add("일부 구간")
    }
    if (share.unnamed) add("이름 없음")
}.joinToString(" · ")

/** 처음 → 마지막 탑승 인원. 바뀌지 않았으면 한 값만. */
fun peopleChangeText(log: TripLog): String {
    val (a, b) = log.peopleChange()
    return if (a == b) "${a}명" else "${a}명 → ${b}명"
}

fun timeRange(startMillis: Long, endMillis: Long, zone: ZoneId): String {
    fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    // 자정을 넘긴 운행은 끝 시각이 시작보다 작아 보이므로 날짜 차이를 함께 적습니다.
    val days = dateOf(endMillis, zone).toEpochDay() - dateOf(startMillis, zone).toEpochDay()
    val endText = when {
        days == 1L -> "다음 날 ${hm(endMillis)}"
        days > 1L -> "${days}일 뒤 ${hm(endMillis)}"
        else -> hm(endMillis)
    }
    return "${hm(startMillis)} — $endText"
}

/**
 * 총액 아래 한 줄: 추정한 에너지비와 사람이 입력한 확정 비용을 나눠 보여 줍니다.
 * 총액 전체에 '추정'을 붙이면 입력한 통행료까지 추정처럼 보이기 때문입니다.
 */
fun costBreakdown(log: TripLog): String? {
    if (log.extrasWon <= 0 && log.wearWon <= 0) return null
    return buildList {
        add("에너지비 ${Formatting.won(log.fuelOnlyWon)}")
        if (log.wearWon > 0) add("유지비 ${Formatting.won(log.wearWon)}")
        if (log.extrasWon > 0) add("통행료 등 ${Formatting.won(log.extrasWon)}")
    }.joinToString(" + ")
}

/** 반올림 차이를 누가 맞췄는지. 운전자 이름이 '나'여도 어색하지 않게 씁니다. */
fun roundingText(roundingWon: Long): String? =
    if (roundingWon == 0L) null
    else "반올림 차이 ${Formatting.won(kotlin.math.abs(roundingWon))}은 운전자 몫에서 맞췄어요(${if (roundingWon > 0) "더" else "덜"} 부담)."

fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** "11월 11일 · 오늘", "11월 10일 · 어제", "11월 9일 (월)". */
fun dateHeader(date: LocalDate, today: LocalDate): String {
    val base = "${date.monthValue}월 ${date.dayOfMonth}일"
    return when (date) {
        today -> "$base · 오늘"
        today.minusDays(1) -> "$base · 어제"
        else -> {
            val dow = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
            if (date.year == today.year) "$base ($dow)" else "${date.year}년 $base ($dow)"
        }
    }
}

/** 운행을 끝난 날짜별로 묶습니다(최신 날짜 먼저, 날짜 안에서는 최신 운행 먼저). */
fun groupByDate(trips: List<TripLog>, zone: ZoneId): List<Pair<LocalDate, List<TripLog>>> =
    trips.sortedByDescending { it.endedAtMillis }
        .groupBy { dateOf(it.endedAtMillis, zone) }
        .toList()

/**
 * QR에 무엇이 담기는지. [PaymentRequest.payload]가 실제로 만드는 내용과 같아야 합니다(테스트로 확인).
 */
fun qrContentsText(name: String, tossId: String?, account: String?): String =
    if (PaymentRequest.validTossId(tossId) != null) {
        "휴대폰 카메라로 찍으면 토스 송금 화면이 열려요. QR에는 받는 사람 토스 아이디와 이 금액만 담겨요."
    } else {
        val parts = listOf("${name}님 이름", "탄 거리", "금액", "받는 사람 이름") + listOfNotNull("계좌 안내".takeIf { !account.isNullOrBlank() })
        "휴대폰 카메라로 찍으면 정산 내용이 글자로 보여요. QR에는 ${parts.joinToString("·")}만 담겨요."
    }

const val QR_PRIVACY = "다른 동승자의 금액은 담기지 않고, 이 앱은 정산 내용을 서버로 보내지 않아요."

/** 계산 근거 한 줄. 에너지비는 언제나 추정입니다. */
fun basisText(log: TripLog, kind: EnergyKind, unitPriceWon: Double): String {
    val unit = if (kind == EnergyKind.ELECTRIC) "kWh" else "L"
    val price = "%,.0f원/%s".format(unitPriceWon, unit)
    val how = when (log.basis) {
        CostBasis.VEHICLE_EFFICIENCY -> "차량이 알려 준 효율 %.1fkm/%s × 단가 %s".format(log.kmPerUnit, unit, price)
        CostBasis.MEASURED_ENERGY_USE -> "잔량 감소 %.1f%s × 단가 %s".format(log.unitsUsed, unit, price)
        CostBasis.USER_EFFICIENCY -> "설정한 효율 %.1fkm/%s × 단가 %s".format(log.kmPerUnit, unit, price)
    }
    return if (log.rechargeExcluded) "$how · 중간 충전분 제외" else how
}

/** 구간별 1인 부담(근거 화면). 인원으로 나눈 값이며 반올림 전입니다. */
data class BandCost(val km: Double, val people: Int, val costWon: Double) {
    val perPersonWon: Double get() = if (people == 0) 0.0 else costWon / people
}

fun bandCosts(log: TripLog): List<BandCost> {
    val payersPerBand = log.bands()
    return payersPerBand.map { b ->
        val payers = if (log.driverIncluded) b.people else (b.people - 1).coerceAtLeast(0)
        BandCost(b.km, payers, b.km * log.wonPerKm)
    }
}
