package kr.gachitan.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kr.gachitan.ui.theme.Palette
import kr.gachitan.ui.theme.Space
import kr.gachitan.ui.theme.Type

// ---------------------------------------------------------------------- 버튼
// 버튼은 네 단계뿐입니다. 한 화면의 Primary는 하나만 둡니다.
//   Primary   : 블루로 채운 큰 버튼(80dp)
//   Secondary : 흰 면 + 테두리(64dp)
//   TextLink  : 블루 글자 + › (계산 근거, 기록 수정처럼 다른 화면으로 넘어가는 보조 행동)
//   Quiet     : 회색 글자(드문 행동)

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Space.radius)
    Box(
        modifier
            .heightIn(min = Space.touchPrimary)
            .clip(shape)
            .background(if (enabled) Palette.Accent else Palette.SurfaceAlt)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = Type.Heading, color = if (enabled) Palette.OnAccent else Palette.TextSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(Space.radius)
    Box(
        modifier
            .heightIn(min = Space.touchSecondary)
            .clip(shape)
            .background(Palette.Surface)
            .border(1.5.dp, if (enabled) Palette.Outline else Palette.Line, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = Type.Label, color = if (enabled) Palette.Text else Palette.TextSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** 다른 화면·패널로 넘어가는 보조 행동. 터치 영역은 64dp 이상입니다. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.heightIn(min = Space.touchSecondary).clip(RoundedCornerShape(Space.radius))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = Type.Label, color = Palette.AccentText, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.AccentText, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Palette.TextSecondary) {
    Box(
        modifier
            .heightIn(min = Space.touchSecondary)
            .clip(RoundedCornerShape(Space.radius))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.CenterStart
    ) { Text(text, style = Type.Label, color = color, maxLines = 1) }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    Box(
        Modifier.size(Space.touchSecondary).clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "설정" },
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Filled.Settings, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(32.dp)) }
}

// ---------------------------------------------------------------------- 앱 틀: 상단바·하단 메뉴

/** 상태 표시. 테두리만 있는 알약 모양이고, 기록 중일 때만 점이 붙습니다. */
@Composable
fun StatusPill(text: String, live: Boolean = false, caution: Boolean = false) {
    val color = if (caution) Palette.Caution else Palette.AccentText
    Row(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).border(1.5.dp, color, CircleShape).padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.Accent))
            Spacer(Modifier.width(Space.xs))
        }
        Text(text, style = Type.Label, color = color, maxLines = 1)
    }
}

/**
 * 앱 맨 위 한 줄: 앱 이름 · (여행 이름) · 상태 · 설정.
 * 주행 중에는 설정을 숨깁니다(누를 것을 두지 않음).
 */
@Composable
fun AppTopBar(
    status: String,
    live: Boolean,
    demo: Boolean,
    onSettings: (() -> Unit)?,
    caution: Boolean = false,
    trailingExtra: @Composable RowScope.() -> Unit = {}
) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("같이 탄 만큼", style = Type.Title, color = Palette.Text, maxLines = 1)
            if (demo) {
                Spacer(Modifier.width(Space.s))
                Text("시뮬레이터", style = Type.Caption, color = Palette.Caution, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.CautionFill).padding(horizontal = 10.dp, vertical = 2.dp))
            }
            // 여행 이름처럼 길어질 수 있는 것은 남는 폭 안에서 줄여 보이고, 상태·설정은 항상 보입니다.
            Spacer(Modifier.width(Space.m))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                content = trailingExtra)
            Spacer(Modifier.width(Space.s))
            StatusPill(status, live, caution)
            if (onSettings != null) {
                Spacer(Modifier.width(Space.xs))
                SettingsButton(onSettings)
            }
        }
        Spacer(Modifier.height(Space.s))
        HLine()
    }
}

/** 정차 중 하단 메뉴. 선택된 항목은 블루 글자 + 밑줄. */
@Composable
fun BottomNav(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HLine()
        Row(Modifier.widthIn(max = 960.dp).fillMaxWidth()) {
            items.forEachIndexed { i, label ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).heightIn(min = 80.dp)
                        .clickable(role = Role.Tab) { onSelect(i) }
                        .semantics { this.selected = on },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(label, style = Type.Heading, color = if (on) Palette.AccentText else Palette.TextSecondary, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(96.dp).height(4.dp).clip(CircleShape).background(if (on) Palette.Accent else Color.Transparent))
                }
            }
        }
    }
}

@Composable
fun HLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}

@Composable
fun VLine(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).background(Palette.Line))
}

/**
 * 사람 표시. 사진·얼굴은 쓰지 않습니다. 이름이 있으면 첫 글자와 사람별 색, 없으면 사람 아이콘입니다.
 */
@Composable
fun Avatar(size: Dp = 56.dp, filled: Boolean = false, name: String? = null) {
    val initial = name?.trim()?.takeIf { it.isNotEmpty() && it != "이름 없음" }?.let { it.first().uppercaseChar().toString() }
    if (initial != null && !filled) {
        Box(
            Modifier.size(size).clip(CircleShape).background(Palette.personFill(name.orEmpty())),
            contentAlignment = Alignment.Center
        ) {
            Text(initial, style = Type.Heading.copy(fontSize = Type.Heading.fontSize * (size.value / 56f)),
                color = Palette.personInk(name.orEmpty()), maxLines = 1)
        }
        return
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(if (filled) Palette.Accent else Palette.AccentFill),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Person, contentDescription = null, tint = if (filled) Palette.OnAccent else Palette.AccentText,
            modifier = Modifier.size(size * 0.62f))
    }
}

// ---------------------------------------------------------------------- 머리글·안내

/** 화면 안의 제목 줄(설정·묶음 같은 하위 화면). */
@Composable
fun StateHeader(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    live: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(modifier.fillMaxWidth().heightIn(min = Space.touchSecondary), verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.Accent))
            Spacer(Modifier.width(Space.s))
        }
        Text(title, style = Type.Title, color = Palette.Text, maxLines = 1)
        // 설명은 남는 폭을 모두 차지해 오른쪽 행동(닫기·완료)이 항상 오른쪽 끝에 붙게 합니다.
        if (detail != null) {
            Spacer(Modifier.width(Space.m))
            Text(detail, style = Type.LabelNum, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing()
    }
}

/** 사람이 확인해야 할 것, 연결 문제. 주의색은 여기에만 씁니다. */
@Composable
fun Notice(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Space.radius)).background(Palette.CautionFill)
            .let { if (onAction != null) it.clickable(role = Role.Button, onClick = onAction) else it }
            .heightIn(min = Space.touchSecondary)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = Type.Label, color = Palette.Caution, modifier = Modifier.weight(1f), maxLines = 3,
            overflow = TextOverflow.Ellipsis)
        if (action != null) {
            Spacer(Modifier.width(Space.m))
            Text(action, style = Type.Label, color = Palette.Text, maxLines = 1)
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.Label, color = Palette.TextSecondary, modifier = modifier.padding(bottom = Space.xs))
}

/** 이름 — 값 한 줄. 값은 오른쪽 정렬, 고정폭 숫자. */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, note: String? = null, strong: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.Body, color = if (strong) Palette.Text else Palette.TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = Type.Caption, color = Palette.TextSecondary, maxLines = 3)
        }
        Spacer(Modifier.width(Space.m))
        Text(value, style = if (strong) Type.Heading else Type.BodyNum, color = Palette.Text, maxLines = 1)
    }
}

// ---------------------------------------------------------------------- 선택·입력

@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Space.radius)
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) Palette.AccentFill else Palette.Surface)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) Palette.Accent else Palette.Line, shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.Center
    ) { Text(text, style = Type.Label, color = if (selected) Palette.AccentText else Palette.Text, maxLines = 1) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) { content() }
}

@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    style: TextStyle = Type.Body
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = Palette.Text),
        cursorBrush = SolidColor(Palette.Accent),
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = Space.touchSecondary).clip(RoundedCornerShape(Space.radius))
                    .background(Palette.Surface).border(1.5.dp, Palette.Outline, RoundedCornerShape(Space.radius))
                    .padding(horizontal = Space.m),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) Text(placeholder, style = style, color = Palette.TextSecondary, maxLines = 1)
                inner()
            }
        }
    )
}

// ---------------------------------------------------------------------- 시트(대화상자)

/** 정차 중에만 여는 작은 작업 창. 주 행동은 오른쪽 아래 하나입니다. */
@Composable
fun Sheet(
    title: String,
    onDismiss: () -> Unit,
    maxWidth: Dp = 760.dp,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(Space.l).widthIn(max = maxWidth).fillMaxWidth()
                .clip(RoundedCornerShape(Space.radiusPanel)).background(Palette.Surface)
                .padding(Space.xl)
        ) {
            Text(title, style = Type.Title, color = Palette.Text)
            Spacer(Modifier.size(Space.l))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.m),
                content = content
            )
            Spacer(Modifier.size(Space.l))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically,
                content = actions)
        }
    }
}
