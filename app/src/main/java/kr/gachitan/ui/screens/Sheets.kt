package kr.gachitan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kr.gachitan.core.ExpenseCategory
import kr.gachitan.core.Formatting
import kr.gachitan.core.Member
import kr.gachitan.ui.components.ChipRow
import kr.gachitan.ui.components.ChoiceChip
import kr.gachitan.ui.components.InputField
import kr.gachitan.ui.components.PrimaryButton
import kr.gachitan.ui.components.QrCode
import kr.gachitan.ui.components.QuietButton
import kr.gachitan.ui.components.SecondaryButton
import kr.gachitan.ui.components.SectionLabel
import kr.gachitan.ui.components.Sheet
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Type

/**
 * 이름 붙이기. 센서는 '앉았다'만 알기 때문에 누가 앉았는지는 사람이 정합니다.
 * 최근 동승자를 먼저 보여 줘 키보드를 쓰지 않고 한 번에 고를 수 있게 합니다.
 *
 * @param suggested 정차 후 다시 앉아 앞사람 이름을 제안한 경우 그 이름. "맞아요" 한 번으로 확인합니다.
 */
@Composable
fun NameSheet(
    title: String,
    current: String?,
    suggested: String?,
    recent: List<String>,
    onSave: (String) -> Unit,
    onConfirmSuggested: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** 같은 이름이 다른 좌석에 붙어 이름이 떨어진 경우: 이름 없이 두는 것도 허용합니다. */
    reviewUnnamed: Boolean = false
) {
    var name by remember { mutableStateOf(if (suggested != null) "" else current.orEmpty()) }
    Sheet(title = title, onDismiss = onDismiss, actions = {
        if (current != null && suggested == null) QuietButton("이름 지우기", { onSave("") })
        if (current == null && reviewUnnamed) QuietButton("이름 없이 두기", { onSave("") })
        Spacer(Modifier.weight(1f))
        SecondaryButton("취소", onDismiss)
        PrimaryButton("저장", { if (name.isNotBlank()) onSave(name) }, enabled = name.isNotBlank())
    }) {
        if (reviewUnnamed && suggested == null) {
            Text("같은 이름이 다른 좌석에도 있어 이 좌석의 이름을 뗐어요.", style = Type.Body, color = Palette.TextSecondary)
        }
        if (suggested != null && onConfirmSuggested != null) {
            Text("정차한 뒤 이 좌석에 다시 앉았어요. 같은 분인가요?", style = Type.Body, color = Palette.TextSecondary)
            PrimaryButton("$suggested 맞아요", onConfirmSuggested, Modifier.fillMaxWidth())
            SectionLabel("다른 분이면 이름을 고르세요")
        }
        val choices = recent.filter { it != suggested }.take(8)
        if (choices.isNotEmpty()) {
            ChipRow { choices.forEach { r -> ChoiceChip(r, selected = r == name, onClick = { onSave(r) }) } }
        }
        InputField(name, { name = it.take(12) }, "이름 (12자까지)")
    }
}

/**
 * 통행료·주차비(운행 중 정차 화면). 나눌 사람의 기본값은 방금 달려온 구간에 타고 있던 사람입니다.
 * 중간에 내린 사람은 그 뒤 톨게이트 비용에 자동으로 빠집니다. 결제는 운전자가 한 것으로 봅니다.
 */
@Composable
fun ExtraSheet(
    participants: Map<String, String>,
    defaultPayers: Set<String>,
    initialLabel: String = ExpenseCategory.TOLL.label,
    initialAmount: Long? = null,
    initialPayers: Set<String>? = null,
    editing: Boolean = false,
    onSave: (label: String, amount: Long, payers: Set<String>) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var category by remember { mutableStateOf(ExpenseCategory.of(initialLabel)) }
    var custom by remember { mutableStateOf(if (ExpenseCategory.of(initialLabel) == ExpenseCategory.OTHER) initialLabel else "") }
    var amount by remember { mutableStateOf(initialAmount?.toString().orEmpty()) }
    var payers by remember { mutableStateOf(initialPayers ?: defaultPayers.ifEmpty { participants.keys }) }
    val label = if (category == ExpenseCategory.OTHER) custom.trim().ifEmpty { ExpenseCategory.OTHER.label } else category.label
    val won = amount.toLongOrNull()
    val valid = won != null && won > 0 && payers.isNotEmpty()
    Sheet(title = if (editing) "$initialLabel 나누기" else "통행료·주차비", onDismiss = onDismiss, actions = {
        if (onDelete != null) QuietButton("삭제", onDelete, color = Palette.Caution)
        Spacer(Modifier.weight(1f))
        SecondaryButton("취소", onDismiss)
        PrimaryButton(if (editing) "저장" else "추가", { if (valid && won != null) onSave(label, won, payers) }, enabled = valid)
    }) {
        if (!editing) {
            ChipRow { ExpenseCategory.rideExtras.forEach { c -> ChoiceChip(c.label, category == c, { category = c }) } }
            if (category == ExpenseCategory.OTHER) InputField(custom, { custom = it.take(14) }, "무엇에 쓴 돈인가요? (예: 터널 통행료)")
            AmountInput(amount, { amount = it }, max = 7, quick = listOf(1_000L, 5_000L, 10_000L))
        } else {
            Text(Formatting.won(initialAmount ?: 0), style = Type.Figure, color = Palette.Text)
        }
        SectionLabel("나눌 사람 ${payers.size}명")
        ChipRow {
            participants.forEach { (key, name) ->
                val on = key in payers
                ChoiceChip(name, on, { payers = if (on) payers - key else payers + key })
            }
        }
        Text(
            buildString {
                if (won != null && won > 0 && payers.isNotEmpty()) append("1인 약 ${Formatting.won(won / payers.size)} · ")
                append("방금 구간 탑승자 자동 선택")
            },
            style = Type.Caption, color = Palette.TextSecondary
        )
    }
}

/** 금액 입력 + 빠른 더하기 버튼(흔들리는 차 안에서 키보드 입력을 줄입니다). */
@Composable
internal fun AmountInput(value: String, onChange: (String) -> Unit, max: Int, quick: List<Long>) {
    InputField(value, { v -> onChange(v.filter(Char::isDigit).take(max)) }, "금액(원)", number = true, style = Type.BodyNum)
    ChipRow {
        quick.forEach { q ->
            ChoiceChip("+${Formatting.won(q)}", false, {
                val next = (value.toLongOrNull() ?: 0L) + q
                onChange(next.toString().take(max))
            })
        }
        if (value.isNotEmpty()) ChoiceChip("지우기", false, { onChange("") })
    }
}

/** 짧은 이름 입력(장소 이름, 묶음 이름, 사람 추가). */
@Composable
fun TextSheet(
    title: String,
    placeholder: String,
    presets: List<String>,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    body: String? = null
) {
    var text by remember { mutableStateOf("") }
    Sheet(title = title, onDismiss = onDismiss, actions = {
        Spacer(Modifier.weight(1f))
        SecondaryButton("취소", onDismiss)
        PrimaryButton(confirm, { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank())
    }) {
        body?.let { Text(it, style = Type.Body, color = Palette.TextSecondary) }
        if (presets.isNotEmpty()) ChipRow { presets.forEach { p -> ChoiceChip(p, text == p, { text = p }) } }
        InputField(text, { text = it.take(30) }, placeholder)
    }
}

/**
 * 여행의 공동 경비(숙박·식비 등). 낸 사람과 나눌 사람을 따로 고릅니다.
 * 낸 사람은 받을 돈이 늘고, 나눌 사람은 그만큼 부담이 늘어 마지막 송금 목록에 함께 정리됩니다.
 */
@Composable
fun ExpenseSheet(
    members: List<Member>,
    onSave: (label: String, amount: Long, payerId: String, participants: Set<String>) -> Unit,
    onDismiss: () -> Unit,
    defaultPayerId: String? = null
) {
    var category by remember { mutableStateOf(ExpenseCategory.FOOD) }
    var custom by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var payer by remember { mutableStateOf(defaultPayerId?.takeIf { id -> members.any { it.id == id } } ?: members.firstOrNull()?.id) }
    var participants by remember { mutableStateOf(members.map { it.id }.toSet()) }
    val label = if (category == ExpenseCategory.OTHER) custom.trim().ifEmpty { ExpenseCategory.OTHER.label } else category.label
    val won = amount.toLongOrNull()
    val valid = won != null && won > 0 && payer != null && participants.isNotEmpty()
    val allOn = participants.size == members.size
    Sheet(title = "공동 경비", onDismiss = onDismiss, actions = {
        Spacer(Modifier.weight(1f))
        SecondaryButton("취소", onDismiss)
        PrimaryButton("추가", {
            val pid = payer
            if (valid && won != null && pid != null) onSave(label, won, pid, participants)
        }, enabled = valid)
    }) {
        ChipRow { ExpenseCategory.journeyExpenses.forEach { c -> ChoiceChip(c.label, category == c, { category = c }) } }
        if (category == ExpenseCategory.OTHER) InputField(custom, { custom = it.take(14) }, "무엇에 쓴 돈인가요? (예: 장보기)")
        AmountInput(amount, { amount = it }, max = 8, quick = listOf(5_000L, 10_000L, 50_000L))
        SectionLabel("낸 사람")
        ChipRow { members.forEach { m -> ChoiceChip(m.name, payer == m.id, { payer = m.id }) } }
        SectionLabel("나눌 사람 ${participants.size}명")
        ChipRow {
            ChoiceChip(if (allOn) "모두 해제" else "모두", allOn, { participants = if (allOn) emptySet() else members.map { it.id }.toSet() })
            members.forEach { m ->
                val on = m.id in participants
                ChoiceChip(m.name, on, { participants = if (on) participants - m.id else participants + m.id })
            }
        }
        val payerName = members.firstOrNull { it.id == payer }?.name
        if (won != null && won > 0 && participants.isNotEmpty() && payerName != null) {
            Text("1인 약 ${Formatting.won(won / participants.size)} · ${payerName} 결제",
                style = Type.Caption, color = Palette.TextSecondary)
        }
        Text("주유·충전비는 차량 비용에 이미 포함돼요.", style = Type.Caption, color = Palette.TextSecondary)
    }
}

/** 휴대폰으로 받아 갈 QR. 앱 설치·가입 없이 카메라로 찍기만 하면 됩니다. */
@Composable
fun QrSheet(title: String, payload: String, caption: String, onDismiss: () -> Unit) {
    Sheet(title = title, onDismiss = onDismiss, actions = {
        Spacer(Modifier.weight(1f))
        PrimaryButton("닫기", onDismiss)
    }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(
            kr.gachitan.ui.theme.Space.m)) {
            QrCode(payload, description = title)
            Text(caption, style = Type.Caption, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ConfirmSheet(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    Sheet(title = title, onDismiss = onDismiss, actions = {
        Spacer(Modifier.weight(1f))
        SecondaryButton("취소", onDismiss)
        if (danger) SecondaryButton(confirm, onConfirm) else PrimaryButton(confirm, onConfirm)
    }) {
        Text(body, style = Type.Body, color = Palette.TextSecondary)
    }
}
