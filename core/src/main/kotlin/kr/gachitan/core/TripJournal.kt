package kr.gachitan.core

/**
 * 운행 중 일어난 모든 일을 순서대로 적어 두는 기록장입니다.
 *
 * 앱이 백그라운드로 내려가거나, 시동이 꺼져 Suspend 모드로 들어가거나, 프로세스가 재생성되어도
 * 이 기록을 다시 재생(replay)하면 운행 상태가 똑같이 복원됩니다. 저장 형식은 줄 단위 텍스트라서
 * 외부 라이브러리 없이 읽고 쓸 수 있습니다.
 */
sealed interface TripCommand {
    data class Event(val event: VehicleEvent) : TripCommand
    data class Assign(val seat: SeatPosition, val rider: Rider) : TripCommand
    data class Unassign(val seat: SeatPosition) : TripCommand
    data class AddExtra(val label: String, val amountWon: Long, val payerKeys: Set<String> = emptySet()) : TripCommand
    data class RemoveExtra(val index: Int) : TripCommand
    data class SetExtraPayers(val index: Int, val payerKeys: Set<String>) : TripCommand
    data class LabelStop(val index: Int, val name: String) : TripCommand
    /** 이미 내린 사람을 포함해 앉은 기간 하나에 이름을 붙입니다. rider가 null이면 이름을 지웁니다. */
    data class NameSitting(val sittingId: String, val rider: Rider?) : TripCommand
    data class ConfirmSitting(val sittingId: String) : TripCommand
    /** 앱이 다시 시작된 시각. 기록 중이었다면 공백으로 남습니다. */
    data class Resumed(val atMillis: Long) : TripCommand
}

class TripJournal(val driver: Rider) {
    private val commands = mutableListOf<TripCommand>()
    var recorder: TripRecorder = TripRecorder(driver)
        private set

    val size: Int get() = commands.size

    fun apply(command: TripCommand) {
        execute(recorder, command)
        commands += command
    }

    fun commands(): List<TripCommand> = commands.toList()

    fun encode(): String = buildString {
        appendLine(listOf("V2", esc(driver.id), esc(driver.name)).joinToString(SEP))
        commands.forEach { appendLine(encode(it)) }
    }

    companion object {
        private const val SEP = "|"

        fun decode(text: String): TripJournal {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.isNotEmpty()) { "빈 기록입니다." }
            val header = lines.first().split(SEP)
            require(header.size == 3 && (header[0] == "V1" || header[0] == "V2")) { "알 수 없는 기록 형식입니다." }
            val journal = TripJournal(Rider(unesc(header[1]), unesc(header[2])))
            lines.drop(1).forEach { line ->
                // 손상된 줄은 건너뛰되, 나머지 기록은 최대한 살립니다.
                runCatching { journal.apply(decodeCommand(line)) }
            }
            return journal
        }

        fun execute(recorder: TripRecorder, command: TripCommand) {
            when (command) {
                is TripCommand.Event -> recorder.onEvent(command.event)
                is TripCommand.Assign -> recorder.assign(command.seat, command.rider)
                is TripCommand.Unassign -> recorder.unassign(command.seat)
                is TripCommand.AddExtra -> recorder.addExtraCost(command.label, command.amountWon, command.payerKeys)
                is TripCommand.RemoveExtra -> recorder.removeExtraCost(command.index)
                is TripCommand.SetExtraPayers -> recorder.setExtraPayers(command.index, command.payerKeys)
                is TripCommand.LabelStop -> recorder.labelStop(command.index, command.name)
                is TripCommand.NameSitting -> recorder.nameSitting(command.sittingId, command.rider)
                is TripCommand.ConfirmSitting -> recorder.confirmSitting(command.sittingId)
                is TripCommand.Resumed -> recorder.onResume(command.atMillis)
            }
        }

        private fun encode(command: TripCommand): String = when (command) {
            is TripCommand.Assign -> listOf("AS", command.seat.name, esc(command.rider.id), esc(command.rider.name))
            is TripCommand.Unassign -> listOf("UA", command.seat.name)
            is TripCommand.AddExtra -> listOf("EX", esc(command.label), command.amountWon.toString(),
                command.payerKeys.joinToString(",") { esc(it) })
            is TripCommand.RemoveExtra -> listOf("RX", command.index.toString())
            is TripCommand.SetExtraPayers -> listOf("XP", command.index.toString(), command.payerKeys.joinToString(",") { esc(it) })
            is TripCommand.LabelStop -> listOf("LS", command.index.toString(), esc(command.name))
            is TripCommand.NameSitting -> listOf("NS", esc(command.sittingId),
                esc(command.rider?.id.orEmpty()), esc(command.rider?.name.orEmpty()))
            is TripCommand.ConfirmSitting -> listOf("CS", esc(command.sittingId))
            is TripCommand.Resumed -> listOf("RS", command.atMillis.toString())
            is TripCommand.Event -> when (val e = command.event) {
                is VehicleEvent.GearChanged -> listOf("G", e.atMillis.toString(), e.gear.name)
                is VehicleEvent.SeatOccupancyChanged ->
                    listOf("S", e.atMillis.toString(), e.seat.name, if (e.occupied) "1" else "0")
                is VehicleEvent.OdometerChanged -> listOf("O", e.atMillis.toString(), e.km.toString())
                is VehicleEvent.EnergyLevelChanged ->
                    listOf("E", e.atMillis.toString(), e.kind.name, e.percent.toString())
                is VehicleEvent.EfficiencyReported ->
                    listOf("F", e.atMillis.toString(), e.kind.name, e.kmPerUnit.toString())
                is VehicleEvent.PlaceObserved -> listOf(
                    "P", e.atMillis.toString(), e.place.source.name,
                    e.place.latitude?.toString().orEmpty(), e.place.longitude?.toString().orEmpty(),
                    esc(e.place.name.orEmpty()), esc(e.place.address.orEmpty())
                )
            }
        }.joinToString(SEP)

        private fun decodeCommand(line: String): TripCommand {
            val p = line.split(SEP)
            return when (p[0]) {
                "AS" -> TripCommand.Assign(SeatPosition.valueOf(p[1]), Rider(unesc(p[2]), unesc(p[3])))
                "UA" -> TripCommand.Unassign(SeatPosition.valueOf(p[1]))
                "EX" -> TripCommand.AddExtra(unesc(p[1]), p[2].toLong(), keys(p.getOrNull(3)))
                "RX" -> TripCommand.RemoveExtra(p[1].toInt())
                "XP" -> TripCommand.SetExtraPayers(p[1].toInt(), keys(p.getOrNull(2)))
                "LS" -> TripCommand.LabelStop(p[1].toInt(), unesc(p[2]))
                "NS" -> TripCommand.NameSitting(unesc(p[1]),
                    unesc(p[2]).takeIf { it.isNotEmpty() }?.let { Rider(it, unesc(p[3])) })
                "CS" -> TripCommand.ConfirmSitting(unesc(p[1]))
                "RS" -> TripCommand.Resumed(p[1].toLong())
                "P" -> TripCommand.Event(
                    VehicleEvent.PlaceObserved(
                        Place(
                            name = unesc(p[5]).ifEmpty { null }, address = unesc(p[6]).ifEmpty { null },
                            latitude = p[3].toDoubleOrNull(), longitude = p[4].toDoubleOrNull(),
                            source = PlaceSource.valueOf(p[2])
                        ),
                        p[1].toLong()
                    )
                )
                "G" -> TripCommand.Event(VehicleEvent.GearChanged(Gear.valueOf(p[2]), p[1].toLong()))
                "S" -> TripCommand.Event(
                    VehicleEvent.SeatOccupancyChanged(SeatPosition.valueOf(p[2]), p[3] == "1", p[1].toLong())
                )
                "O" -> TripCommand.Event(VehicleEvent.OdometerChanged(p[2].toDouble(), p[1].toLong()))
                "E" -> TripCommand.Event(
                    VehicleEvent.EnergyLevelChanged(EnergyKind.valueOf(p[2]), p[3].toDouble(), p[1].toLong())
                )
                "F" -> TripCommand.Event(
                    VehicleEvent.EfficiencyReported(EnergyKind.valueOf(p[2]), p[3].toDouble(), p[1].toLong())
                )
                else -> error("unknown command ${p[0]}")
            }
        }

        private fun keys(field: String?): Set<String> =
            field.orEmpty().split(",").filter { it.isNotEmpty() }.map(::unesc).toSet()

        // 이름에 구분자(|, ,)나 줄바꿈이 들어가도 안전하도록 %로 이스케이프합니다.
        private fun esc(s: String): String = TextEscape.esc(s)
        private fun unesc(s: String): String = TextEscape.unesc(s)
    }
}
