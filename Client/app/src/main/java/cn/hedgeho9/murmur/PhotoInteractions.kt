/** 草稿照片的预览与拖拽删除，不自动修改已发送记录。 */
package cn.hedgeho9.murmur

import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.roundToInt

/** 根布局共享拖动坐标，使缩小的照片与垃圾桶不受取景框裁切。 */
@Stable
class PhotoDragState {
    var path by mutableStateOf<String?>(null)
    var bounds by mutableStateOf(Rect.Zero)
    var offset by mutableStateOf(Offset.Zero)
    var pointer by mutableStateOf(Offset.Zero)
    var trash by mutableStateOf(Rect.Zero)
    val inside: Boolean
        get() = path != null && trash.contains(pointer)
}

/** 长按开始拖动；只在进入垃圾桶时反馈一次，释放在目标内才删除。 */
@Composable
fun DraggablePhoto(
    path: String,
    drag: PhotoDragState,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val delete by rememberUpdatedState(onDelete)
    var decoded by remember(path) { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .pointerInput(path) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        drag.bounds = bounds
                        drag.pointer = bounds.topLeft + it
                        drag.offset = Offset.Zero
                        drag.path = path
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragCancel = { drag.path = null },
                    onDragEnd = {
                        if (drag.inside) {
                            @Suppress("DEPRECATION")
                            val vibrator =
                                context.getSystemService(android.content.Context.VIBRATOR_SERVICE)
                                    as Vibrator
                            vibrator.vibrate(
                                VibrationEffect.createWaveform(
                                    longArrayOf(0, 12, 45, 12, 45, 18),
                                    -1,
                                )
                            )
                            delete()
                        }
                        drag.path = null
                    },
                ) { change, amount ->
                    change.consume()
                    val wasInside = drag.inside
                    drag.offset += amount
                    drag.pointer = bounds.topLeft + change.position
                    if (!wasInside && drag.inside)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
    ) {
        if (drag.path == null)
            AsyncImage(
                File(path),
                "草稿照片，长按拖动删除",
                Modifier.fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        if (decoded) CaptureTiming.firstDraw(path)
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onPreview),
                contentScale = ContentScale.Crop,
                onSuccess = { decoded = true },
            )
    }
}

/** 根窗口浮层显示无外框的缩小照片，垃圾桶固定在导航栏上方。 */
@Composable
fun BoxScope.PhotoDragOverlay(drag: PhotoDragState) {
    val density = LocalDensity.current
    drag.path?.let { path ->
        AsyncImage(
            File(path),
            "拖动中的照片",
            Modifier.offset {
                    IntOffset(
                        (drag.bounds.left + drag.offset.x).roundToInt(),
                        (drag.bounds.top + drag.offset.y).roundToInt(),
                    )
                }
                .size(
                    with(density) { drag.bounds.width.toDp() },
                    with(density) { drag.bounds.height.toDp() },
                )
                .graphicsLayer {
                    scaleX = .64f
                    scaleY = .64f
                    alpha = if (drag.inside) .5f else 1f
                }
                .clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )
        Box(
            Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .size(64.dp)
                .onGloballyPositioned { drag.trash = it.boundsInRoot() }
                .background(if (drag.inside) Color(0xFFE65C55) else Color(0xFFF2F2F2), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Delete,
                "拖到这里删除",
                tint = if (drag.inside) Color.White else Color.Gray,
            )
        }
    }
}

/** 全屏照片查看器，支持双指缩放和平移；关闭不会删除或发送照片。 */
@Composable
fun PhotoViewer(source: Any, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Dialog(
        onDismissRequest = onClose,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AsyncImage(
                source,
                "照片预览",
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 5f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp),
            ) {
                Icon(Icons.Outlined.Close, "关闭预览", tint = Color.White)
            }
        }
    }
}
