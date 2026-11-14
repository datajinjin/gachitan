package kr.gachitan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.gachitan.core.Formatting
import kr.gachitan.session.SessionState.Phase
import kr.gachitan.ui.MainViewModel
import kr.gachitan.ui.UiState
import kr.gachitan.ui.components.HLine
import kr.gachitan.ui.components.SeatMap
import kr.gachitan.ui.components.SecondaryButton
import kr.gachitan.ui.components.VLine
import kr.gachitan.ui.model.LockReason
import kr.gachitan.ui.model.SeatState
import kr.gachitan.ui.model.lockCaption
import kr.gachitan.ui.model.occupied
import kr.gachitan.ui.model.seatCells
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type
import kr.gachitan.vehicle.Link

/**
 * 주행 중: 누를 것이 없습니다(상태를 모를 때의 '다시 연결' 하나만 예외).
 * 1~2초 안에 읽히는 두 가지만 크게 보입니다: 이동 거리, 탑승 인원.
 * 금액·버튼·하단 메뉴는 숨기고, 애니메이션을 쓰지 않습니다.
 */
@Composable
fun DrivingScreen(ui: UiState, lock: LockReason, layout: WindowLayout, vm: MainViewModel) {
    if (lock == LockReason.UNKNOWN) return UnknownState(vm)
    val s = ui.session
    val cells = seatCells(ui.settings.driverName, s.seats, ui.seatsKnown)
    val recording = s.phase == Phase.DRIVING && s.odometerKnown
    val people = cells.count { it.state.occupied }
    val seatsUnknown = cells.any { it.state == SeatState.UNKNOWN }
    val displaySize = if (layout.wide) 128.sp else 96.sp

    val info: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Text(if (recording) "이동 거리" else "주행거리 확인 중", style = Type.Title, color = Palette.TextSecondary)
            Text(
                buildAnnotatedString {
                    if (recording) {
                        val km = Formatting.km(s.liveKm)
                        append(km.removeSuffix("km"))
                        withStyle(SpanStyle(fontSize = displaySize * 0.42f)) { append("km") }
                    } else append("—")
                },
                style = Type.display(displaySize), color = Palette.Text, maxLines = 1
            )
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!seatsUnknown) {
                    repeat(people.coerceAtMost(6)) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(56.dp))
                    }
                    Spacer(Modifier.width(Space.m))
                }
                Text(
                    when {
                        seatsUnknown && ui.health.seats == Link.WAITING -> "좌석 확인 중"
                        seatsUnknown -> "좌석 정보 없음"
                        else -> "${people}명 탑승"
                    },
                    style = Type.Hero, color = Palette.Text, maxLines = 1
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (layout.wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.56f)) { info() }
                VLine(Modifier.fillMaxHeight().padding(vertical = Space.l))
                Spacer(Modifier.width(Space.xl))
                SeatMap(cells, onSeat = null, modifier = Modifier.weight(0.44f).fillMaxHeight())
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth()) {
                info()
                Spacer(Modifier.height(Space.l))
                // 좁은 창: 남는 높이만큼만 좌석 배치도를 그립니다(높이가 모자라면 생략).
                if (layout.height >= 560.dp) SeatMap(cells, onSeat = null, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
        HLine()
        Text(lockCaption(lock), style = Type.Heading, color = Palette.TextSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = Space.l))
    }
}

/** 주행 상태를 알 수 없어 잠근 화면. 누를 수 있는 것은 '다시 연결' 하나입니다. */
@Composable
private fun UnknownState(vm: MainViewModel) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
        Text("주행 상태를 확인하지 못했어요", style = Type.Hero, color = Palette.Caution)
        Spacer(Modifier.height(Space.m))
        Text(lockCaption(LockReason.UNKNOWN), style = Type.Body, color = Palette.TextSecondary)
        Spacer(Modifier.height(Space.xl))
        SecondaryButton("다시 연결", vm::reconnect, Modifier.widthIn(min = 280.dp))
    }
}
