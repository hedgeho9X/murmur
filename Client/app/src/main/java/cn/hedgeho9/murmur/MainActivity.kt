/** Compose 原生页面：白底取景、居中波形、单行转写和统一底部编辑器。 */
package cn.hedgeho9.murmur

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.sin

private val Green = Color(0xFF35B981)
private val Red = Color(0xFFE65C55)

/** Activity 仅承载界面与权限申请，业务状态由 ViewModel 持有。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme =
                    lightColorScheme(
                        primary = Green,
                        background = Color.White,
                        surface = Color.White,
                    )
            ) {
                MurmurScreen()
            }
        }
    }
}

/** 装配取景、帖子和编辑界面；进入后台时结束录音，不隐式后台采集。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MurmurScreen(vm: MurmurModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var settings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val controller = remember { LifecycleCameraController(context) }
    val cameraPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            cameraGranted = it
            if (!it) vm.error("需要相机权限才能拍照，仍可使用文字或语音")
        }
    val micPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            if (it) vm.record() else vm.error("未获得麦克风权限")
        }
    val gallery =
        androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            vm.addPhotos(uris)
        }
    val record: () -> Unit = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
            vm.record()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.background()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            controller.unbind()
        }
    }
    LaunchedEffect(cameraGranted, lifecycle) {
        if (cameraGranted)
            try {
                controller.bindToLifecycle(lifecycle)
            } catch (_: Exception) {
                vm.error("相机不可用")
            }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(s.error) {
        s.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }
    Scaffold(
        containerColor = Color.White,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.statusBarsPadding()
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { if (s.page == "capture") vm.history() else vm.capture() },
                    enabled = !s.recording && !s.finalizing,
                ) {
                    Icon(
                        if (s.page == "capture") Icons.Outlined.Menu else Icons.Outlined.ArrowBack,
                        "记录列表",
                    )
                }
                Text("Murmur", fontSize = 24.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.editor(true) }, enabled = !s.recording) {
                    Icon(Icons.Outlined.Edit, "打开草稿")
                }
                IconButton(onClick = { settings = true }, enabled = !s.recording && !s.busy) {
                    Icon(Icons.Outlined.Settings, "连接设置")
                }
            }
        },
    ) { padding ->
        when (s.page) {
            "capture" ->
                Column(
                    Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(.35f))
                    Crossfade(s.recording, label = "capture") { recording ->
                        if (recording) RecordingArea(s.liveText, s.speaking, s.level)
                        else
                            Box(
                                Modifier.fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(Color(0xFFF1F2F3)),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (s.draft.images.isNotEmpty())
                                    AsyncImage(
                                        File(s.draft.images.first()),
                                        "草稿照片",
                                        Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                else if (cameraGranted)
                                    AndroidView(
                                        factory = {
                                            PreviewView(it).apply {
                                                this.controller = controller
                                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                else
                                    IconButton(
                                        onClick = {
                                            cameraPermission.launch(Manifest.permission.CAMERA)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.PhotoCamera,
                                            "启用相机",
                                            tint = Color.LightGray,
                                            modifier = Modifier.size(34.dp),
                                        )
                                    }
                            }
                    }
                    Spacer(Modifier.height(44.dp))
                    if (s.recording)
                        RoundButton("停止录音", { vm.stop() }) {
                            Box(Modifier.size(23.dp).clip(RoundedCornerShape(5.dp)).background(Red))
                        }
                    else if (s.draft.images.isNotEmpty())
                        Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                            RoundButton("开始录音", record) {
                                Box(Modifier.size(26.dp).background(Red, CircleShape))
                            }
                            RoundButton("编辑文字", { vm.editor(true) }) {
                                Icon(Icons.Outlined.Edit, "编辑文字")
                            }
                        }
                    else
                        Box(
                            Modifier.size(76.dp)
                                .border(2.dp, Color.DarkGray, CircleShape)
                                .padding(7.dp)
                                .background(Color.DarkGray, CircleShape)
                                .pointerInput(cameraGranted, s.busy) {
                                    detectTapGestures(
                                        onLongPress = { record() },
                                        onTap = {
                                            if (!cameraGranted)
                                                cameraPermission.launch(Manifest.permission.CAMERA)
                                            else if (!s.busy) {
                                                val file =
                                                    File(context.cacheDir, "capture-${newId()}.jpg")
                                                controller.takePicture(
                                                    ImageCapture.OutputFileOptions.Builder(file)
                                                        .build(),
                                                    ContextCompat.getMainExecutor(context),
                                                    object : ImageCapture.OnImageSavedCallback {
                                                        override fun onImageSaved(
                                                            result: ImageCapture.OutputFileResults
                                                        ) {
                                                            vm.addPhoto(Uri.fromFile(file))
                                                        }

                                                        override fun onError(
                                                            exception: ImageCaptureException
                                                        ) {
                                                            vm.error("拍照失败")
                                                        }
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                        )
                    Spacer(Modifier.weight(.65f))
                }
            "history" ->
                LazyColumn(Modifier.padding(padding).fillMaxSize().padding(horizontal = 24.dp)) {
                    item {
                        OutlinedTextField(
                            s.query,
                            vm::query,
                            placeholder = { Text("搜索记录") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            trailingIcon = {
                                IconButton(onClick = { vm.history() }) {
                                    Icon(Icons.Outlined.Search, "搜索")
                                }
                            },
                        )
                        FilterChip(
                            selected = s.imagesOnly,
                            onClick = { vm.filterImages(!s.imagesOnly) },
                            label = { Text("有照片") },
                        )
                    }
                    items(s.posts, key = { it.id.toString() }) { post ->
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { vm.open(post.id.toString()) }
                                .padding(vertical = 22.dp)
                        ) {
                            Text(
                                post.createdAt.take(16).replace('T', ' '),
                                color = Color.Gray,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                post.title ?: post.preview?.takeIf { it.isNotBlank() } ?: "照片记录",
                                fontSize = 18.sp,
                                maxLines = 3,
                            )
                            HorizontalDivider(
                                Modifier.padding(top = 20.dp),
                                color = Color(0xFFEEEEEE),
                            )
                        }
                    }
                    item {
                        if (s.busy) CircularProgressIndicator(Modifier.padding(24.dp))
                        else if (s.posts.isEmpty())
                            Text(
                                "还没有记录",
                                color = Color.Gray,
                                modifier = Modifier.padding(vertical = 48.dp),
                            )
                        if (s.cursor != null)
                            TextButton(onClick = { vm.history(true) }) { Text("加载更多") }
                        FilledTonalButton(onClick = { vm.capture() }) { Text("＋") }
                    }
                }
            "detail" ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                        items(s.details) { message ->
                            var expanded by remember { mutableStateOf(false) }
                            Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
                                if (message.process)
                                    TextButton(onClick = { expanded = !expanded }) {
                                        Text(if (expanded) "收起执行过程" else "查看执行过程")
                                    }
                                if (!message.process || expanded)
                                    Text(message.text, fontSize = 17.sp, lineHeight = 29.sp)
                                LazyRow {
                                    items(message.images) { url ->
                                        AsyncImage(
                                            url,
                                            "记录图片",
                                            Modifier.padding(top = 12.dp, end = 8.dp)
                                                .size(150.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    Modifier.padding(top = 20.dp),
                                    color = Color(0xFFEEEEEE),
                                )
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, "删除帖子")
                        }
                        FilledTonalButton(onClick = { vm.reply() }) { Text("补充记录") }
                    }
                }
        }
    }
    if (s.editor)
        ModalBottomSheet(
            onDismissRequest = { if (!s.busy && !s.finalizing) vm.editor(false) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.White,
            dragHandle = null,
        ) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(s.finalizing) { if (!s.finalizing) focus.requestFocus() }
            Column(Modifier.fillMaxWidth().imePadding().padding(22.dp)) {
                LazyRow {
                    items(s.draft.images) { path ->
                        Box(Modifier.padding(end = 10.dp).size(68.dp)) {
                            AsyncImage(
                                File(path),
                                "附件",
                                Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            IconButton(
                                onClick = { vm.removePhoto(path) },
                                enabled = !s.busy,
                                modifier =
                                    Modifier.align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .background(Color.DarkGray, CircleShape),
                            ) {
                                Icon(
                                    Icons.Outlined.Close,
                                    "移除附件",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp)
                        .padding(top = 14.dp)
                ) {
                    if (s.draft.text.isEmpty())
                        Text("现在的想法是…", color = Color.LightGray, fontSize = 18.sp)
                    BasicTextField(
                        s.draft.text,
                        vm::edit,
                        enabled = !s.finalizing && !s.busy,
                        textStyle =
                            TextStyle(fontSize = 18.sp, lineHeight = 30.sp, color = Color.DarkGray),
                        cursorBrush = SolidColor(Green),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { gallery.launch("image/*") },
                        enabled = !s.busy && !s.finalizing,
                    ) {
                        Icon(Icons.Outlined.Image, "添加图片")
                    }
                    Spacer(Modifier.weight(1f))
                    if (s.finalizing) Text("正在收尾…", color = Color.Gray, fontSize = 12.sp)
                    if (s.busy) CircularProgressIndicator(Modifier.size(24.dp))
                    else
                        FilledTonalIconButton(
                            onClick = { vm.send() },
                            enabled =
                                !s.finalizing &&
                                    (s.draft.text.isNotBlank() || s.draft.images.isNotEmpty()),
                            colors =
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFFE5F7EF),
                                    contentColor = Green,
                                ),
                        ) {
                            Icon(Icons.Outlined.ArrowUpward, "发送")
                        }
                }
            }
        }
    if (settings) {
        var url by remember { mutableStateOf(s.base) }
        var token by remember { mutableStateOf(s.token) }
        AlertDialog(
            onDismissRequest = { settings = false },
            title = { Text("连接设置") },
            text = {
                Column {
                    OutlinedTextField(
                        url,
                        { url = it },
                        label = { Text("服务器地址") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        token,
                        { token = it },
                        label = { Text("访问令牌") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.settings(url, token)
                        settings = false
                    }
                ) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { settings = false }) { Text("取消") } },
        )
    }
    if (confirmDelete)
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条记录？") },
            text = { Text("正文和关联图片会一起删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        vm.delete()
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
}

/** 圆形主操作按钮，保留足够触摸区域及可访问语义。 */
@Composable
private fun RoundButton(label: String, click: () -> Unit, content: @Composable () -> Unit) {
    OutlinedIconButton(
        onClick = click,
        modifier = Modifier.size(68.dp).semantics { contentDescription = label },
        shape = CircleShape,
    ) {
        content()
    }
}

/** 根据真实音量显示居中波形，静音留白；文字按可用宽度保留单行尾部。 */
@Composable
private fun RecordingArea(text: String, speaking: Boolean, level: Float) {
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val width = with(density) { (maxWidth - 24.dp).roundToPx() }
        val style = TextStyle(fontSize = 20.sp)
        val tail =
            remember(text, width) {
                var chars = text.toList()
                while (
                    chars.isNotEmpty() &&
                        measurer.measure(chars.joinToString(""), style = style).size.width > width
                ) chars = chars.drop(1)
                chars.joinToString("")
            }
        if (speaking) {
            Canvas(Modifier.align(Alignment.Center).fillMaxWidth().height(60.dp)) {
                val spacing = size.width / 44
                for (i in 0..39) {
                    val h =
                        (6 + level.coerceAtMost(.4f) * 140 * kotlin.math.abs(sin(i * .7)))
                            .toFloat() * density.density
                    drawLine(
                        Green,
                        androidx.compose.ui.geometry.Offset(
                            spacing * (i + 2),
                            size.height / 2 - h / 2,
                        ),
                        androidx.compose.ui.geometry.Offset(
                            spacing * (i + 2),
                            size.height / 2 + h / 2,
                        ),
                        strokeWidth = 3 * density.density,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
            Text(
                tail,
                style = style,
                maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}
