package kr.gachitan.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kr.gachitan.core.Formatting
import kr.gachitan.ui.model.TimelineModel
import kr.gachitan.ui.model.TimelineRow
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type

/**
 * 정산 확인의 중심 요소: 사람마다 한 줄, 가로축은 주행거리, 막대는 실제로 앉아 있던 구간입니다.
 * "누가 어디서 타고 내려서 이 금액이 됐는지"가 한 화면에 보입니다.
 * 줄을 누르면 그 사람의 이름을 고치거나 확인할 수 있습니다.
 */
@Composable
fun Timeline(
    model: TimelineModel,
    onRow: ((TimelineRow) -> Unit)?,
    modifier: Modifier = Modifier,
    nameWidth: Dp = 168.dp,
    amountWidth: Dp = 136.dp
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        model.rows.forEach { row ->
            val label = when {
                row.needsReview -> "${row.name} · 확인"
                row.isDriver -> "${row.name} · 운전"
                else -> row.name
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .let { m -> if (onRow != null && !row.isDriver) m.clickable(role = Role.Button) { onRow(row) } else m }
                    .semantics { contentDescription = "$label ${Formatting.km(row.km)} ${Formatting.won(row.amountWon)}" },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.width(nameWidth)) {
                    Text(label, style = Type.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            row.needsReview -> Palette.Caution
                            row.unnamed -> Palette.TextSecondary
                            else -> Palette.Text
                        })
                    Text(Formatting.km(row.km), style = Type.Caption, color = Palette.TextSecondary, maxLines = 1)
                }
                Spacer(Modifier.width(Space.m))
                Track(row, model, Modifier.weight(1f))
                Spacer(Modifier.width(Space.m))
                Text(
                    Formatting.won(row.amountWon),
                    style = Type.Heading.copy(fontFeatureSettings = "tnum"),
                    color = if (row.isDriver) Palette.TextSecondary else Palette.Text,
                    textAlign = TextAlign.End, maxLines = 1,
                    modifier = Modifier.width(amountWidth)
                )
            }
        }
        Axis(model, Modifier.fillMaxWidth().padding(start = nameWidth + Space.m, end = amountWidth + Space.m))
    }
}

@Composable
private fun Track(row: TimelineRow, model: TimelineModel, modifier: Modifier) {
    val fill = when {
        row.needsReview -> Palette.Caution
        row.isDriver -> Palette.TextSecondary
        else -> Palette.Accent
    }
    Canvas(modifier.height(28.dp)) {
        val mid = size.height / 2
        // 기준선과 정차 지점 눈금
        drawLine(Palette.Line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 2.dp.toPx())
        model.ticks.forEach { t ->
            val x = (t.fraction * size.width).toFloat()
            drawLine(Palette.Line, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
        val h = 14.dp.toPx()
        row.spans.forEach { span ->
            val x0 = (span.start * size.width).toFloat()
            val x1 = (span.endInclusive * size.width).toFloat().coerceAtLeast(x0 + 4.dp.toPx())
            drawRoundRect(fill, topLeft = Offset(x0, mid - h / 2), size = Size(x1 - x0, h), cornerRadius = CornerRadius(h / 2, h / 2))
        }
    }
}

/** 아래 눈금 이름: 출발 · 정차 지점들 · 총 거리. 겹치는 이름은 생략합니다. */
@Composable
private fun Axis(model: TimelineModel, modifier: Modifier) {
    BoxWithConstraints(modifier.height(32.dp)) {
        val w = maxWidth
        Text("출발", style = Type.Caption, color = Palette.TextSecondary, modifier = Modifier.align(Alignment.CenterStart))
        Text(Formatting.km(model.totalKm), style = Type.Caption, color = Palette.TextSecondary,
            modifier = Modifier.align(Alignment.CenterEnd))
        var lastShown = 0.0
        model.ticks.forEach { t ->
            if (t.fraction - lastShown >= 0.18 && 1.0 - t.fraction >= 0.14) {
                lastShown = t.fraction
                Box(Modifier.offset(x = w * t.fraction.toFloat() - 40.dp).width(80.dp).align(Alignment.CenterStart)) {
                    Text(t.label, style = Type.Caption, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
