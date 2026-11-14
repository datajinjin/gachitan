package kr.gachitan.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.gachitan.core.Formatting
import kr.gachitan.core.Gear
import kr.gachitan.core.Journey
import kr.gachitan.core.JourneyCalculator
import kr.gachitan.core.JourneyCodec
import kr.gachitan.core.JourneySummary
import kr.gachitan.core.Member
import kr.gachitan.core.Rider
import kr.gachitan.core.SeatPosition
import kr.gachitan.core.SettlementCalculator
import kr.gachitan.core.SharedExpense
import kr.gachitan.core.TripCommand
import kr.gachitan.core.TripEntry
import kr.gachitan.core.TripJournal
import kr.gachitan.core.TripLog
import kr.gachitan.core.TripLogCodec
import kr.gachitan.core.TripRecorder
import kr.gachitan.core.VehicleEvent
import kr.gachitan.data.AppSettings
import kr.gachitan.data.KeyValueStore
import kr.gachitan.data.SettingsSource
import kr.gachitan.vehicle.VehicleSignals
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 차량 신호 → 기록장(TripJournal) → 정산 확인 → 정산 → 묶음(Journey)의 흐름을 관리합니다.
 *
 * - 입력원 하나당 세션 하나입니다. 실차와 데모는 서로 다른 [namespace]의 파일에 기록합니다.
 * - 모든 변경은 기록장에 명령으로 쌓이고 저장됩니다. 프로세스가 다시 시작되면 같은 운행으로 복원하고,
 *   꺼져 있던 동안은 '기록 공백'으로 남깁니다(그동안의 승하차는 복원할 수 없습니다).
 * - 이벤트 구독을 먼저 연결한 다음 신호를 시작해, 연결 직후 받은 현재 값을 버리지 않습니다.
 */
class TripSession(
    private val scope: CoroutineScope,
    val signals: VehicleSignals,
    private val store: KeyValueStore,
    private val settings: SettingsSource,
    private val namespace: String = "",
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { _, _ -> }
) {
    val isDemo: Boolean get() = namespace.isNotEmpty()

    private val mutex = Mutex()
    /** 새 운행을 열 때 이어받을 마지막 차량 상태. journal보다 먼저 초기화되어야 합니다. */
    private val lastKnown = linkedMapOf<String, VehicleEvent>()
    /** 이름 → 사람. 같은 이름이면 다른 좌석·다른 운행에서도 같은 사람으로 이어집니다. */
    private val roster = linkedMapOf<String, Rider>()
    private var pendingWrite: Job? = null
    private var collector: Job? = null

    private var journal: TripJournal = restoreOrNew()
    private var reviewing = false
    private var settled: SettledTrip? = null
    /** 오래 세워 둔 뒤 자동으로 확정한 운행 수(화면 안내용). */
    private var autoSettledCount = 0
    private var journey: Journey? = store.read(file(JOURNEY_FILE))?.let { runCatching { JourneyCodec.decode(it) }.getOrNull() }
    private var endedJourney: JourneySummary? = null
    /** 정산을 확정한 운행(최신순). 송금 여부는 기록하지 않습니다. */
    private var trips: List<TripLog> = store.read(file(TRIPS_FILE))?.let { runCatching { TripLogCodec.decode(it) }.getOrNull() }.orEmpty()

    private val _state = MutableStateFlow(SessionState(demo = isDemo))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        journey?.members()?.forEach { if (it.id != DRIVER_ID) roster[it.name] = Rider(it.id, it.name) }
        journal.recorder.sittingViews().mapNotNull { it.rider }.forEach { roster.putIfAbsent(it.name, it) }
        journal.recorder.pendingAssignments().values.forEach { roster.putIfAbsent(it.name, it) }
    }

    /** 저장된 기록의 마지막 계기판 값(데모 시뮬레이터가 이어서 달리도록). */
    fun lastOdometerKm(): Double? = journal.recorder.currentKm

    fun start() {
        if (collector != null) return
        // 기록 중에 앱이 다시 시작됐다면 그 사이를 공백으로 남깁니다.
        if (journal.recorder.isRecording()) {
            journal.apply(TripCommand.Resumed(clock()))
            persistNow()
        }
        // UNDISPATCHED: 이 줄이 끝나기 전에 구독이 연결됩니다. 그 뒤에 신호를 켜야 첫 값이 버려지지 않습니다.
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.events.collect { event -> onVehicleEvent(event) }
        }
        signals.start()
        publish()
    }

    fun stop() {
        persistNow()
        signals.stop()
        collector?.cancel()
        collector = null
    }

    /** 연결 오류 화면의 '다시 연결'. */
    fun reconnect() {
        signals.stop()
        signals.start()
    }

    // ------------------------------------------------------------------ 차량 이벤트

    private suspend fun onVehicleEvent(event: VehicleEvent) = mutex.withLock {
        rememberLatest(event)
        splitAfterLongPark(event)
        val before = journal.recorder.state
        runCatching { journal.apply(TripCommand.Event(event)) }.onFailure { log("event failed", it) }
        val after = journal.recorder.state
        if (event is VehicleEvent.GearChanged && before != after) {
            // 출발·정차 순간에만 위치를 1회 요청하고, 잔량·효율을 다시 읽습니다.
            signals.requestLocationFix()
            signals.refresh()
        }
        if (after == TripRecorder.State.DRIVING) {
            // 정산 확인 중 다시 출발하면 확인을 취소하고 기록을 이어 갑니다. 정산 결과 화면도 닫습니다.
            reviewing = false
            settled = null
        }
        if (event is VehicleEvent.OdometerChanged || event is VehicleEvent.EnergyLevelChanged) persistSoon()
        else persistNow()
        publish()
    }

    /**
     * 오래 세워 둔 뒤 다시 출발하면(퇴근 후 정산을 누르지 않고 다음 날 아침 출근 등) 앞 운행을 먼저 확정합니다.
     * 그대로 이어 붙이면 저녁 운행과 아침 운행이 한 운행으로 합쳐져, 아침에 처음 탄 사람이
     * 저녁 구간과 섞여 보이고 기록 날짜도 틀어집니다. 확정한 운행은 기록 탭에 그대로 남습니다.
     */
    private fun splitAfterLongPark(event: VehicleEvent) {
        if (event !is VehicleEvent.GearChanged || event.gear != Gear.DRIVE) return
        if (journal.recorder.state != TripRecorder.State.PAUSED) return
        val parkedAt = journal.recorder.stops().lastOrNull()?.atMillis ?: return
        if (event.atMillis - parkedAt < LONG_PARK_MS) return
        if (journal.recorder.liveDistanceKm() <= 0.0) { discardLocked(); return }
        finishTripLocked()
        autoSettledCount++
    }

    private fun discardLocked() {
        reviewing = false
        val stillSeated = journal.recorder.openSittings().mapNotNull { (seat, v) -> v.rider?.let { seat to it } }.toMap()
        journal = freshJournal(stillSeated)
    }

    private fun rememberLatest(event: VehicleEvent) {
        val key = when (event) {
            is VehicleEvent.SeatOccupancyChanged -> "S:${event.seat}"
            is VehicleEvent.EnergyLevelChanged -> "E:${event.kind}"
            is VehicleEvent.EfficiencyReported -> "F:${event.kind}"
            is VehicleEvent.GearChanged -> "G"
            is VehicleEvent.OdometerChanged -> "O"
            is VehicleEvent.PlaceObserved -> "P"
        }
        if (event is VehicleEvent.OdometerChanged) {
            val previous = lastKnown[key] as? VehicleEvent.OdometerChanged
            if (previous != null && event.km < previous.km) return
        }
        lastKnown[key] = event
    }

    // ------------------------------------------------------------------ 이름 붙이기 (정차 중)

    /** 좌석에 이름을 붙입니다. 빈 문자열이면 이름을 지웁니다. */
    fun assign(seat: SeatPosition, name: String) = command {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) journal.apply(TripCommand.Unassign(seat))
        else {
            journal.apply(TripCommand.Assign(seat, riderNamed(trimmed)))
            settings.rememberNames(listOf(trimmed))
        }
    }

    /** 정산 확인에서 앉은 기간 하나(이미 내린 사람 포함)에 이름을 붙입니다. */
    fun nameSitting(sittingId: String, name: String) = command {
        val trimmed = name.trim().take(MAX_NAME)
        journal.apply(TripCommand.NameSitting(sittingId, trimmed.takeIf { it.isNotEmpty() }?.let(::riderNamed)))
        if (trimmed.isNotEmpty()) settings.rememberNames(listOf(trimmed))
    }

    fun confirmSitting(sittingId: String) = command { journal.apply(TripCommand.ConfirmSitting(sittingId)) }

    fun labelCurrentStop(name: String) = command {
        val index = journal.recorder.stops().lastIndex
        if (journal.recorder.state == TripRecorder.State.PAUSED && index >= 0 && name.isNotBlank()) {
            journal.apply(TripCommand.LabelStop(index, name))
        }
    }

    // ------------------------------------------------------------------ 확정 비용 (통행료·주차비)

    fun addExtra(label: String, amountWon: Long, payerKeys: Set<String>) = command {
        if (journal.recorder.isRecording() && amountWon > 0) {
            journal.apply(TripCommand.AddExtra(label.ifBlank { "추가 비용" }.take(20), amountWon, payerKeys))
        }
    }

    fun removeExtra(index: Int) = command { journal.apply(TripCommand.RemoveExtra(index)) }

    fun setExtraPayers(index: Int, payerKeys: Set<String>) = command {
        if (payerKeys.isNotEmpty()) journal.apply(TripCommand.SetExtraPayers(index, payerKeys))
    }

    // ------------------------------------------------------------------ 정산

    /** 정차 중 '운행 끝내기': 아직 끝내지 않고 정산 확인 화면을 엽니다. */
    fun beginReview() = command {
        if (journal.recorder.state == TripRecorder.State.PAUSED) reviewing = true
    }

    fun cancelReview() = command { reviewing = false }

    /** 정산 확정. 묶음 중이면 묶음에 추가하고, 운행별 송금 요청은 만들지 않습니다. */
    fun confirmSettlement() = command { finishTripLocked() }

    fun dismissSettlement() = command { settled = null }

    /** 정차 중 운행 버리기(예: 움직이지 않고 D→P만 한 빈 운행). 정산·기록·여행에 남기지 않습니다. */
    fun discardTrip() = command {
        if (journal.recorder.state != TripRecorder.State.PAUSED) return@command
        reviewing = false
        val stillSeated = journal.recorder.openSittings().mapNotNull { (seat, v) -> v.rider?.let { seat to it } }.toMap()
        journal = freshJournal(stillSeated)
    }

    private fun finishTripLocked() {
        if (journal.recorder.state != TripRecorder.State.PAUSED) return
        val result = SettlementCalculator.settle(journal.recorder.finish(), settings.current.pricing())
        reviewing = false
        var bundleTitle: String? = null
        var day: Int? = null
        journey?.takeIf { !it.isEnded }?.let { j ->
            val updated = j.withTrip(TripEntry.from(result))
            journey = updated
            day = JourneyCalculator.summarize(updated, zone).days.lastOrNull()?.dayNumber ?: 1
            bundleTitle = updated.title
            saveJourney()
        }
        val log = TripLog.from(result, UUID.randomUUID().toString(), bundleTitle)
        settled = SettledTrip(result, bundleTitle, day, log)
        addTrip(log)
        // 아직 앉아 있는 사람의 이름은 다음 운행으로 이어 줍니다(내렸다 다시 타면 '확인 필요'로 제안).
        val stillSeated = journal.recorder.openSittings().mapNotNull { (seat, v) -> v.rider?.let { seat to it } }.toMap()
        journal = freshJournal(stillSeated)
    }

    // ------------------------------------------------------------------ 묶음 정산 (여러 운행을 한 번에)

    fun startJourney(title: String) = command {
        if (journey?.isEnded == false) return@command
        val t = title.trim().take(30).ifEmpty { "여행" }
        journey = Journey(UUID.randomUUID().toString(), t, clock()).withMember(Member(DRIVER_ID, driverName()))
        endedJourney = null
        saveJourney()
    }

    fun addExpense(label: String, amountWon: Long, payerId: String, participantIds: Set<String>) = command {
        val j = journey ?: return@command
        if (amountWon <= 0) return@command
        journey = j.withExpense(
            SharedExpense(UUID.randomUUID().toString(), label.ifBlank { "공동 경비" }.take(20), amountWon, payerId, participantIds, clock())
        )
        saveJourney()
    }

    fun removeExpense(id: String) = command { journey = journey?.withoutExpense(id); saveJourney() }

    fun addMember(name: String) = command {
        val n = name.trim().take(MAX_NAME).ifEmpty { return@command }
        val rider = riderNamed(n)
        journey = journey?.withMember(Member(rider.id, rider.name))
        saveJourney()
    }

    /**
     * 묶음을 끝내고 최종 정산을 만듭니다. 정차 중인 운행이 있으면 먼저 그 운행을 정산해 포함합니다
     * (끝내지 않은 운행이 빠지지 않도록). 주행 중에는 끝낼 수 없습니다.
     */
    fun endJourney() = command {
        val j = journey ?: return@command
        when (journal.recorder.state) {
            TripRecorder.State.DRIVING, TripRecorder.State.WAITING_FOR_ODOMETER -> return@command
            TripRecorder.State.PAUSED -> finishTripLocked()
            else -> Unit
        }
        val current = journey ?: j
        val ended = current.copy(endedAtMillis = clock())
        val summary = JourneyCalculator.summarize(ended, zone, settings.current.roundingUnitWon)
        endedJourney = summary
        settled = null
        journey = null
        store.delete(file(JOURNEY_FILE))
    }

    fun dismissEndedJourney() = command { endedJourney = null }

    fun discardJourney() = command { journey = null; store.delete(file(JOURNEY_FILE)) }

    // ------------------------------------------------------------------ 설정·데이터

    fun refreshDriver() = command {
        if (journal.recorder.state == TripRecorder.State.IDLE) {
            val seated = journal.recorder.openSittings().mapNotNull { (seat, v) -> v.rider?.let { seat to it } }.toMap()
            journal = freshJournal(seated)
        }
    }

    /** 정산 기록의 한 사람을 '받음'으로 표시하거나 취소합니다(운전자가 직접 표시, 앱은 송금을 확인하지 않음). */
    fun setPaid(tripId: String, riderId: String, paid: Boolean) = command {
        updateTrips { if (it.id == tripId) it.withPaid(riderId, paid) else it }
    }

    /** 여러 기록에서 같은 이름의 동승자를 한 번에 '받음'으로 표시합니다(이번 달 몰아서 받았을 때). */
    fun setPaidByName(tripIds: Set<String>, name: String, paid: Boolean) = command {
        updateTrips { log ->
            if (log.id !in tripIds) log
            else log.payers.filter { it.name == name }.fold(log) { acc, sh -> acc.withPaid(sh.riderId, paid) }
        }
    }

    private fun updateTrips(transform: (TripLog) -> TripLog) {
        trips = trips.map(transform)
        runCatching { store.write(file(TRIPS_FILE), TripLogCodec.encode(trips)) }.onFailure { log("trips save failed", it) }
    }

    fun clearHistory() = command {
        trips = emptyList()
        listOf(TRIPS_FILE, LEGACY_HISTORY_FILE).forEach { store.delete(file(it)) }
    }

    fun resetAll() = command {
        settled = null; reviewing = false
        journey = null; endedJourney = null
        trips = emptyList()
        roster.clear()
        listOf(JOURNAL_FILE, JOURNEY_FILE, TRIPS_FILE, LEGACY_HISTORY_FILE).forEach { store.delete(file(it)) }
        journal = freshJournal()
    }

    // ------------------------------------------------------------------ 내부

    private fun riderNamed(name: String): Rider = roster.getOrPut(name) { Rider(UUID.randomUUID().toString(), name) }

    private fun command(block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                runCatching { block() }.onFailure { log("command failed", it) }
                persistNow()
                publish()
            }
        }
    }

    private fun driverName() = settings.current.driverName.ifBlank { "운전자" }

    private fun file(name: String) = namespace + name

    private fun freshJournal(carry: Map<SeatPosition, Rider> = emptyMap()): TripJournal =
        TripJournal(Rider(DRIVER_ID, driverName())).also { j ->
            // 마지막 차량 상태(좌석·계기판·잔량·장소)를 이어받아야 다음 운행의 첫 구간과 출발지가 정확합니다.
            // 기어는 이어받지 않습니다: 다음에 D로 바꾸는 순간이 새 운행의 시작입니다.
            lastKnown.values.filterNot { it is VehicleEvent.GearChanged }.forEach { e -> j.apply(TripCommand.Event(e)) }
            carry.forEach { (seat, rider) ->
                if (seat in j.recorder.openSittings()) j.apply(TripCommand.Assign(seat, rider))
            }
        }

    private fun restoreOrNew(): TripJournal =
        store.read(file(JOURNAL_FILE))?.let { runCatching { TripJournal.decode(it) }.getOrNull() } ?: freshJournal()

    private fun saveJourney() {
        val j = journey ?: return
        runCatching { store.write(file(JOURNEY_FILE), JourneyCodec.encode(j)) }.onFailure { log("journey save failed", it) }
    }

    private fun addTrip(entry: TripLog) {
        trips = (listOf(entry) + trips).take(MAX_TRIPS)
        runCatching { store.write(file(TRIPS_FILE), TripLogCodec.encode(trips)) }.onFailure { log("trips save failed", it) }
    }

    private fun persistSoon() {
        if (pendingWrite?.isActive == true) return
        pendingWrite = scope.launch(Dispatchers.IO) {
            delay(WRITE_COALESCE_MS)
            mutex.withLock { persistNow() }
        }
    }

    private fun persistNow() {
        runCatching { store.write(file(JOURNAL_FILE), journal.encode()) }.onFailure { log("persist failed", it) }
    }

    private fun publish() {
        val r = journal.recorder
        val cfg: AppSettings = settings.current
        if (reviewing && r.state != TripRecorder.State.PAUSED) reviewing = false
        val phase = when {
            settled != null && r.state == TripRecorder.State.IDLE -> SessionState.Phase.SETTLED
            r.state == TripRecorder.State.DRIVING -> SessionState.Phase.DRIVING
            r.state == TripRecorder.State.WAITING_FOR_ODOMETER -> SessionState.Phase.WAITING_FOR_ODOMETER
            r.state == TripRecorder.State.PAUSED && reviewing -> SessionState.Phase.REVIEW
            r.state == TripRecorder.State.PAUSED -> SessionState.Phase.PARKED
            else -> SessionState.Phase.READY
        }
        val open = r.openSittings()
        val pending = r.pendingAssignments()
        val seats = SeatPosition.passengerSeats.map { seat ->
            val s = open[seat]
            SeatView(
                seat = seat,
                occupied = s != null,
                sittingId = s?.id,
                name = s?.rider?.name,
                needsReview = s?.needsReview == true,
                pendingName = if (s == null) pending[seat]?.name else null,
                km = s?.distanceKm ?: 0.0
            )
        }
        val participants = if (r.isRecording()) r.participantKeys() else emptyMap()
        val extras = r.extras().mapIndexed { i, e ->
            ExtraView(i, e.label, e.amountWon, e.payerIds, e.payerIds.map { k -> participants[r.keyOf(k)] ?: "동승자" }.distinct())
        }
        val preview = if (phase == SessionState.Phase.PARKED || phase == SessionState.Phase.REVIEW) {
            r.preview()?.let { runCatching { SettlementCalculator.settle(it, cfg.pricing()) }.getOrNull() }
        } else null
        val recorded = r.sittingViews().filter { it.startKm != null }

        val j = journey
        val summary = j?.let { JourneyCalculator.summarize(it, zone, cfg.roundingUnitWon) }
        val latestMillis = lastKnown.values.maxOfOrNull { it.atMillis } ?: clock()
        val today = Instant.ofEpochMilli(latestMillis).atZone(zone).toLocalDate()
        val firstDate = summary?.days?.firstOrNull()?.date
            ?: j?.let { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() }
        val currentDay = if (firstDate == null) 1 else (ChronoUnit.DAYS.between(firstDate, today).toInt() + 1).coerceAtLeast(1)
        val stop = r.stops().lastOrNull()?.takeIf { r.state == TripRecorder.State.PAUSED }

        _state.value = SessionState(
            phase = phase,
            demo = isDemo,
            seats = seats,
            occupantCount = r.occupantCount(),
            liveKm = r.liveDistanceKm(),
            odometerKnown = r.currentKm != null,
            lastGear = (lastKnown["G"] as? VehicleEvent.GearChanged)?.gear,
            currentStopName = stop?.place?.displayName("") ?.takeIf { it.isNotBlank() },
            sittings = recorded,
            reviewCount = recorded.count { it.needsReview },
            participants = participants,
            defaultExtraPayers = if (r.isRecording()) r.defaultExtraPayerKeys() else emptySet(),
            extras = extras,
            preview = preview,
            gaps = r.gaps(),
            settled = settled,
            autoSettledCount = autoSettledCount,
            journey = j,
            journeySummary = summary,
            currentDayNumber = currentDay,
            endedJourney = endedJourney,
            trips = trips
        )
    }

    companion object {
        const val DRIVER_ID = "driver"
        private const val JOURNAL_FILE = "journal.enc"
        private const val JOURNEY_FILE = "journey.enc"
        private const val TRIPS_FILE = "trips.enc"
        /** 이만큼 세워 둔 뒤 출발하면 새 운행으로 나눕니다. */
        const val LONG_PARK_MS = 3 * 60 * 60 * 1000L
        /** 이전 버전의 문장형 기록. 지울 때만 씁니다. */
        private const val LEGACY_HISTORY_FILE = "history.enc"
        private const val MAX_TRIPS = 200
        private const val MAX_NAME = 12
        private const val WRITE_COALESCE_MS = 1_500L

        /** 화면에서 쓰는 거리 표기(정산 확인 화면 등). */
        fun km(v: Double) = Formatting.km(v)
    }
}
