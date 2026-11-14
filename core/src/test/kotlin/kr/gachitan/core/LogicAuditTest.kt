package kr.gachitan.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 무작위 운행 수천 건으로 돈과 관련된 규칙이 깨지지 않는지 확인합니다.
 * 승하차·정차·통행료·효율 보고·잔량 변화·이름 붙이기·설정(운전자 포함/제외, 반올림 단위)을 섞습니다.
 */
class LogicAuditTest {
    private data class Run(val record: TripRecord, val journal: TripJournal, val config: PricingConfig)

    private fun randomRun(rnd: Random): Run? {
        val driver = Rider("driver", "나")
        val journal = TripJournal(driver)
        var t = 1_000L
        var km = rnd.nextDouble(0.0, 50_000.0)
        var soc = rnd.nextDouble(30.0, 100.0)
        fun ev(e: VehicleEvent) = journal.apply(TripCommand.Event(e))
        ev(VehicleEvent.OdometerChanged(km, t))
        ev(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, soc, t))
        val names = listOf("민지", "준호", "서연", "지훈", "하늘", "도윤")
        // 처음 탑승
        SeatPosition.passengerSeats.forEach { seat ->
            if (rnd.nextBoolean()) {
                ev(VehicleEvent.SeatOccupancyChanged(seat, true, ++t))
                if (rnd.nextInt(3) > 0) journal.apply(TripCommand.Assign(seat, Rider(names.random(rnd), names.random(rnd))))
            }
        }
        ev(VehicleEvent.GearChanged(Gear.DRIVE, ++t))
        repeat(rnd.nextInt(1, 7)) {
            // 달리기
            repeat(rnd.nextInt(1, 4)) {
                km += rnd.nextDouble(0.0, 40.0); t += 60_000
                ev(VehicleEvent.OdometerChanged(km, t))
                if (rnd.nextInt(4) == 0) { soc = (soc - rnd.nextDouble(0.0, 6.0)).coerceAtLeast(0.0); ev(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, soc, t)) }
                if (rnd.nextInt(6) == 0) ev(VehicleEvent.EfficiencyReported(EnergyKind.ELECTRIC, rnd.nextDouble(3.0, 8.0), t))
                if (rnd.nextInt(10) == 0) ev(VehicleEvent.OdometerChanged(km - 5, t + 1)) // 역행 오류
            }
            ev(VehicleEvent.GearChanged(Gear.PARK, ++t))
            if (rnd.nextInt(3) == 0) journal.apply(TripCommand.AddExtra("통행료", rnd.nextLong(500, 30_000)))
            if (rnd.nextInt(8) == 0) { soc = (soc + rnd.nextDouble(5.0, 40.0)).coerceAtMost(100.0); ev(VehicleEvent.EnergyLevelChanged(EnergyKind.ELECTRIC, soc, ++t)) }
            // 승하차
            SeatPosition.passengerSeats.forEach { seat ->
                if (rnd.nextInt(3) == 0) {
                    val on = rnd.nextBoolean()
                    ev(VehicleEvent.SeatOccupancyChanged(seat, on, ++t))
                    if (on && rnd.nextBoolean()) journal.apply(TripCommand.Assign(seat, Rider(names.random(rnd), names.random(rnd))))
                }
            }
            ev(VehicleEvent.GearChanged(Gear.DRIVE, ++t))
        }
        km += rnd.nextDouble(0.0, 30.0); t += 60_000
        ev(VehicleEvent.OdometerChanged(km, t))
        ev(VehicleEvent.GearChanged(Gear.PARK, ++t))
        if (!journal.recorder.canFinish()) return null
        val config = PricingConfig(
            energyKind = EnergyKind.ELECTRIC,
            unitPriceWon = listOf(0.0, 200.0, 350.0, 555.5).random(rnd),
            capacityUnits = listOf(null, 58.0, 77.4).random(rnd),
            fallbackKmPerUnit = rnd.nextDouble(2.0, 9.0),
            driverPaysShare = rnd.nextBoolean(),
            roundingUnitWon = listOf(1L, 10L, 100L, 1000L).random(rnd),
            wearWonPerKm = listOf(0.0, 0.0, 30.0, 55.5).random(rnd)
        )
        val encoded = journal.encode()
        return Run(journal.recorder.finish(), TripJournal.decode(encoded), config)
    }

    @Test
    fun `무작위 운행 3천 건에서 정산 규칙이 깨지지 않는다`() {
        val rnd = Random(20261114)
        var checked = 0
        repeat(3_000) { i ->
            val run = randomRun(rnd) ?: return@repeat
            val r = run.record; val c = run.config
            val s = SettlementCalculator.settle(r, c)
            val tag = "case $i"

            // 1. 총액 보존: 사람별 금액의 합 = 에너지비 + 입력 비용 (원 단위 반올림)
            assertEquals("$tag 총액", Math.round(s.tripCost.totalWon + s.extrasWon), s.grandTotalWon)
            // 1-1. 동승자 합계는 실제 총액을 넘지 않는다(운전자 부담이 마이너스가 되지 않음)
            assertTrue("$tag 운전자 마이너스 ${s.driverShare.amountWon}", s.driverShare.amountWon >= 0)
            // 2. 구간 거리 합 = 운행 거리
            assertEquals("$tag 구간 합", r.distanceKm, r.segments.sumOf { it.distanceKm }, 1e-6)
            // 3. 동승자 금액은 음수가 아니고, 정확한 몫과 반올림 단위의 절반 이내
            s.shares.filter { !it.isDriver }.forEach { sh ->
                assertTrue("$tag 동승자 음수 ${sh.amountWon}", sh.amountWon >= 0)
                // 올림은 단위의 절반까지, 내림은 (총액 초과를 막으려고) 한 단위까지 허용
                assertTrue("$tag 반올림 오차", sh.amountWon - sh.exactWon <= c.roundingUnitWon / 2.0 + 1e-6)
                assertTrue("$tag 내림 오차", sh.exactWon - sh.amountWon <= c.roundingUnitWon + 1e-6)
                assertTrue("$tag 탄 거리 초과", sh.distanceKm <= r.distanceKm + 1e-6)
            }
            // 4. 운전자 제외 옵션이면 운전자 정확한 몫은 '아무도 없던 구간' 비용뿐
            if (!c.driverPaysShare) {
                val alone = s.segmentCharges.filter { it.payerIds == setOf(r.driver.id) }.sumOf { it.costWon }
                val aloneExtras = s.shares.first { it.isDriver }.extrasWon
                assertEquals("$tag 운전자 제외", alone + aloneExtras, s.driverShare.exactWon, 1e-6)
            }
            // 5. 같은 구간을 더 많이 탄 사람(구간 집합의 상위집합)이 덜 내지 않는다(통행료 없는 경우)
            if (r.extras.isEmpty()) {
                val segsOf = s.shares.associate { sh -> sh.rider.id to r.segments.indices.filter { sh.rider.id in r.segments[it].occupantIds }.toSet() }
                s.shares.filter { !it.isDriver }.forEach { a -> s.shares.filter { !it.isDriver && it !== a }.forEach { b ->
                    val sa = segsOf.getValue(a.rider.id); val sb = segsOf.getValue(b.rider.id)
                    if (sa.containsAll(sb)) assertTrue("$tag 단조성 ${a.exactWon} < ${b.exactWon}", a.exactWon >= b.exactWon - 1e-6)
                } }
            }
            // 6. 저장했다 복원한 기록장으로 다시 계산해도 같은 결과
            val replay = SettlementCalculator.settle(run.journal.recorder.finish(), c)
            assertEquals("$tag 복원", s.shares.map { it.rider.id to it.amountWon }, replay.shares.map { it.rider.id to it.amountWon })
            // 7. 기록(TripLog) 저장·복원
            val log = TripLog.from(s, "id$i", null)
            val back = TripLogCodec.decode(TripLogCodec.encode(listOf(log))).single()
            assertEquals("$tag 기록 복원", log.shares.map { it.amountWon }, back.shares.map { it.amountWon })
            assertEquals("$tag 기록 총액", log.totalWon, back.shares.sumOf { it.amountWon })
            checked++
        }
        assertTrue("검사한 운행이 너무 적음: $checked", checked > 2_500)
    }

    @Test
    fun `반올림 때문에 동승자가 실제 비용보다 많이 내지 않는다`() {
        val driver = Rider("driver", "나")
        val riders = (1..3).map { Rider("p$it", "p$it") }
        val rec = TripRecord(driver, riders, listOf(Segment(0.0, 20.0, (riders.map { it.id } + driver.id).toSet())),
            0.0, 20.0, 0, 1, emptyMap(), emptyMap(), extras = emptyList())
        // km당 70원 × 20km = 1,400원, 동승자만 3명: 정확히는 466.67원씩
        val s = SettlementCalculator.settle(rec, PricingConfig(EnergyKind.ELECTRIC, 350.0, null, 5.0, driverPaysShare = false, roundingUnitWon = 10))
        assertEquals(1_400L, s.grandTotalWon)
        assertTrue(s.payers.sumOf { it.amountWon } <= 1_400L)
        assertTrue(s.driverShare.amountWon >= 0)
        assertEquals(listOf(460L, 470L, 470L), s.payers.map { it.amountWon }.sorted())
        assertEquals(0L, s.driverShare.amountWon)
    }

    @Test
    fun `여행 정산에서 받는 사람은 반올림으로 실제보다 더 받지 않는다`() {
        val rnd = Random(3)
        repeat(3_000) {
            val n = rnd.nextInt(2, 7); val ids = (0 until n).map { "p$it" }
            val exact = ids.associateWith { 0.0 }.toMutableMap()
            ids.drop(1).forEach { val v = rnd.nextDouble(0.0, 30_000.0); exact[it] = -v; exact["p0"] = exact.getValue("p0") + v }
            val unit = listOf(1L, 10L, 100L).random(rnd)
            val b = JourneyCalculator.roundBalances(exact, unit)
            assertEquals(0L, b.values.sum())
            assertTrue("받는 사람이 더 받음", b.getValue("p0") <= Math.round(exact.getValue("p0")))
            ids.drop(1).forEach { id ->
                assertTrue("보낼 돈은 0 이하", b.getValue(id) <= 0)
                assertTrue("보낼 사람 과다 청구", -b.getValue(id) <= -exact.getValue(id) + unit / 2.0 + 1e-6)
            }
        }
    }
}

class WearCostTest {
    private val driver = Rider("driver", "나")
    private val minji = Rider("m", "민지")
    private fun record(km: Double) = TripRecord(driver, listOf(minji),
        listOf(Segment(0.0, km, setOf(driver.id, minji.id))), 0.0, km, 0, 1, emptyMap(), emptyMap(), extras = emptyList())

    @Test
    fun `유지비는 거리만큼 더해지고 에너지비와 같은 방식으로 나눈다`() {
        val base = PricingConfig(EnergyKind.ELECTRIC, 350.0, null, 5.0, roundingUnitWon = 1)
        val plain = SettlementCalculator.settle(record(40.0), base)
        val worn = SettlementCalculator.settle(record(40.0), base.copy(wearWonPerKm = 50.0))
        assertEquals(2_800L, plain.grandTotalWon)            // 40km ÷ 5 × 350
        assertEquals(4_800L, worn.grandTotalWon)             // + 40km × 50
        assertEquals(2_000L, worn.wearWon)
        assertEquals(2_400L, worn.shares.first { !it.isDriver }.amountWon)
        val log = TripLog.from(worn, "w", null)
        assertEquals(2_800L, log.fuelOnlyWon)
        val back = TripLogCodec.decode(TripLogCodec.encode(listOf(log))).single()
        assertEquals(2_000L, back.wearWon)
    }

    @Test
    fun `유지비 칸이 없는 예전 기록도 읽는다`() {
        val log = TripLog.from(SettlementCalculator.settle(record(10.0), PricingConfig(EnergyKind.ELECTRIC, 350.0, null, 5.0)), "o", null)
        val old = TripLogCodec.encode(listOf(log)).lines().joinToString("\n") { if (it.startsWith("T|")) it.substringBeforeLast("|") else it }
        assertEquals(0L, TripLogCodec.decode(old).single().wearWon)
    }
}

class PaidMarkTest {
    @Test
    fun `받음 표시는 저장·복원되고 남은 금액이 줄어든다`() {
        val driver = Rider("driver", "나"); val a = Rider("m,1", "민지|a"); val b = Rider("j", "준호")
        val rec = TripRecord(driver, listOf(a, b), listOf(Segment(0.0, 30.0, setOf(driver.id, a.id, b.id))),
            0.0, 30.0, 0, 1, emptyMap(), emptyMap(), extras = emptyList())
        var log = TripLog.from(SettlementCalculator.settle(rec, PricingConfig(EnergyKind.ELECTRIC, 350.0, null, 5.0)), "t", null)
        assertEquals(log.payers.sumOf { it.amountWon }, log.unpaidWon)
        log = log.withPaid(a.id, true)
        assertEquals(log.payers.first { it.riderId == b.id }.amountWon, log.unpaidWon)
        val back = TripLogCodec.decode(TripLogCodec.encode(listOf(log))).single()
        assertEquals(setOf(a.id), back.paidIds)
        assertTrue(!back.allPaid)
        assertTrue(back.withPaid(b.id, true).allPaid)
        assertEquals(0L, back.withPaid(b.id, true).unpaidWon)
    }
}
