package arisa.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import arisa.app.ArisaApp
import arisa.quark.QuarkWebLogin
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 扫码登录夸克. 二维码 5 分钟内有效, 过期自动换新. */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onDemo: () -> Unit) {
    val app = ArisaApp.instance
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    LaunchedEffect(generation) {
        error = null
        qr = null
        val web = QuarkWebLogin(app.http)
        val login = try {
            withContext(Dispatchers.IO) { web.start() }
        } catch (e: Exception) {
            error = "获取二维码失败: ${e.message}"
            return@LaunchedEffect
        }
        qr = qrBitmap(login.qrContent)
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val cookie = withContext(Dispatchers.IO) { runCatching { web.poll(login.token) }.getOrNull() } ?: continue
            try {
                withContext(Dispatchers.IO) { app.session.login(cookie) }
                onLoggedIn()
            } catch (e: Exception) {
                error = "登录失败: ${e.message}"
            }
            return@LaunchedEffect
        }
        generation++
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF15151F), Color(0xFF2A1636), Palette.Background))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column(Modifier.width(380.dp)) {
                Text("Arisa", style = MaterialTheme.typography.displaySmall, color = Palette.Accent)
                Spacer(Modifier.height(16.dp))
                Text("用夸克 App 扫码登录", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "打开手机夸克 App → 右上角扫一扫 → 确认登录\n片库由刮削器生成后会自动出现在这里",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { generation++ }, modifier = Modifier.focusRequester(focus)) { Text("刷新二维码") }
                    OutlinedButton(onClick = onDemo) { Text("演示模式") }
                }
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF8A80))
                }
            }
            Box(
                Modifier.size(300.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                qr?.let { Image(it, contentDescription = "登录二维码", modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
                    ?: Text(if (error == null) "加载中…" else "加载失败", color = Color.Gray)
            }
        }
    }
}

private fun qrBitmap(content: String): ImageBitmap {
    val m = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
