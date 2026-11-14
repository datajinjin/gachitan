package kr.gachitan.core

/**
 * 차량 이벤트를 받아 "누가 몇 km부터 몇 km까지 탔는지"를 기록합니다.
 *
 * 설계 원칙
 * - 센서가 아는 것과 사람이 아는 것을 나눕니다. 좌석 센서는 '앉았다/비었다'만 알려 주므로
 *   한 번 앉아 있던 기간을 [Sitting]으로 기록하고, 이름은 그 기간에만 붙입니다.
 *   그래서 같은 좌석에 다른 사람이 앉아도 앞사람의 기록이 바뀌지 않습니다.
 * - 구간 경계는 탑승 구성이 바뀐 순간의 계기판 값입니다. 시간 기반 디바운스가 없어서
 *   같은 이벤트를 넣으면 언제나 같은 결과가 나옵니다.
 * - 주행 중 센서가 잠깐 깜빡인 것(정차 없이 [FLICKER_KM] 안에서 다시 앉음)은 같은 사람으로 봅니다.
 *   정차(P) 뒤 같은 좌석에 다시 앉으면 앞사람 이름을 제안하되 '확인 필요'로 표시합니다.
 * - 기어 P는 '운행 종료'가 아니라 '일시 정차'입니다.
 */
class TripRecorder(val driver: Rider) {
    enum class State { IDLE, WAITING_FOR_ODOMETER, DRIVING, PAUSED, FINISHED }

    /** 화면과 정산 확인에 쓰는 앉은 기간 정보. */
    data class SittingView(
        val id: String,
        val seat: SeatPosition,
        val rider: Rider?,
        val startKm: Double?,
        val endKm: Double?,
        val open: Boolean,
        /** 정차 후 같은 좌석에 다시 앉아 앞사람 이름을 제안한 경우. 사람이 확인해야 합니다. */
        val needsReview: Boolean,
        /** 이름이 없을 때 쓰는 이름(예: "조수석 동승자 2"). */
        val fallbackName: String
    ) {
        val displayName: String get() = rider?.name ?: fallbackName
        val distanceKm: Double get() = if (startKm == null) 0.0 else ((endKm ?: startKm) - startKm).coerceAtLeast(0.0)
    }

    private class Sitting(
        val id: String,
        val seat: SeatPosition,
        var riderId: String?,
        var startKm: Double?,
        var endKm: Double?,
        var open: Boolean,
        var needsReview: Boolean,
        val ordinalInSeat: Int
    )

    private class Vacated(val sittingId: String, val riderId: String?, val km: Double?, var parkSeen: Boolean)

    var state: State = State.IDLE
        private set

    private val riders = linkedMapOf<String, Rider>(driver.id to driver)
    private val sittings = mutableListOf<Sitting>()
    private val seatSitting = mutableMapOf<SeatPosition, String>()
    /** 사람이 빈 좌석에 미리 정한 이름(다음 한 번 그대로 씀). */
    private val pendingAssign = mutableMapOf<SeatPosition, Rider>()
    /** 출발 전 내렸던 사람의 이름(다음에 앉는 사람에게 '확인 필요'로 제안). */
    private val pendingSuggest = mutableMapOf<SeatPosition, Rider>()
    private val lastVacated = mutableMapOf<SeatPosition, Vacated>()
    private var sittingCounter = 0

    /** 닫힌 구간. occupantIds는 운전자 id와 sitting id입니다(이름은 정산 때 붙입니다). */
    private val closedSegments = mutableListOf<Segment>()
    private var openSegmentStartKm: Double? = null
    private var openOccupants: Set<String> = emptySet()

    var currentKm: Double? = null
        private set
    private var startKm: Double? = null
    private var startedAtMillis: Long? = null
    var lastEventMillis: Long = 0
        private set

    // 에너지: 시작·마지막 잔량, 기록 중 감소량 합계(충전으로 늘어난 양은 빼지 않습니다), 효율 보고 이력.
    private val energyStart = mutableMapOf<EnergyKind, Double>()
    private val energyLatest = mutableMapOf<EnergyKind, Double>()
    private val energyUsed = mutableMapOf<EnergyKind, Double>()
    private val energyRecharged = mutableSetOf<EnergyKind>()
    private val efficiencyLatest = mutableMapOf<EnergyKind, Double>()
    private val efficiencySamples = mutableMapOf<EnergyKind, MutableList<EfficiencySample>>()

    private val extras = mutableListOf<ExtraCost>()
    private val gaps = mutableListOf<RecordGap>()

    // 장소
    private var latestPlace: Place? = null
    private var latestPlaceAt: Long = Long.MIN_VALUE
    private var legStartMillis: Long = Long.MIN_VALUE
    private var startPlace: Place? = null
    private val stops = mutableListOf<Stop>()

    // ---------------------------------------------------------------- 이름 붙이기

    /**
     * 좌석에 사람을 정합니다.
     * - 지금 누가 앉아 있으면: 그 사람이 앉아 있는 기간에만 이름을 붙입니다(과거 다른 사람의 기록은 그대로).
     * - 비어 있으면: 다음에 그 좌석에 앉는 한 번에만 이 이름을 씁니다.
     */
    fun assign(seat: SeatPosition, rider: Rider) {
        require(!seat.isDriver) { "운전석은 운전자 고정입니다." }
        require(rider.id != driver.id) { "운전자는 동승자 좌석에 배정할 수 없습니다." }
        check(state != State.FINISHED) { "끝난 운행은 수정할 수 없습니다." }
        riders[rider.id] = rider
        val open = seatSitting[seat]?.let(::sitting)
        if (open != null) nameSittingInternal(open, rider) else { pendingAssign[seat] = rider; pendingSuggest.remove(seat) }
    }

    /** 좌석의 이름을 지웁니다. 앉아 있는 사람은 '이름 없음'이 됩니다. */
    fun unassign(seat: SeatPosition) {
        check(state != State.FINISHED) { "끝난 운행은 수정할 수 없습니다." }
        pendingAssign.remove(seat)
        pendingSuggest.remove(seat)
        seatSitting[seat]?.let(::sitting)?.let { it.riderId = null; it.needsReview = false }
    }

    /** 정산 확인 화면에서 이미 내린 사람을 포함해 앉은 기간 하나에 이름을 붙입니다. null이면 이름을 지웁니다. */
    fun nameSitting(sittingId: String, rider: Rider?) {
        check(state != State.FINISHED) { "끝난 운행은 수정할 수 없습니다." }
        val s = sitting(sittingId) ?: return
        if (rider == null) { s.riderId = null; s.needsReview = false; return }
        require(rider.id != driver.id) { "운전자는 동승자 좌석에 배정할 수 없습니다." }
        riders[rider.id] = rider
        nameSittingInternal(s, rider)
    }

    /** '확인 필요'로 제안된 이름이 맞다고 확인합니다. */
    fun confirmSitting(sittingId: String) {
        sitting(sittingId)?.needsReview = false
    }

    private fun nameSittingInternal(target: Sitting, rider: Rider) {
        // 한 사람은 같은 순간 두 좌석에 앉을 수 없습니다. 겹치는 다른 기간에서는 이름을 떼고 확인을 요청합니다.
        sittings.filter { it !== target && it.riderId == rider.id && overlaps(it, target) }
            .forEach { it.riderId = null; it.needsReview = true }
        target.riderId = rider.id
        target.needsReview = false
    }

    private fun overlaps(a: Sitting, b: Sitting): Boolean {
        if (a.open && b.open) return true
        val now = currentKm ?: 0.0
        val aStart = a.startKm ?: now; val aEnd = if (a.open) Double.MAX_VALUE else (a.endKm ?: aStart)
        val bStart = b.startKm ?: now; val bEnd = if (b.open) Double.MAX_VALUE else (b.endKm ?: bStart)
        return aStart < bEnd && bStart < aEnd
    }

    fun assignments(): Map<SeatPosition, Rider> {
        val seated = seatSitting.mapNotNull { (seat, id) -> sitting(id)?.riderId?.let(riders::get)?.let { seat to it } }.toMap()
        return pendingAssign.filterKeys { it !in seatSitting } + seated
    }

    // ---------------------------------------------------------------- 이벤트

    fun onEvent(event: VehicleEvent) {
        if (state == State.FINISHED) return
        lastEventMillis = maxOf(lastEventMillis, event.atMillis)
        when (event) {
            is VehicleEvent.OdometerChanged -> onOdometer(event)
            is VehicleEvent.GearChanged -> onGear(event)
            is VehicleEvent.SeatOccupancyChanged -> onSeat(event)
            is VehicleEvent.EnergyLevelChanged -> onEnergy(event)
            is VehicleEvent.EfficiencyReported -> onEfficiency(event)
            is VehicleEvent.PlaceObserved -> onPlace(event)
        }
    }

    /**
     * 앱(프로세스)이 다시 시작됐음을 알립니다. 기록 중이었다면 그동안을 '기록 공백'으로 남깁니다.
     * 저장된 기록은 복원되지만, 꺼져 있던 동안의 승하차는 알 수 없습니다.
     */
    fun onResume(atMillis: Long) {
        if (!isRecording()) return
        if (atMillis > lastEventMillis) gaps += RecordGap(lastEventMillis, atMillis, currentKm)
        lastEventMillis = maxOf(lastEventMillis, atMillis)
    }

    private fun onOdometer(event: VehicleEvent.OdometerChanged) {
        val previous = currentKm
        // 계기판은 줄어들지 않습니다. 역행하는 값은 센서 오류로 보고 무시합니다.
        if (previous != null && event.km < previous) return
        currentKm = event.km
        if (state == State.WAITING_FOR_ODOMETER) beginRecording(event.atMillis)
    }

    private fun onGear(event: VehicleEvent.GearChanged) {
        val moving = event.gear == Gear.DRIVE || event.gear == Gear.REVERSE
        when (state) {
            State.IDLE -> if (moving) {
                if (currentKm == null) state = State.WAITING_FOR_ODOMETER
                else beginRecording(event.atMillis)
            }
            State.PAUSED -> if (moving) {
                state = State.DRIVING
                legStartMillis = event.atMillis
            }
            State.DRIVING -> if (event.gear == Gear.PARK) {
                state = State.PAUSED
                lastVacated.values.forEach { it.parkSeen = true }
                // 이번 주행 구간 동안 받은 장소(예: 내비 도착)만 정차 지점에 붙입니다.
                val place = latestPlace?.takeIf { latestPlaceAt >= legStartMillis }
                stops += Stop(currentKm ?: startKm ?: 0.0, event.atMillis, place)
            }
            State.WAITING_FOR_ODOMETER -> if (event.gear == Gear.PARK) state = State.IDLE
            State.FINISHED -> Unit
        }
    }

    private fun onSeat(event: VehicleEvent.SeatOccupancyChanged) {
        if (event.seat.isDriver) return // 운전자는 운행 내내 탑승한 것으로 봅니다.
        val seat = event.seat
        val openId = seatSitting[seat]
        if (event.occupied == (openId != null)) return

        if (event.occupied) {
            val vac = lastVacated[seat]
            val flicker = vac != null && !vac.parkSeen && isRecording() &&
                (currentKm ?: 0.0) - (vac.km ?: 0.0) < FLICKER_KM && sittings.lastOrNull { it.seat == seat }?.id == vac.sittingId
            if (flicker) {
                // 주행 중 센서 깜빡임: 같은 사람이 계속 앉아 있던 것으로 봅니다.
                val s = sitting(vac!!.sittingId)!!
                s.open = true; s.endKm = null
                seatSitting[seat] = s.id
            } else {
                val pending = pendingAssign.remove(seat)
                val remembered = pendingSuggest.remove(seat)?.id
                val suggested = if (pending != null) null else remembered ?: if (vac?.parkSeen == true) vac.riderId else null
                val s = Sitting(
                    id = "s${++sittingCounter}",
                    seat = seat,
                    riderId = pending?.id ?: suggested,
                    startKm = if (isRecording()) currentKm else null,
                    endKm = null,
                    open = true,
                    needsReview = suggested != null,
                    ordinalInSeat = sittings.count { it.seat == seat } + 1
                )
                sittings += s
                seatSitting[seat] = s.id
            }
            lastVacated.remove(seat)
        } else {
            val s = sitting(openId!!)!!
            seatSitting.remove(seat)
            s.open = false
            if (s.startKm == null) {
                // 출발 전에 앉았다 내린 사람: 기록에 남기지 않습니다. 이름은 다음에 앉는 사람에게 '확인 필요'로 제안합니다.
                sittings.remove(s)
                s.riderId?.let { id -> riders[id]?.let { pendingSuggest[seat] = it } }
            } else {
                s.endKm = currentKm
                lastVacated[seat] = Vacated(s.id, s.riderId, currentKm, parkSeen = state == State.PAUSED)
            }
        }
        if (isRecording()) cutSegment()
    }

    private fun onEnergy(event: VehicleEvent.EnergyLevelChanged) {
        val previous = energyLatest[event.kind]
        energyLatest[event.kind] = event.percent
        if (!isRecording()) return
        energyStart.putIfAbsent(event.kind, event.percent)
        if (previous != null) {
            val delta = previous - event.percent
            if (delta > 0) energyUsed[event.kind] = (energyUsed[event.kind] ?: 0.0) + delta
            else if (delta < -RECHARGE_THRESHOLD_PERCENT) energyRecharged += event.kind
        }
    }

    private fun onEfficiency(event: VehicleEvent.EfficiencyReported) {
        if (event.kmPerUnit <= 0 || !event.kmPerUnit.isFinite()) return
        efficiencyLatest[event.kind] = event.kmPerUnit
        if (isRecording()) addEfficiencySample(event.kind, event.kmPerUnit)
    }

    private fun addEfficiencySample(kind: EnergyKind, kmPerUnit: Double) {
        val at = currentKm ?: startKm ?: return
        val list = efficiencySamples.getOrPut(kind) { mutableListOf() }
        if (list.lastOrNull()?.kmPerUnit == kmPerUnit) return
        // 같은 km에서 다시 보고되면 마지막 값으로 바꿉니다.
        if (list.lastOrNull()?.atKm == at) list[list.lastIndex] = EfficiencySample(at, kmPerUnit)
        else list += EfficiencySample(at, kmPerUnit)
    }

    private fun onPlace(event: VehicleEvent.PlaceObserved) {
        val p = event.place
        if (p.name != null && p.hasCoordinates) {
            for (i in stops.indices) {
                val sp = stops[i].place
                if (sp != null && sp.name == null && sp.isNear(p)) stops[i] = stops[i].copy(place = sp.mergedWith(p))
            }
            startPlace?.let { sp -> if (sp.name == null && sp.isNear(p)) startPlace = sp.mergedWith(p) }
        }
        when (state) {
            State.PAUSED -> {
                val last = stops.lastOrNull() ?: return
                val lp = last.place
                if (lp == null || !lp.hasCoordinates || !p.hasCoordinates || lp.isNear(p)) {
                    stops[stops.lastIndex] = last.copy(place = lp?.mergedWith(p) ?: p)
                }
            }
            State.DRIVING -> {
                val nearKnown = (listOfNotNull(startPlace) + stops.mapNotNull { it.place }).any { it.isNear(p) }
                when {
                    nearKnown -> Unit
                    startPlace == null && stops.isEmpty() && p.source == PlaceSource.LOCATION &&
                        event.atMillis - legStartMillis <= START_FIX_WINDOW_MS -> startPlace = p
                    else -> { latestPlace = p; latestPlaceAt = event.atMillis }
                }
            }
            State.IDLE, State.WAITING_FOR_ODOMETER -> {
                latestPlace = latestPlace?.takeIf { it.isNear(p) || !p.hasCoordinates }?.mergedWith(p) ?: p
                latestPlaceAt = event.atMillis
            }
            State.FINISHED -> Unit
        }
    }

    /** 정차 지점(또는 출발지, index = -1)에 사람이 직접 이름을 붙입니다. */
    fun labelStop(index: Int, name: String) {
        val label = name.trim().take(30).ifEmpty { return }
        if (index == -1) {
            startPlace = (startPlace ?: Place(source = PlaceSource.MANUAL)).copy(name = label, source = PlaceSource.MANUAL)
            return
        }
        val stop = stops.getOrNull(index) ?: return
        stops[index] = stop.copy(place = (stop.place ?: Place(source = PlaceSource.MANUAL)).copy(name = label, source = PlaceSource.MANUAL))
    }

    fun stops(): List<Stop> = stops.toList()
    fun startPlace(): Place? = startPlace

    // ---------------------------------------------------------------- 사람이 입력하는 확정 비용

    /**
     * 통행료·주차비를 추가합니다. [payerKeys]가 비어 있으면 '방금 달려온 구간'(정차 직전 마지막 주행 구간)에
     * 타고 있던 사람이 나눕니다. 톨게이트를 지난 뒤 정차해서 입력해도, 정차 후 내린 사람이 빠지지 않습니다.
     * 키는 [participantKeys]가 돌려주는 값(운전자 id, rider id, sitting id)입니다.
     */
    fun addExtraCost(label: String, amountWon: Long, payerKeys: Set<String> = emptySet()) {
        require(amountWon > 0) { "금액은 0보다 커야 합니다." }
        check(isRecording()) { "운행 중에만 추가할 수 있습니다." }
        val keys = payerKeys.ifEmpty { defaultExtraPayerKeys() }
        extras += ExtraCost(label, amountWon, currentKm ?: startKm ?: 0.0, keys)
    }

    fun removeExtraCost(index: Int) {
        if (index in extras.indices) extras.removeAt(index)
    }

    fun setExtraPayers(index: Int, payerKeys: Set<String>) {
        val e = extras.getOrNull(index) ?: return
        if (payerKeys.isNotEmpty()) extras[index] = e.copy(payerIds = payerKeys)
    }

    fun extras(): List<ExtraCost> = extras.toList()

    /** 방금 달려온 구간의 탑승자(운전자 포함). 정차 뒤 내린 사람도 포함됩니다. */
    fun defaultExtraPayerKeys(): Set<String> {
        val all = closedSegments + listOfNotNull(openSegmentPreview())
        val driven = all.lastOrNull { it.distanceKm > 0.0 } ?: all.lastOrNull()
        return (driven?.occupantIds ?: setOf(driver.id)).map(::keyOf).toSet()
    }

    /** 비용을 나눌 수 있는 사람 목록(화면의 선택지). 키 → 표시 이름. */
    fun participantKeys(): Map<String, String> {
        val result = linkedMapOf(driver.id to driver.name)
        val seenRiders = mutableSetOf<String>()
        sittings.filter { it.startKm != null }.forEach { s ->
            // 같은 사람이 두 번 앉았으면 한 번만 보여 줍니다. 키는 첫 앉은 기간 id입니다.
            if (riderIdForRecord(s.id) in seenRiders) return@forEach
            seenRiders += riderIdForRecord(s.id)
            result[s.id] = viewOf(s).displayName
        }
        return result
    }

    /**
     * 키는 sitting id로 저장하고 이름은 정산할 때 붙입니다(나중에 이름을 고쳐도 따라갑니다).
     * 같은 사람이 두 번 앉았으면 첫 앉은 기간 id로 맞춰, 화면의 선택지와 같은 키가 되게 합니다.
     */
    fun keyOf(id: String): String {
        if (id == driver.id) return id
        val rider = riderIdForRecord(id)
        return sittings.firstOrNull { it.startKm != null && riderIdForRecord(it.id) == rider }?.id ?: id
    }

    // ---------------------------------------------------------------- 종료

    fun canFinish(): Boolean = isRecording()

    fun finish(): TripRecord {
        check(isRecording()) { "기록 중인 운행이 없습니다." }
        val record = buildRecord(closeOpen = true)
        state = State.FINISHED
        return record
    }

    /** 끝내지 않고 지금까지의 기록으로 만든 임시 기록(정산 확인 화면의 미리보기). */
    fun preview(): TripRecord? = if (isRecording()) buildRecord(closeOpen = false) else null

    private fun buildRecord(closeOpen: Boolean): TripRecord {
        if (closeOpen) closeOpenSegment()
        val now = currentKm ?: startKm ?: 0.0
        val allSegments = (if (closeOpen) closedSegments else closedSegments + listOfNotNull(openSegmentPreview()))
        val riderOf = { id: String -> if (id == driver.id) driver.id else riderIdForRecord(id) }
        val segments = allSegments.filter { it.distanceKm > 0.0 }
            .map { seg -> seg.copy(occupantIds = seg.occupantIds.map(riderOf).toSet()) }
        val recordedSittings = sittings.filter { it.startKm != null }.map { s ->
            SittingRecord(s.id, s.seat, riderIdForRecord(s.id), s.startKm!!, if (s.open) now else (s.endKm ?: now))
        }.filter { it.distanceKm > 0.0 }
        val usedIds = segments.flatMap { it.occupantIds }.toSet() + driver.id
        val ridersOut = linkedMapOf(driver.id to driver)
        sittings.filter { it.startKm != null }.forEach { s ->
            val id = riderIdForRecord(s.id)
            if (id in usedIds) ridersOut.putIfAbsent(id, Rider(id, viewOf(s).displayName))
        }
        val extrasOut = extras.map { e ->
            e.copy(payerIds = e.payerIds.map { k -> if (k == driver.id) k else (sitting(k)?.let { riderIdForRecord(it.id) } ?: k) }.toSet())
        }
        return TripRecord(
            driver = driver,
            riders = ridersOut.values.toList(),
            segments = segments,
            startKm = startKm ?: 0.0,
            endKm = now,
            startedAtMillis = startedAtMillis ?: lastEventMillis,
            endedAtMillis = lastEventMillis,
            energyStartPercent = energyStart.toMap(),
            energyEndPercent = energyLatest.toMap(),
            energyUsedPercent = energyUsed.toMap(),
            energyRecharged = energyRecharged.toSet(),
            efficiencySamples = efficiencySamples.mapValues { it.value.toList() },
            extras = extrasOut,
            startPlace = startPlace,
            stops = stops.toList(),
            sittings = recordedSittings,
            gaps = gaps.toList()
        )
    }

    /** 이름이 있으면 그 사람 id, 없으면 이 앉은 기간만의 손님 id. */
    private fun riderIdForRecord(sittingId: String): String = sitting(sittingId)?.riderId ?: "guest-$sittingId"

    // ---------------------------------------------------------------- 화면용 읽기

    fun isRecording(): Boolean = state == State.DRIVING || state == State.PAUSED

    fun liveDistanceKm(): Double {
        val start = startKm ?: return 0.0
        return ((currentKm ?: start) - start).coerceAtLeast(0.0)
    }

    /** 이번 운행의 모든 앉은 기간(출발 전 앉아 있는 사람 포함). */
    fun sittingViews(): List<SittingView> = sittings.map(::viewOf)

    /** 지금 각 좌석에 앉아 있는 기간. */
    fun openSittings(): Map<SeatPosition, SittingView> =
        seatSitting.mapNotNull { (seat, id) -> sitting(id)?.let { seat to viewOf(it) } }.toMap()

    /** 다음에 앉을 사람으로 미리 정해 둔 이름(빈 좌석). */
    fun pendingAssignments(): Map<SeatPosition, Rider> = (pendingSuggest + pendingAssign).filterKeys { it !in seatSitting }

    fun gaps(): List<RecordGap> = gaps.toList()

    fun occupantCount(): Int = 1 + seatSitting.size

    fun riderName(id: String): String? = riders[id]?.name

    private fun viewOf(s: Sitting): SittingView {
        val rider = s.riderId?.let(riders::get)
        val fallback = if (s.ordinalInSeat == 1) "${s.seat.label} 동승자" else "${s.seat.label} 동승자 ${s.ordinalInSeat}"
        val end = if (s.open) currentKm else s.endKm
        return SittingView(s.id, s.seat, rider, s.startKm, end, s.open, s.needsReview, fallback)
    }

    // ---------------------------------------------------------------- 내부

    private fun sitting(id: String): Sitting? = sittings.firstOrNull { it.id == id }

    private fun beginRecording(atMillis: Long) {
        val km = currentKm ?: return
        state = State.DRIVING
        startKm = km
        startedAtMillis = atMillis
        legStartMillis = atMillis
        startPlace = latestPlace
        pendingAssign.clear() // 출발 후 빈 좌석에 앉는 사람은 정차 후 확인합니다.
        pendingSuggest.clear()
        seatSitting.values.forEach { id -> sitting(id)?.startKm = km }
        openSegmentStartKm = km
        openOccupants = occupantIdsNow()
        energyLatest.forEach { (kind, value) -> energyStart.putIfAbsent(kind, value) }
        efficiencyLatest.forEach { (kind, value) -> addEfficiencySample(kind, value) }
    }

    private fun cutSegment() {
        val km = currentKm ?: return
        closeOpenSegment()
        openSegmentStartKm = km
        openOccupants = occupantIdsNow()
    }

    private fun closeOpenSegment() {
        val start = openSegmentStartKm ?: return
        val end = currentKm ?: start
        closedSegments += Segment(start, end, openOccupants)
        openSegmentStartKm = null
    }

    private fun openSegmentPreview(): Segment? {
        val start = openSegmentStartKm ?: return null
        return Segment(start, currentKm ?: start, openOccupants)
    }

    private fun occupantIdsNow(): Set<String> = setOf(driver.id) + seatSitting.values

    companion object {
        private const val START_FIX_WINDOW_MS = 120_000L
        /** 이 거리 안에서 정차 없이 다시 앉으면 센서 깜빡임으로 봅니다. */
        const val FLICKER_KM = 0.3
        /** 잔량이 이만큼 늘면 충전·주유로 봅니다(센서 잡음 제외). */
        const val RECHARGE_THRESHOLD_PERCENT = 1.0
    }
}

/** 끝난 운행의 불변 기록. 정산 계산의 유일한 입력입니다. */
data class TripRecord(
    val driver: Rider,
    val riders: List<Rider>,
    val segments: List<Segment>,
    val startKm: Double,
    val endKm: Double,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val energyStartPercent: Map<EnergyKind, Double>,
    val energyEndPercent: Map<EnergyKind, Double>,
    /** 기록 중 잔량 감소의 합(%). 중간 충전으로 늘어난 양은 빼지 않습니다. */
    val energyUsedPercent: Map<EnergyKind, Double> = emptyMap(),
    /** 기록 중 충전·주유로 잔량이 늘어난 적이 있는 에너지. */
    val energyRecharged: Set<EnergyKind> = emptySet(),
    val efficiencySamples: Map<EnergyKind, List<EfficiencySample>> = emptyMap(),
    val extras: List<ExtraCost>,
    val startPlace: Place? = null,
    val stops: List<Stop> = emptyList(),
    val sittings: List<SittingRecord> = emptyList(),
    val gaps: List<RecordGap> = emptyList()
) {
    val distanceKm: Double get() = segments.sumOf { it.distanceKm }
}
