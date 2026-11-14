package kr.gachitan.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kr.gachitan.data.ThemeMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 색 역할. Pleos 기본 화면(밝은 흰 바탕)과 이어지도록 흰 바탕에 블루 하나를 씁니다.
 * 대비는 WCAG 상대 휘도로 계산한 값입니다(괄호 안: 배경 #FAFBFD / 흰 면 / 보조 면 기준).
 *
 * - 블루는 주 행동, 선택, 탑승 좌석, 기록 중 표시에만 씁니다. 화면 전체를 파랗게 칠하지 않습니다.
 * - 금액은 색이 아니라 크기로 강조합니다(공유 패널의 한 사람 금액만 예외).
 * - 회색 글자는 #596575 하나로 통일했습니다(5.2:1 이상). 더 옅은 회색 글자는 쓰지 않습니다.
 * - 주의색(갈색 기운의 호박색)은 사람이 확인해야 할 것과 연결 문제에만 씁니다.
 */
object Palette {
    /**
     * 야간 모드 여부. [GachitanTheme]이 차량(시스템) 주간/야간 설정이나 사용자가 고른 테마로 정합니다.
     * Compose 상태라서 값이 바뀌면 모든 화면이 다시 그려집니다.
     */
    var dark: Boolean by mutableStateOf(false)

    private fun pick(day: Long, night: Long) = Color(if (dark) night else day)

    // 주간: 배경 #FAFBFD 기준 대비 / 야간: 배경 #0F1217 기준 대비
    val Bg: Color get() = pick(0xFFFAFBFD, 0xFF0F1217)            // 기본 배경
    val Surface: Color get() = pick(0xFFFFFFFF, 0xFF181C23)       // 패널·시트
    val SurfaceAlt: Color get() = pick(0xFFF0F5FC, 0xFF222833)    // 보조 면(입력 칸, 눌린 행)
    val Text: Color get() = pick(0xFF202630, 0xFFE9EDF3)          // 14.7 / 야간 15.1
    val TextSecondary: Color get() = pick(0xFF596575, 0xFFA7B1BE) // 5.7 / 야간 8.3
    val Accent: Color get() = pick(0xFF2463EB, 0xFF3B76F0)        // 주 행동·탑승 좌석
    val AccentText: Color get() = pick(0xFF1D4FD0, 0xFF8AB4FF)    // 작은 블루 글자 6.0 / 야간 8.4
    val OnAccent: Color get() = Color(0xFFFFFFFF)                 // 블루 버튼 위 글자
    val AccentFill: Color get() = pick(0xFFE8F0FE, 0xFF1C2C4A)    // 선택 상태 배경
    val Line: Color get() = pick(0xFFDCE3ED, 0xFF2C3440)          // 구분선
    val Outline: Color get() = pick(0xFF8A96A5, 0xFF6C7889)       // 빈 좌석 점선, 입력 칸 테두리
    val Caution: Color get() = pick(0xFF8A5300, 0xFFF0B45A)       // 6.1 / 야간 9.6
    val CautionFill: Color get() = pick(0xFFFFF4E0, 0xFF3A2A12)
    val Scrim: Color get() = pick(0x66202630, 0x99000000)         // 공유 패널 뒤 화면을 낮추는 막
    /** 차량 윤곽선(형태만 알려 주는 배경 선). */
    val CarLine: Color get() = pick(0xFFC3CCD8, 0xFF3E4756)
    /** 핸들. */
    val Wheel: Color get() = pick(0xFF5E6A79, 0xFF9AA5B3)
    /** 구간 막대의 교차 색. */
    val BandAlt: Color get() = pick(0xFF9DB9F5, 0xFF3F5F9E)

    // 사람 구분 색: 이름 첫 글자 아바타와 탄 거리 막대에 씁니다. 같은 이름은 항상 같은 색입니다.
    private val personDay = listOf(
        0xFFE8F0FE to 0xFF1D4FD0, 0xFFE3F5EC to 0xFF1B7A4B, 0xFFFFF0E0 to 0xFFA4510B,
        0xFFF1EAFD to 0xFF6D3FC8, 0xFFE0F4F4 to 0xFF0F7171, 0xFFFDE8F0 to 0xFFB0306A
    )
    private val personNight = listOf(
        0xFF1C2C4A to 0xFF8AB4FF, 0xFF16362A to 0xFF7FD6A8, 0xFF3D2A14 to 0xFFF5B66F,
        0xFF2C2147 to 0xFFC3A6FF, 0xFF123536 to 0xFF72D4D4, 0xFF3C1A2A to 0xFFF39BC1
    )
    private fun personPair(key: String): Pair<Long, Long> {
        val list = if (dark) personNight else personDay
        return list[Math.floorMod(key.hashCode(), list.size)]
    }
    fun personFill(key: String): Color = Color(personPair(key).first)
    fun personInk(key: String): Color = Color(personPair(key).second)
}

/**
 * 글자 단계. 기본 산세리프(시스템 글꼴, 한글은 Noto Sans CJK 계열). 숫자는 모두 고정폭 숫자(tnum).
 * 차량 화면 기준 최소 18sp, 본문 22sp 이상. 작은 창에서도 글자를 줄이지 않고 배치를 바꿉니다.
 */
object Type {
    private const val TNUM = "tnum"
    private val base = TextStyle(fontFamily = FontFamily.SansSerif)

    /** 화면 중심 숫자(주행 거리, 총액). 크기는 창에 맞춰 [display]로 고릅니다. */
    fun display(size: TextUnit) = base.copy(fontSize = size, lineHeight = size * 1.08f, fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp, fontFeatureSettings = TNUM)
    /** 큰 제목(n명 탑승, 사람별 부담액). */
    val Hero = base.copy(fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
        fontFeatureSettings = TNUM)
    /** 사람별 금액. */
    val Figure = base.copy(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM)
    val Title = base.copy(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val Heading = base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM)
    val Body = base.copy(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM)
    val BodyNum = Body
    val Label = base.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM)
    val LabelNum = Label
    val Caption = base.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM)
    /** 좌석 안 작은 이름표. 좌석 칸이 작아도 18sp 아래로 내리지 않습니다. */
    val Seat = base.copy(fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
}

/** 간격은 8dp 격자를 따릅니다. */
object Space {
    val xs = 8.dp
    val s = 12.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    /** 화면 바깥 여백. */
    val edgeCompact = 24.dp
    val edgeExpanded = 40.dp
    /** 주 행동 버튼 높이. 흔들리는 차 안에서도 한 번에 누를 수 있는 크기. */
    val touchPrimary = 80.dp
    val touchSecondary = 64.dp
    val radius = 14.dp
    val radiusSeat = 18.dp
    val radiusPanel = 28.dp
}

/**
 * 창 크기 기준. 숫자는 실제 가용 크기(dp)로 판단합니다.
 * - 넓은 배치: 너비 760dp 이상이고 높이 440dp 이상(Pleos 단독 창, 2:1 분할의 큰 쪽)
 * - 좁은 배치: 그 밖(1:1 분할, 2:1 분할의 작은 쪽). 두 칸을 세로로 쌓고 스크롤합니다.
 */
object Breakpoints {
    val expandedWidth = 760.dp
    val expandedHeight = 440.dp
    /** 넓은 화면에서도 내용이 양 끝으로 벌어지지 않게 하는 최대 너비. */
    val maxContent = 1440.dp
}

private fun colors() = (if (Palette.dark) darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentFill,
    onPrimaryContainer = Palette.AccentText,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceAlt,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Line,
    error = Palette.Caution
) else lightColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.OnAccent,
    primaryContainer = Palette.AccentFill,
    onPrimaryContainer = Palette.AccentText,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.SurfaceAlt,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Line,
    error = Palette.Caution
))

private val typography = Typography(
    displayLarge = Type.Hero, headlineMedium = Type.Title, titleLarge = Type.Heading,
    bodyLarge = Type.Body, bodyMedium = Type.Body, labelLarge = Type.Label, labelMedium = Type.Caption
)

/**
 * @param mode AUTO면 차량(시스템)의 주간/야간 설정을 따릅니다. 밤에 흰 화면이 눈부시지 않도록
 *   AAOS가 야간 모드로 바꾸면 앱도 어두운 색으로 바뀝니다(액티비티를 다시 만들지 않고 즉시 반영).
 */
@Composable
fun GachitanTheme(mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) { ThemeMode.AUTO -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    if (Palette.dark != dark) Palette.dark = dark
    MaterialTheme(colorScheme = colors(), typography = typography) {
        Surface(color = Palette.Bg, contentColor = Palette.Text, content = content)
    }
}
