package kr.gachitan.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kr.gachitan.ui.components.PrimaryButton
import kr.gachitan.ui.components.SecondaryButton
import kr.gachitan.ui.components.VLine
import kr.gachitan.ui.theme.Space

/** 화면 아래 행동. 한 화면의 주 행동(primary)은 하나입니다. */
data class Action(val text: String, val onClick: () -> Unit, val primary: Boolean = false, val enabled: Boolean = true)

/**
 * 여행(묶음) 화면의 틀. 탑승·정산 화면과 같은 규칙을 씁니다.
 * - 넓은 창: 왼쪽(요약 + 행동, 행동은 아래 끝) | 오른쪽(중심 목록)
 * - 좁은 창: 세로로 쌓아 스크롤, 행동은 아래 고정
 */
@Composable
fun ScreenFrame(
    expanded: Boolean,
    header: @Composable () -> Unit,
    actions: List<Action>,
    summary: @Composable ColumnScope.() -> Unit,
    stage: @Composable ColumnScope.() -> Unit,
    stageFirstWhenCompact: Boolean = true
) {
    Column(Modifier.fillMaxSize()) {
        header()
        Spacer(Modifier.height(if (expanded) Space.l else Space.m))
        if (expanded) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                FillColumn(Modifier.weight(0.4f).fillMaxHeight(), top = summary, bottom = { if (actions.isNotEmpty()) ActionStack(actions) })
                Spacer(Modifier.width(Space.xl))
                VLine(Modifier.fillMaxHeight().padding(vertical = Space.m))
                Spacer(Modifier.width(Space.xl))
                Column(Modifier.weight(0.6f).fillMaxHeight().verticalScroll(rememberScrollState()), content = stage)
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.l)) {
                if (stageFirstWhenCompact) { Column(content = stage); Column(verticalArrangement = Arrangement.spacedBy(Space.m), content = summary) }
                else { Column(verticalArrangement = Arrangement.spacedBy(Space.m), content = summary); Column(content = stage) }
            }
            if (actions.isNotEmpty()) {
                Spacer(Modifier.height(Space.m))
                ActionStack(actions)
            }
        }
    }
}

/** 보조 행동을 한 줄에, 주 행동을 그 아래 가장 크게. */
@Composable
private fun ActionStack(actions: List<Action>) {
    val primary = actions.firstOrNull { it.primary }
    val others = actions.filter { !it.primary }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (others.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            others.forEach { a -> SecondaryButton(a.text, a.onClick, Modifier.weight(1f), enabled = a.enabled) }
        }
        primary?.let { PrimaryButton(it.text, it.onClick, Modifier.fillMaxWidth(), enabled = it.enabled) }
    }
}
