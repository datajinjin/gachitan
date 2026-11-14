package kr.gachitan.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kr.gachitan.ui.theme.Palette

/**
 * QR 코드. 흰 바탕에 넓은 여백(가로 길이의 8%)을 두어 휴대폰 카메라가 바로 읽습니다.
 * 흰 면 위에서도 QR 영역이 보이도록 옅은 테두리를 둡니다.
 */
@Composable
fun QrCode(content: String, size: Dp = 260.dp, description: String = "정산 QR 코드") {
    val bitmap = remember(content) { encodeQr(content, 600) }
    Box(Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White)
        .border(1.dp, Palette.Line, RoundedCornerShape(16.dp)).padding(size * 0.08f)) {
        Image(BitmapPainter(bitmap.asImageBitmap()), contentDescription = description, modifier = Modifier.fillMaxSize())
    }
}

private fun encodeQr(content: String, px: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 1))
    val pixels = IntArray(px * px) { i -> if (matrix[i % px, i / px]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
}
