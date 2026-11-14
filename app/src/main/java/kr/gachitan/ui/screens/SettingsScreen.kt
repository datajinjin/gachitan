package kr.gachitan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.gachitan.core.EnergyKind
import kr.gachitan.ui.MainViewModel
import kr.gachitan.ui.UiState
import kr.gachitan.ui.components.ChipRow
import kr.gachitan.ui.components.ChoiceChip
import kr.gachitan.ui.components.InputField
import kr.gachitan.ui.components.QuietButton
import kr.gachitan.ui.components.SecondaryButton
import kr.gachitan.ui.components.SectionLabel
import kr.gachitan.ui.components.StateHeader
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type
import kr.gachitan.data.ThemeMode
import kr.gachitan.vehicle.SignalStatus
import kr.gachitan.vehicle.SignalText

@Composable
fun SettingsScreen(ui: UiState, vm: MainViewModel, expanded: Boolean) {
    val s = ui.settings
    var confirmWipe by rememberSaveable { mutableStateOf(false) }
    var showSignals by rememberSaveable { mutableStateOf(false) }
    val unit = if (s.energyKind == EnergyKind.ELECTRIC) "kWh" else "L"

    val driverGroup: @Composable () -> Unit = {
        Group("운전자") {
            Field("내 이름", s.driverName, "이름") { v -> vm.updateSettings { it.copy(driverName = v.take(12)) } }
            SectionLabel("운전자도 나눠 낼까요?")
            ChipRow {
                ChoiceChip("함께 나눔", s.driverPaysShare, { vm.updateSettings { it.copy(driverPaysShare = true) } })
                ChoiceChip("동승자만 나눔", !s.driverPaysShare, { vm.updateSettings { it.copy(driverPaysShare = false) } })
            }
        }
    }
    val costGroup: @Composable () -> Unit = {
        Group("비용") {
            ChipRow {
                ChoiceChip("전기차", s.energyKind == EnergyKind.ELECTRIC, { vm.setEnergyKind(EnergyKind.ELECTRIC) })
                ChoiceChip("내연기관·하이브리드", s.energyKind == EnergyKind.FUEL, { vm.setEnergyKind(EnergyKind.FUEL) })
            }
            SectionLabel(if (s.energyKind == EnergyKind.ELECTRIC) "자주 쓰는 충전 단가" else "자주 쓰는 주유 단가")
            ChipRow {
                pricePresets(s.energyKind).forEach { (label, won) ->
                    ChoiceChip(label, s.unitPriceWon == won, { vm.updateSettings { it.copy(unitPriceWon = won) } })
                }
            }
            NumberField("단가 (원/$unit)", s.unitPriceWon) { v -> vm.updateSettings { it.copy(unitPriceWon = v) } }
            NumberField(if (s.energyKind == EnergyKind.ELECTRIC) "배터리 용량 (kWh)" else "연료탱크 용량 (L)", s.capacityUnits ?: 0.0) { v ->
                vm.updateSettings { it.copy(capacityUnits = v.takeIf { x -> x > 0 }) }
            }
            NumberField("기본 효율 (km/$unit)", s.fallbackKmPerUnit) { v -> if (v > 0) vm.updateSettings { it.copy(fallbackKmPerUnit = v) } }
            Text("차량 효율 → 잔량 감소 → 기본 효율 순으로 계산해요.",
                style = Type.Caption, color = Palette.TextSecondary)
            SectionLabel("차량 유지비 (타이어·소모품 등)")
            ChipRow {
                listOf(0.0 to "안 받음", 30.0 to "km당 30원", 50.0 to "km당 50원", 100.0 to "km당 100원").forEach { (w, label) ->
                    ChoiceChip(label, s.wearWonPerKm == w, { vm.updateSettings { it.copy(wearWonPerKm = w) } })
                }
            }
            SectionLabel("동승자 금액 단위")
            ChipRow {
                listOf(1L, 10L, 100L).forEach { u ->
                    ChoiceChip("${u}원", s.roundingUnitWon == u, { vm.updateSettings { it.copy(roundingUnitWon = u) } })
                }
            }
        }
    }
    val screenGroup: @Composable () -> Unit = {
        Group("화면") {
            ChipRow {
                ThemeMode.entries.forEach { m ->
                    ChoiceChip(m.label, s.themeMode == m, { vm.updateSettings { it.copy(themeMode = m) } })
                }
            }
        }
    }
    val payGroup: @Composable () -> Unit = {
        Group("받는 곳") {
            Field("토스 아이디", s.tossId, "선택") { v -> vm.updateSettings { it.copy(tossId = v.trim().take(40)) } }
            Field("계좌 안내", s.account, "선택 · 예: ○○은행 123-…") { v -> vm.updateSettings { it.copy(account = v.take(40)) } }
            Field("정산 화면 주소", s.viewerBaseUrl, "선택 · https://로 시작하는 정산 화면 주소") { v -> vm.updateSettings { it.copy(viewerBaseUrl = v.trim().take(120)) } }
            Text("결제는 처리하지 않고, 서버에 저장하지 않아요.",
                style = Type.Caption, color = Palette.TextSecondary)
        }
    }
    val dataGroup: @Composable () -> Unit = {
        Group("기록") {
            Text(if (ui.session.trips.isEmpty()) "아직 정산 기록이 없어요." else "정산 기록 ${ui.session.trips.size}건",
                style = Type.Body, color = Palette.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SecondaryButton("기록 지우기", vm::clearHistory)
                SecondaryButton("내 데이터 모두 지우기", { confirmWipe = true })
            }
            Text("이 차 안에만 암호화해 저장해요.", style = Type.Caption, color = Palette.TextSecondary)
        }
    }
    // 아래 두 묶음은 시연·문제 확인용입니다. 일반 사용 흐름(탑승·정산·기록)에는 나오지 않습니다.
    val demoGroup: @Composable () -> Unit = {
        Group("시연 모드") {
            ChipRow {
                ChoiceChip("실제 차량", !s.demoMode, { vm.setDemoMode(false) })
                ChoiceChip("시뮬레이터", s.demoMode, { vm.setDemoMode(true) })
            }
            Text("시뮬레이터 기록은 따로 저장돼요.",
                style = Type.Caption, color = Palette.TextSecondary)
        }
    }
    val signalGroup: @Composable () -> Unit = {
        Group("차량 연결 상태") {
            val ok = ui.statuses.count { it.connected }
            Text(
                when {
                    s.demoMode -> "시뮬레이터 사용 중"
                    ui.statuses.isEmpty() -> "아직 받은 신호가 없습니다."
                    else -> "차량 신호 ${ui.statuses.size}개 중 ${ok}개 연결"
                },
                style = Type.Body, color = Palette.TextSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SecondaryButton(if (showSignals) "접기" else "자세히 보기", { showSignals = !showSignals })
                SecondaryButton("다시 연결", vm::reconnect)
            }
            if (showSignals) ui.statuses.forEach { st -> SignalRow(st) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        StateHeader("설정", null) { QuietButton("완료", vm::closeSettings, color = Palette.AccentText) }
        Spacer(Modifier.height(Space.l))
        if (expanded) {
            // 넓은 화면: 두 칸으로 나눠 스크롤을 줄입니다. 왼쪽은 정산 규칙, 오른쪽은 받는 곳·데이터·연결.
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                    driverGroup(); costGroup(); screenGroup()
                    Spacer(Modifier.height(Space.l))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.xl)) {
                    payGroup(); dataGroup(); demoGroup(); signalGroup()
                    Spacer(Modifier.height(Space.l))
                }
            }
        } else {
            Column(
                Modifier.weight(1f).widthIn(max = 760.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.xl)
            ) {
                driverGroup(); costGroup(); payGroup(); screenGroup(); dataGroup(); demoGroup(); signalGroup()
                Spacer(Modifier.height(Space.l))
            }
        }
    }

    if (confirmWipe) ConfirmSheet("모든 데이터를 지울까요?", "정산 기록, 동승자 이름, 여행, 설정이 모두 삭제됩니다. 운전자가 바뀌었을 때 쓰세요.",
        "모두 지우기", { vm.wipeAllData(); confirmWipe = false }, { confirmWipe = false }, danger = true)
}

/** 신호 한 줄: 이름 · 받은 값(사람이 읽는 말) 또는 대신 쓰는 방법. */
@Composable
private fun SignalRow(st: SignalStatus) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(if (st.connected) "●" else "○", style = Type.Caption, color = if (st.connected) Palette.Accent else Palette.Caution,
            modifier = Modifier.width(32.dp))
        Text(st.name, style = Type.Caption, color = Palette.Text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(Space.m))
        val detail = when {
            st.connected -> SignalText.friendlyValue(st.name, st.lastRawValue).orEmpty()
            st.error != null -> SignalText.friendlyError(st.name, st.error)
            else -> "연결 중"
        }
        Text(detail, style = Type.Caption, color = if (st.connected) Palette.TextSecondary else Palette.Caution,
            textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
    }
}

/** 단가 빠른 선택. 실제 단가는 충전소·주유소마다 다르니 예시값이며, 칸에서 직접 고칠 수 있습니다. */
private fun pricePresets(kind: EnergyKind): List<Pair<String, Double>> = when (kind) {
    EnergyKind.ELECTRIC -> listOf("집 완속 200원" to 200.0, "공용 완속 300원" to 300.0, "급속 350원" to 350.0)
    EnergyKind.FUEL -> listOf("휘발유 1,700원" to 1700.0, "경유 1,600원" to 1600.0, "LPG 1,050원" to 1050.0)
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title, style = Type.Heading, color = Palette.Text)
        content()
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body, color = Palette.TextSecondary, modifier = Modifier.width(200.dp))
        InputField(text, { text = it; onChange(it) }, placeholder, Modifier.weight(1f))
    }
}

@Composable
private fun NumberField(label: String, value: Double, onChange: (Double) -> Unit) {
    fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    var text by remember { mutableStateOf(fmt(value)) }
    // 바깥에서 값이 바뀌면(예: 에너지 종류 변경) 입력 칸도 맞춥니다. 입력 중인 "3." 같은 값은 건드리지 않습니다.
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = fmt(value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Body, color = Palette.TextSecondary, modifier = Modifier.width(200.dp))
        InputField(text, { v ->
            text = v.filter { it.isDigit() || it == '.' }.take(8)
            text.toDoubleOrNull()?.let(onChange)
        }, "숫자", Modifier.weight(1f), number = true, style = Type.BodyNum)
    }
}
