package kr.gachitan.core

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater

internal object TextEscape {
    fun esc(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '%' -> append("%25"); '|' -> append("%7C"); ',' -> append("%2C")
                '\n' -> append("%0A"); '\r' -> append("%0D")
                else -> append(c)
            }
        }
    }

    fun unesc(s: String): String = s.replace("%0D", "\r").replace("%0A", "\n")
        .replace("%2C", ",").replace("%7C", "|").replace("%25", "%")
}

/** 여행 저장 형식(줄 단위). 기기 안에서 암호화되어 저장됩니다. */
object JourneyCodec {
    private const val SEP = "|"
    private fun e(s: String) = TextEscape.esc(s)
    private fun u(s: String) = TextEscape.unesc(s)

    fun encode(j: Journey): String = buildString {
        appendLine(listOf("J1", e(j.id), e(j.title), j.startedAtMillis, j.endedAtMillis ?: "").joinToString(SEP))
        j.extraMembers.forEach { appendLine(listOf("M", e(it.id), e(it.name)).joinToString(SEP)) }
        j.trips.forEach { t ->
            appendLine(listOf("T", t.startedAtMillis, t.endedAtMillis, t.distanceKm, t.costWon, t.basis.name,
                e(t.driverId), e(t.startName)).joinToString(SEP))
            t.stops.forEach { appendLine(listOf("TS", e(it.name), it.km, it.atMillis).joinToString(SEP)) }
            t.shares.forEach { appendLine(listOf("TH", e(it.riderId), e(it.name), it.km, it.amountWon).joinToString(SEP)) }
        }
        j.expenses.forEach { x ->
            appendLine(listOf("X", e(x.id), e(x.label), x.amountWon, e(x.payerId), x.atMillis,
                x.participantIds.joinToString(",") { e(it) }).joinToString(SEP))
        }
    }

    fun decode(text: String): Journey {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        val h = lines.first().split(SEP)
        require(h[0] == "J1") { "알 수 없는 여행 형식" }
        var j = Journey(u(h[1]), u(h[2]), h[3].toLong(), endedAtMillis = h[4].toLongOrNull())
        var trip: TripEntry? = null
        val trips = mutableListOf<TripEntry>()
        fun flush() { trip?.let(trips::add); trip = null }
        lines.drop(1).forEach { line ->
            val p = line.split(SEP)
            runCatching {
                when (p[0]) {
                    "M" -> j = j.copy(extraMembers = j.extraMembers + Member(u(p[1]), u(p[2])))
                    "T" -> {
                        flush()
                        trip = TripEntry(p[1].toLong(), p[2].toLong(), p[3].toDouble(), p[4].toLong(),
                            CostBasis.valueOf(p[5]), u(p[6]), u(p[7]), emptyList(), emptyList())
                    }
                    "TS" -> trip = trip?.let { it.copy(stops = it.stops + StopEntry(u(p[1]), p[2].toDouble(), p[3].toLong())) }
                    "TH" -> trip = trip?.let { it.copy(shares = it.shares + ShareEntry(u(p[1]), u(p[2]), p[3].toDouble(), p[4].toLong())) }
                    "X" -> j = j.copy(expenses = j.expenses + SharedExpense(
                        id = u(p[1]), label = u(p[2]), amountWon = p[3].toLong(), payerId = u(p[4]),
                        participantIds = p[6].split(",").filter { it.isNotEmpty() }.map(::u).toSet(),
                        atMillis = p[5].toLong()
                    ))
                }
            }
        }
        flush()
        return j.copy(trips = trips)
    }
}

/**
 * 묶음 정산 결과를 휴대폰으로 넘기는 형식.
 *
 * 서버에 저장하지 않습니다. 요약을 압축해 URL의 '#' 뒤(fragment)에 담고, 정적 뷰어 페이지가
 * 휴대폰 브라우저 안에서만 풀어서 보여 줍니다. fragment는 브라우저가 서버로 보내지 않습니다.
 *
 * 두 가지가 있습니다.
 * - 개인용([personalUrl], [personalText]): 그 사람 몫과 그 사람이 주고받을 송금만 담습니다.
 *   다른 사람의 금액, 다른 사람끼리의 송금, 경로·장소 이름은 들어가지 않습니다.
 * - 전체([url], [text]): 모든 사람의 이름·금액·경로가 담깁니다. 함께 확인할 때만 씁니다.
 */
object JourneyShare {
    /** QR(오류정정 L, 바이트 모드) 한 장에 안전하게 담기는 URL 길이. */
    const val MAX_URL_LENGTH = 2_300

    fun payload(summary: JourneySummary, tossId: String?, detail: Int = 2): String {
        val json = json(summary, tossId, detail)
        return base64Url(deflate(json.toByteArray(Charsets.UTF_8)))
    }

    /**
     * 뷰어 주소. [meIndex]가 있으면 그 사람의 송금 항목을 강조합니다.
     * 너무 길면 경로 상세(2) → 주요 지점만(1) → 경로 없음(0) 순서로 줄입니다.
     */
    fun url(baseUrl: String, summary: JourneySummary, tossId: String?, meIndex: Int? = null): String? {
        val base = safeBase(baseUrl) ?: return null
        for (detail in 2 downTo 0) {
            val u = buildString {
                append(base); append("#d="); append(payload(summary, tossId, detail))
                if (meIndex != null) append("&me=").append(meIndex)
            }
            if (u.length <= MAX_URL_LENGTH) return u
        }
        return null
    }

    /**
     * 뷰어 주소 검사. https 주소만 받습니다(설정에 잘못 넣은 javascript:·http: 주소가 QR로 퍼지지 않게).
     * 맞지 않으면 null이고, 이때 QR에는 링크 대신 텍스트 요약이 담깁니다.
     */
    internal fun safeBase(baseUrl: String): String? {
        val base = baseUrl.trim().substringBefore('#')
        if (!base.startsWith("https://", ignoreCase = true) || base.length <= "https://".length) return null
        if (base.any { it.isWhitespace() || it == '"' || it == '<' || it == '>' }) return null
        return base
    }

    fun memberIndex(summary: JourneySummary, memberId: String): Int? =
        memberOrder(summary).indexOfFirst { it.id == memberId }.takeIf { it >= 0 }

    /** 한 사람용 뷰어 주소. 그 사람의 몫과 그 사람이 관련된 송금만 담깁니다. */
    fun personalUrl(baseUrl: String, summary: JourneySummary, memberId: String, tossId: String?, driverId: String?): String? {
        val base = safeBase(baseUrl) ?: return null
        val json = personalJson(summary, memberId, tossId, driverId) ?: return null
        val u = base + "#d=" + base64Url(deflate(json.toByteArray(Charsets.UTF_8)))
        return u.takeIf { it.length <= MAX_URL_LENGTH }
    }

    internal fun personalJson(s: JourneySummary, memberId: String, tossId: String?, driverId: String?): String? {
        val me = s.people.firstOrNull { it.member.id == memberId } ?: return null
        val mine = s.transfers.filter { it.from.id == memberId || it.to.id == memberId }
        val toss = tossId?.trim()?.trimStart('@')?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{2,40}")) }
        val sb = StringBuilder()
        sb.append("{\"v\":2,\"k\":\"p\",\"t\":").append(str(s.journey.title))
        sb.append(",\"dn\":").append(s.days.size)
        sb.append(",\"n\":").append(str(me.member.name))
        sb.append(",\"km\":").append(num(me.distanceKm))
        sb.append(",\"f\":").append(me.fuelShareWon)
        sb.append(",\"e\":").append(me.expenseShareWon)
        sb.append(",\"pd\":").append(me.paidWon)
        sb.append(",\"b\":").append(me.balanceWon)
        sb.append(",\"x\":[")
        sb.append(mine.joinToString(",") { t ->
            val sending = t.from.id == memberId
            val other = if (sending) t.to else t.from
            // 토스 링크는 이 사람이 차 주인(토스 아이디 주인)에게 보낼 때만 붙습니다.
            val link = if (sending && toss != null && other.id == driverId) ",${str("https://toss.me/$toss/${t.amountWon}")}" else ""
            "[${str(other.name)},${t.amountWon},${if (sending) 1 else -1}$link]"
        })
        sb.append("]}")
        return sb.toString()
    }

    /** 뷰어 주소가 없을 때 QR에 담는 한 사람용 텍스트. */
    fun personalText(s: JourneySummary, memberId: String): String? {
        val me = s.people.firstOrNull { it.member.id == memberId } ?: return null
        val mine = s.transfers.filter { it.from.id == memberId || it.to.id == memberId }
        return buildString {
            append("[같이 탄 만큼] ${s.journey.title} · ${me.member.name}님\n")
            append("탑승 ${Formatting.km(me.distanceKm)} · 차량 ${Formatting.won(me.fuelShareWon)}")
            if (me.expenseShareWon > 0) append(" · 경비 ${Formatting.won(me.expenseShareWon)}")
            append('\n')
            if (mine.isEmpty()) append("주고받을 돈이 없어요.")
            mine.forEach { t ->
                if (t.from.id == memberId) append("${t.to.name}님께 ${Formatting.won(t.amountWon)} 보내기\n")
                else append("${t.from.name}님께 ${Formatting.won(t.amountWon)} 받기\n")
            }
        }.trimEnd()
    }

    private fun memberOrder(s: JourneySummary): List<Member> = s.people.map { it.member }

    internal fun json(s: JourneySummary, tossId: String?, detail: Int): String {
        val members = memberOrder(s)
        val idx = members.withIndex().associate { (i, m) -> m.id to i }
        val sb = StringBuilder()
        sb.append("{\"v\":1,\"t\":").append(str(s.journey.title))
        sb.append(",\"m\":[").append(members.joinToString(",") { str(it.name) }).append(']')
        sb.append(",\"d\":[")
        sb.append(s.days.joinToString(",") { d ->
            val route = when (detail) {
                2 -> d.route
                1 -> if (d.route.size <= 3) d.route else listOf(d.route.first(), d.route[d.route.size / 2], d.route.last())
                else -> emptyList()
            }
            "{\"n\":${d.dayNumber},\"dt\":\"${d.date}\",\"k\":${num(d.distanceKm)},\"c\":${d.costWon}," +
                "\"r\":[${route.joinToString(",") { str(it.take(24)) }}]}"
        })
        sb.append("],\"p\":[")
        sb.append(s.people.joinToString(",") { p ->
            "[${idx.getValue(p.member.id)},${num(p.distanceKm)},${p.fuelShareWon},${p.expenseShareWon},${p.paidWon},${p.balanceWon}]"
        })
        sb.append("],\"x\":[")
        sb.append(s.transfers.joinToString(",") { "[${idx[it.from.id]},${idx[it.to.id]},${it.amountWon}]" })
        sb.append("],\"e\":[")
        sb.append(s.journey.expenses.joinToString(",") { "[${str(it.label)},${it.amountWon},${idx[it.payerId] ?: -1}]" })
        sb.append("],\"tk\":${num(s.totalKm)},\"tf\":${s.totalFuelWon},\"te\":${s.totalExpenseWon}")
        tossId?.trim()?.trimStart('@')?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{2,40}")) }?.let { id ->
            // 운전자(차 주인)의 토스 아이디. 운전자에게 보내는 송금에 링크 버튼이 생깁니다.
            val driverIdx = s.journey.trips.firstOrNull()?.driverId?.let { idx[it] }
            sb.append(",\"pay\":{\"toss\":").append(str(id)).append(",\"to\":").append(driverIdx ?: -1).append('}')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun num(d: Double) = String.format(Locale.ROOT, "%.1f", d)

    private fun str(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when {
                c == '"' -> append("\\\""); c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t")
                c < ' ' -> append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    internal fun deflate(bytes: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true).apply { setInput(bytes); finish() }
        val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    internal fun inflate(bytes: ByteArray): ByteArray {
        val i = Inflater(true).apply { setInput(bytes) }
        val out = ByteArrayOutputStream(); val buf = ByteArray(1024)
        while (!i.finished()) { val n = i.inflate(buf); if (n == 0 && i.needsInput()) break; out.write(buf, 0, n) }
        i.end()
        return out.toByteArray()
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    internal fun fromBase64Url(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    /** 텍스트 요약(클립보드 복사·뷰어 주소가 없을 때 QR용). */
    fun text(s: JourneySummary): String = buildString {
        append("[같이 탄 만큼] ${s.journey.title}\n")
        append("총 ${Formatting.km(s.totalKm)} · 차량 ${Formatting.won(s.totalFuelWon)}")
        if (s.totalExpenseWon > 0) append(" · 공동경비 ${Formatting.won(s.totalExpenseWon)}")
        append('\n')
        s.days.forEach { d -> append("${d.dayNumber}일차 ${Formatting.km(d.distanceKm)} ${d.route.joinToString(" → ")}\n") }
        if (s.transfers.isEmpty()) append("주고받을 돈이 없어요.")
        else append("송금 ${s.transfers.size}번으로 정리\n")
        s.transfers.forEach { t -> append("· ${t.from.name} → ${t.to.name} ${Formatting.won(t.amountWon)}\n") }
    }.trimEnd()
}
