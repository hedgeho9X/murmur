/** 草稿照片的预览与拖拽删除，不自动修改已发送记录。 */
package cn.hedgeho9.murmur

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import java.io.File

/** 点击查看照片；拖动时缩小照片并显示垃圾桶，释放点落入目标区域才删除并震动。 */
@Composable
fun DraggablePhoto(path: String, onPreview: () -> Unit, onDelete: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    var dragging by remember(path) { mutableStateOf(false) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var pointer by remember(path) { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var trash by remember { mutableStateOf(Rect.Zero) }
    val inside = dragging && trash.contains(pointer)
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .pointerInput(path) {
                detectDragGestures(
                    onDragStart = {
                        dragging = true
                        pointer = origin + it
                    },
                    onDragCancel = {
                        dragging = false
                        offset = Offset.Zero
                    },
                    onDragEnd = {
                        if (trash.contains(pointer)) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDelete()
                        }
                        dragging = false
                        offset = Offset.Zero
                    },
                ) { change, amount ->
                    change.consume()
                    offset += amount
                    pointer = origin + change.position
                }
            }
    ) {
        AsyncImage(
            File(path),
            "草稿照片，拖到垃圾桶删除",
            Modifier.fillMaxSize()
                .graphicsLayer {
                    translationX = offset.x
                    translationY = offset.y
                    scaleX = if (dragging) .72f else 1f
                    scaleY = scaleX
                    alpha = if (inside) .5f else 1f
                }
                .clip(RoundedCornerShape(22.dp))
                .clickable(onClick = onPreview),
            contentScale = ContentScale.Crop,
        )
        if (dragging)
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .size(64.dp)
                    .onGloballyPositioned { trash = it.boundsInRoot() }
                    .background(if (inside) Color(0xFFE65C55) else Color(0xFFF2F2F2), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    "拖到这里删除",
                    tint = if (inside) Color.White else Color.Gray,
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
