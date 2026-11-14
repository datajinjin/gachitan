package kr.gachitan.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import kr.gachitan.core.Formatting
import kr.gachitan.core.PaymentRequest
import kr.gachitan.core.TripLog
import kr.gachitan.data.AppSettings
import kr.gachitan.ui.components.Avatar
import kr.gachitan.ui.components.ChipRow
import kr.gachitan.ui.components.ChoiceChip
import kr.gachitan.ui.components.HLine
import kr.gachitan.ui.components.QrCode
import kr.gachitan.ui.components.VLine
import kr.gachitan.ui.model.QR_PRIVACY
import kr.gachitan.ui.model.qrContentsText
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type

/**
 * 정산 공유: 정산 화면 위에 여는 큰 패널(메뉴 항목이 아님).
 * 한 사람의 이름·탄 거리·부담액과, 그 사람 휴대폰으로 받아 갈 실제 QR을 보여 줍니다.
 * QR 내용은 [PaymentRequest.payload]가 만들고, 그 사람 몫만 담깁니다.
 */
@Composable
fun SharePanel(
    log: TripLog,
    riderId: String,
    settings: AppSettings,
    wide: Boolean,
    onSelect: (String) -> Unit,
    onClose: () -> Unit
) {
    val payers = log.payers
    val share = payers.firstOrNull { it.riderId == riderId } ?: payers.firstOrNull() ?: return
    val payload = remember(share, settings.tossId, settings.account, settings.driverName) {
        PaymentRequest.payload(share.name, share.distanceKm, share.amountWon, settings.driverName.ifBlank { "운전자" },
            settings.tossId, settings.account)
    }

    // 뒤로 가기 버튼은 패널만 닫습니다.
    BackHandler(onBack = onClose)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 뒤 화면을 낮추는 막. 누르면 닫힙니다.
        Box(Modifier.fillMaxSize().background(Palette.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .semantics { contentDescription = "공유 닫기" })
        val panelWidth = if (wide) maxWidth * 0.78f else maxWidth
        Column(
            Modifier.align(Alignment.CenterEnd).width(panelWidth).fillMaxHeight()
                .clip(RoundedCornerShape(topStart = if (wide) 32.dp else 0.dp, bottomStart = if (wide) 32.dp else 0.dp))
                .background(Palette.Surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = if (wide) Space.xxl else Space.l, vertical = Space.l)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("정산 공유", style = Type.Title, color = Palette.Text, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = "닫기" },
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Close, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(36.dp)) }
            }
            Spacer(Modifier.height(Space.s))
            HLine()
            Spacer(Modifier.height(Space.l))

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (wide) {
                    val qrSize = min(maxHeight - 48.dp, maxWidth * 0.44f).coerceAtLeast(240.dp)
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                            Person(share.name, share.amountWon, share.distanceKm, big = true)
                            HLine()
                            Text(qrContentsText(share.name, settings.tossId, settings.account), style = Type.Body, color = Palette.TextSecondary)
                            Text(QR_PRIVACY, style = Type.Caption, color = Palette.TextSecondary)
                        }
                        Spacer(Modifier.width(Space.xl))
                        VLine(Modifier.fillMaxHeight().padding(vertical = Space.l))
                        Spacer(Modifier.width(Space.xl))
                        QrBlock(payload, qrSize, "${share.name}님 송금 QR")
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l),
                        horizontalAlignment = Alignment.Start) {
                        Person(share.name, share.amountWon, share.distanceKm, big = false)
                        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            QrBlock(payload, min(maxWidth, 400.dp), "${share.name}님 송금 QR")
                        }
                        Text(qrContentsText(share.name, settings.tossId, settings.account), style = Type.Body, color = Palette.TextSecondary)
                        Text(QR_PRIVACY, style = Type.Caption, color = Palette.TextSecondary)
                    }
                }
            }

            if (payers.size > 1) {
                Spacer(Modifier.height(Space.m))
                HLine()
                Spacer(Modifier.height(Space.m))
                Text("다른 사람 선택", style = Type.Label, color = Palette.TextSecondary)
                Spacer(Modifier.height(Space.xs))
                ChipRow {
                    payers.forEach { p ->
                        ChoiceChip("${p.name} · ${Formatting.won(p.amountWon)}", p.riderId == share.riderId, { onSelect(p.riderId) },
                            Modifier.height(64.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Person(name: String, amountWon: Long, km: Double, big: Boolean) {
    val amountSize = if (big) 104.sp else 80.sp
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(72.dp, name = name)
            Spacer(Modifier.width(Space.m))
            Text(name, style = Type.Hero, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            buildAnnotatedString {
                append("%,d".format(amountWon))
                withStyle(SpanStyle(fontSize = amountSize * 0.5f)) { append("원") }
            },
            style = Type.display(amountSize), color = Palette.Accent, maxLines = 1
        )
        Text("${Formatting.km(km)} 탑승", style = Type.Title, color = Palette.TextSecondary)
    }
}

/** 큰 흰 여백을 둔 QR. 흰 바탕이 카메라 인식에 유리합니다. */
@Composable
private fun QrBlock(payload: String, size: Dp, description: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        QrCode(payload, size = size, description = description)
        Spacer(Modifier.height(Space.s))
        Text("휴대폰 카메라로 찍어 주세요", style = Type.Label, color = Palette.TextSecondary, textAlign = TextAlign.Center)
    }
}
