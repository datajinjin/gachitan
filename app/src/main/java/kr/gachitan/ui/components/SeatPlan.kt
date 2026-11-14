package kr.gachitan.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kr.gachitan.core.SeatPosition
import kr.gachitan.ui.model.SeatCell
import kr.gachitan.ui.model.SeatState
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Type


/**
 * 위에서 내려다본 차와 좌석. 운전석은 왼쪽 앞(좌핸들)이고 위에 핸들을 그립니다.
 * 주어진 공간(가로·세로) 안에서 차 비율(약 0.66:1)을 지키며 가장 크게 그립니다.
 *
 * 좌석 상태는 색에만 기대지 않습니다.
 * - 탑승(이름 있음): 블루로 채움 + 사람 아이콘 + 이름
 * - 탑승(이름 없음): 옅은 블루 + 블루 테두리 + "이름 없음" → 정차 중에 눌러 이름을 정하면 됩니다
 * - 빈 좌석: 점선 + "—"
 * - 확인 필요: 주의색 테두리 + "이름?"
 * - 신호 없음: 점선 + "?"
 */
@Composable
fun SeatMap(
    cells: List<SeatCell>,
    onSeat: ((SeatPosition) -> Unit)?,
    modifier: Modifier = Modifier,
    maxCarWidth: Dp = 520.dp
) {
    val by = cells.associateBy { it.seat }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = 0.66f
        val carW = min(min(maxWidth, maxHeight * ratio), maxCarWidth)
        val carH = carW / ratio
        Box(Modifier.size(carW, carH)) {
            val carLine = Palette.CarLine
            Canvas(Modifier.fillMaxSize()) { drawCarOutline(carLine) }
            val inner = carW * 0.80f
            val gap = carW * 0.035f
            val frontW = (inner - gap) / 2f
            val rearW = (inner - gap * 2) / 3f
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = carH * 0.27f).width(inner),
                verticalArrangement = Arrangement.spacedBy(carH * 0.045f)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    Seat(by[SeatPosition.DRIVER], frontW, frontW * 1.02f, onSeat = null, wheel = true)
                    Seat(by[SeatPosition.FRONT_PASSENGER], frontW, frontW * 1.02f, onSeat)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    listOf(SeatPosition.REAR_LEFT, SeatPosition.REAR_CENTER, SeatPosition.REAR_RIGHT).forEach { s ->
                        Seat(by[s], rearW, rearW * 1.25f, onSeat)
                    }
                }
            }
        }
    }
}

@Composable
private fun Seat(cell: SeatCell?, w: Dp, h: Dp, onSeat: ((SeatPosition) -> Unit)?, wheel: Boolean = false) {
    cell ?: return Spacer(Modifier.size(w, h))
    // 이름이 정해진 탑승(운전석 포함)은 진한 블루, 이름이 없는 탑승은 옅은 블루 + 블루 테두리로 구분합니다.
    val named = cell.state == SeatState.DRIVER || cell.state == SeatState.NAMED
    val unnamed = cell.state == SeatState.UNNAMED
    val occupied = named || unnamed
    val review = cell.state == SeatState.REVIEW
    val clickable = onSeat != null && cell.seat != SeatPosition.DRIVER
    val fillColor = when { named -> Palette.Accent; unnamed -> Palette.AccentFill; review -> Palette.CautionFill; else -> null }
    val strokeColor = when { review -> Palette.Caution; unnamed -> Palette.Accent; named -> null; else -> Palette.Outline }
    val inkColor = when { named -> Palette.OnAccent; unnamed -> Palette.AccentText; review -> Palette.Caution; else -> Palette.TextSecondary }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 앞줄 두 좌석의 높이를 맞추려고 조수석 위에도 핸들 높이만큼 비워 둡니다.
        if (wheel) { val wc = Palette.Wheel; Canvas(Modifier.size(w * 0.42f, w * 0.30f)) { drawWheel(wc) } }
        else if (cell.seat == SeatPosition.FRONT_PASSENGER) Spacer(Modifier.height(w * 0.30f))
        Box(
            Modifier.size(w, h).clip(RoundedCornerShape(w * 0.16f))
                .let { if (clickable) it.clickable(role = Role.Button) { onSeat?.invoke(cell.seat) } else it }
                .semantics { contentDescription = cell.description },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawSeatShape(fill = fillColor, stroke = strokeColor, dashed = !occupied && !review)
            }
            Column(
                Modifier.padding(top = h * 0.16f, start = 6.dp, end = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (occupied || review) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = inkColor,
                        modifier = Modifier.size(minOf(w * 0.34f, 44.dp)))
                }
                Text(
                    cell.text,
                    style = if (occupied || review) Type.Seat else Type.Heading,
                    color = inkColor,
                    // '이름 없음'처럼 두 단어인 표기는 좁은 뒷좌석에서 두 줄로 내려 잘리지 않게 합니다.
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 좌석 모양: 위에 머리받침, 아래에 등판. */
private fun DrawScope.drawSeatShape(fill: Color?, stroke: Color?, dashed: Boolean) {
    val w = size.width; val h = size.height
    val headW = w * 0.56f; val headH = h * 0.13f
    val bodyTop = headH * 0.85f
    val r = CornerRadius(w * 0.16f)
    val headR = CornerRadius(w * 0.08f)
    val headOffset = Offset((w - headW) / 2f, 1f)
    val headSize = Size(headW, headH)
    val bodyOffset = Offset(1f, bodyTop)
    val bodySize = Size(w - 2f, h - bodyTop - 1f)
    if (fill != null) {
        drawRoundRect(fill, headOffset, headSize, headR)
        drawRoundRect(fill, bodyOffset, bodySize, r)
    }
    if (stroke != null) {
        val st = Stroke(width = 2.dp.toPx(), pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
        drawRoundRect(stroke, headOffset, headSize, headR, style = st)
        drawRoundRect(stroke, bodyOffset, bodySize, r, style = st)
    }
}

/** 핸들: 원 + 가운데 허브 + 세 개의 살. */
private fun DrawScope.drawWheel(color: Color) {
    val c = Offset(size.width / 2f, size.height / 2f + 2f)
    val radius = minOf(size.width, size.height * 1.6f) / 2f * 0.9f
    val st = Stroke(width = 5.dp.toPx())
    drawCircle(color, radius, c, style = st)
    drawCircle(color, radius * 0.22f, c)
    drawLine(color, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), strokeWidth = 4.dp.toPx())
}

/** 차 윤곽: 보닛·앞유리·뒷유리·사이드미러를 단순한 선으로. */
private fun DrawScope.drawCarOutline(CarLine: Color) {
    val w = size.width; val h = size.height
    val st = Stroke(width = 2.5.dp.toPx())
    val body = Path().apply {
        moveTo(w * 0.22f, h * 0.02f)
        cubicTo(w * 0.40f, h * 0.0f, w * 0.60f, h * 0.0f, w * 0.78f, h * 0.02f)
        cubicTo(w * 0.93f, h * 0.05f, w * 0.97f, h * 0.14f, w * 0.97f, h * 0.24f)
        lineTo(w * 0.97f, h * 0.86f)
        cubicTo(w * 0.97f, h * 0.95f, w * 0.88f, h * 0.995f, w * 0.75f, h * 0.995f)
        lineTo(w * 0.25f, h * 0.995f)
        cubicTo(w * 0.12f, h * 0.995f, w * 0.03f, h * 0.95f, w * 0.03f, h * 0.86f)
        lineTo(w * 0.03f, h * 0.24f)
        cubicTo(w * 0.03f, h * 0.14f, w * 0.07f, h * 0.05f, w * 0.22f, h * 0.02f)
        close()
    }
    drawPath(body, CarLine, style = st)
    // 앞유리(보닛과 실내 경계)
    val shield = Path().apply {
        moveTo(w * 0.12f, h * 0.22f)
        quadraticTo(w * 0.5f, h * 0.14f, w * 0.88f, h * 0.22f)
    }
    drawPath(shield, CarLine, style = st)
    // 실내(유리 안쪽)
    drawRoundRect(CarLine, Offset(w * 0.08f, h * 0.22f), Size(w * 0.84f, h * 0.70f), CornerRadius(w * 0.10f), style = st)
    // 사이드미러
    drawRoundRect(CarLine, Offset(-w * 0.03f, h * 0.24f), Size(w * 0.07f, h * 0.035f), CornerRadius(w * 0.02f), style = st)
    drawRoundRect(CarLine, Offset(w * 0.96f, h * 0.24f), Size(w * 0.07f, h * 0.035f), CornerRadius(w * 0.02f), style = st)
}
