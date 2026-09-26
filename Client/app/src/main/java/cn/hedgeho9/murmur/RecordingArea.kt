/** 录音视觉反馈：真实音量驱动连续曲线，静音保留细线，字幕只显示一行尾部。 */
package cn.hedgeho9.murmur

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/** 输入麦克风 RMS 与转写状态；绘制不依赖转写返回，断句不会卸载波形。 */
@Composable
fun RecordingArea(text: String, captionVisible: Boolean, level: Float) {
    val amplitude by
        animateFloatAsState(
            (ln(1 + level.coerceAtLeast(0f) * 120) / ln(13f)).coerceIn(0f, 1f),
            tween(140),
            label = "audio level",
        )
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by
        transition.animateFloat(
            0f,
            (2 * PI).toFloat(),
            infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "wave phase",
        )
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val width = with(density) { (maxWidth - 24.dp).roundToPx() }
        val style = TextStyle(fontSize = 20.sp)
        val tail =
            remember(text, width) {
                val points = text.codePoints().toArray()
                var start = 0
                while (
                    start < points.size &&
                        measurer
                            .measure(String(points, start, points.size - start), style)
                            .size
                            .width > width
                ) start++
                String(points, start, points.size - start)
            }
        Canvas(Modifier.align(Alignment.Center).fillMaxWidth().height(100.dp)) {
            for (layer in 0..2) {
                val path = Path()
                for (step in 0..180) {
                    val x = step / 180f
                    val envelope = sin(PI * x).pow(2).toFloat()
                    val y =
                        size.height / 2 +
                            sin(x * PI * 4 + phase + layer * .8).toFloat() *
                                envelope *
                                amplitude *
                                size.height *
                                (.38f - layer * .08f)
                    if (step == 0) path.moveTo(x * size.width, y)
                    else path.lineTo(x * size.width, y)
                }
                drawPath(
                    path,
                    Color(0xFF35B981).copy(alpha = if (layer == 0) .85f else .22f),
                    style = Stroke((if (layer == 0) 2f else 1f).dp.toPx()),
                )
            }
        }
        if (captionVisible)
            Text(
                tail,
                style = style,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
    }
}
