package kr.gachitan.core

import java.util.Locale

object Formatting {
    fun won(amount: Long): String = String.format(Locale.KOREA, "%,d원", amount)

    fun km(value: Double): String =
        if (value >= 100) String.format(Locale.KOREA, "%,.0fkm", value)
        else String.format(Locale.KOREA, "%.1fkm", value)

    fun wonPerKm(value: Double): String = String.format(Locale.KOREA, "km당 %,.0f원", value)
}

/** 동승자에게 보여줄 송금 정보. 앱은 결제를 처리하지 않고 링크·문구만 만듭니다. */
object PaymentRequest {
    /**
     * 토스 아이디가 있으면 송금 링크를, 없으면 사람이 읽을 수 있는 안내 문구를 만듭니다.
     * 이 문자열이 QR 코드에 그대로 들어갑니다.
     */
    fun payload(share: RiderShare, driverName: String, tossId: String?, account: String?): String =
        payload(share.rider.name, share.distanceKm, share.amountWon, driverName, tossId, account)

    /**
     * 한 사람 몫만 담습니다. 다른 동승자의 이름·금액은 들어가지 않습니다.
     * - 토스 아이디가 있으면: 토스 아이디와 금액만 든 송금 링크
     * - 없으면: 이 사람 이름, 탄 거리, 금액, 받는 사람 이름(+ 설정한 경우 계좌 안내)
     */
    fun payload(name: String, km: Double, amountWon: Long, driverName: String, tossId: String?, account: String?): String {
        val id = validTossId(tossId)
        if (id != null) return "https://toss.me/$id/$amountWon"
        return buildString {
            append("[같이 탄 만큼] ${name}님 정산\n")
            append("${Formatting.km(km)} 탑승 · ${Formatting.won(amountWon)}\n")
            append("받는 사람: $driverName")
            account?.takeIf { it.isNotBlank() }?.let { append("\n계좌: $it") }
        }
    }

    fun validTossId(tossId: String?): String? =
        tossId?.trim()?.trimStart('@')?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{2,40}")) }

    fun summary(settlement: Settlement): String = buildString {
        val r = settlement.record
        append("[같이 탄 만큼] 총 ${Formatting.km(r.distanceKm)} · ${Formatting.won(settlement.grandTotalWon)}\n")
        append("에너지비 ${Formatting.won(settlement.energyWon)}(추정, ${settlement.tripCost.basis.description})")
        if (settlement.extrasWon > 0) append(" + 입력 비용 ${Formatting.won(settlement.extrasWon)}")
        append('\n')
        settlement.shares.forEach { s ->
            val who = if (s.isDriver) "${s.rider.name}(운전)" else s.rider.name
            append("· $who ${Formatting.km(s.distanceKm)} ${Formatting.won(s.amountWon)}\n")
        }
    }.trimEnd()
}
