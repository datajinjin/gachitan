package kr.gachitan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.gachitan.core.Formatting
import kr.gachitan.core.TripLog
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Type

/** 두 번째 구간 색. 블루와 구분되는 옅은 블루(형태 구분용, 글자 아님). */

/**
 * 운행 구간 막대. 막대 길이는 거리 비율 그대로입니다(30km : 50km면 37.5% : 62.5%).
 * 위에는 인원이 바뀐 지점(누가 내리고 탔는지), 아래에는 구간 거리와 인원을 적습니다.
 */
@Composable
fun SegmentBar(bands: List<TripLog.Band>, modifier: Modifier = Modifier) {
    val total = bands.sumOf { it.km }
    if (bands.isEmpty() || total <= 0.0) return
    val desc = bands.joinToString(", ") { "${Formatting.km(it.km)} ${it.people}명" }
    Column(modifier.fillMaxWidth().semantics { contentDescription = "구간: $desc" }) {
        // 인원이 바뀐 지점
        if (bands.any { it.changeBefore != null }) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                bands.forEach { b ->
                    Box(Modifier.weight(weightOf(b.km, total))) {
                        b.changeBefore?.let {
                            Text(it, style = Type.Caption, color = Palette.Text, maxLines = 1, softWrap = false,
                                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = (-10).dp))
                        }
                    }
                }
            }
        }
        // 막대
        Row(Modifier.fillMaxWidth().height(16.dp), verticalAlignment = Alignment.CenterVertically) {
            bands.forEachIndexed { i, b ->
                Box(Modifier.weight(weightOf(b.km, total)).height(16.dp), contentAlignment = Alignment.CenterStart) {
                    val shape = when {
                        bands.size == 1 -> CircleShape
                        i == 0 -> RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)
                        i == bands.lastIndex -> RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    Box(Modifier.fillMaxWidth().height(12.dp).clip(shape).background(if (i % 2 == 0) Palette.Accent else Palette.BandAlt))
                    if (i > 0) {
                        Box(Modifier.offset(x = (-9).dp).size(18.dp).clip(CircleShape).background(Palette.Surface)
                            .border(3.dp, Palette.Accent, CircleShape))
                    }
                }
            }
        }
        // 구간 거리 · 인원
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Start) {
            bands.forEach { b ->
                Text("${Formatting.km(b.km)} · ${b.people}명", style = Type.Caption, color = Palette.TextSecondary,
                    textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.weight(weightOf(b.km, total)).padding(horizontal = 2.dp))
            }
        }
    }
}

/** Compose의 weight는 0을 받지 않습니다. 비율은 그대로 두고 0만 피합니다. */
private fun weightOf(km: Double, total: Double): Float = (km / total).toFloat().coerceAtLeast(0.0001f)
