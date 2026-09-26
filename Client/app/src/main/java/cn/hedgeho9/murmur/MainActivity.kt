/** Compose 原生页面：白底取景、居中波形、单行转写和统一底部编辑器。 */
package cn.hedgeho9.murmur

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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
import com.mikepenz.markdown.m3.Markdown
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                        onSurface = Color(0xFF292929),
                        secondary = Color(0xFF525252),
                        secondaryContainer = Color(0xFFF2F2F2),
                        onSecondaryContainer = Color(0xFF292929),
                        surfaceVariant = Color(0xFFF6F6F6),
                        outline = Color(0xFFD8D8D8),
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
    val view = LocalView.current
    val photoDrag = remember { PhotoDragState() }
    var preview by remember { mutableStateOf<Any?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var settings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var capturePending by remember { mutableStateOf(false) }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(androidx.camera.view.CameraController.IMAGE_CAPTURE)
        }
    }
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
    LaunchedEffect(cameraGranted, lifecycle, s.page, s.recording, s.draft.images.isEmpty()) {
        if (cameraGranted && s.page == "capture" && !s.recording && s.draft.images.isEmpty())
            try {
                controller.bindToLifecycle(lifecycle)
            } catch (_: Exception) {
                vm.error("相机不可用")
            }
        else controller.unbind()
    }
    BackHandler(enabled = !s.editor && s.page == "detail") { vm.showHistory() }
    BackHandler(enabled = !s.editor && drawer.isOpen) { scope.launch { drawer.close() } }
    Box(Modifier.fillMaxSize()) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = !s.recording && !s.finalizing && !s.editor && photoDrag.path == null,
            drawerContent = {
                NotesSidebar(
                    s.page,
                    {
                        vm.capture()
                        scope.launch { drawer.close() }
                    },
                    {
                        vm.showHistory()
                        scope.launch { drawer.close() }
                    },
                    {
                        scope.launch { drawer.close() }
                        settings = true
                    },
                )
            },
        ) {
            Scaffold(
                containerColor = Color.White,
                topBar = {
                    Row(
                        Modifier.statusBarsPadding()
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                if (s.page == "detail") vm.showHistory()
                                else scope.launch { drawer.open() }
                            },
                            enabled = !s.recording && !s.finalizing,
                        ) {
                            Icon(
                                if (s.page == "detail") Icons.Outlined.ArrowBack
                                else Icons.Outlined.Menu,
                                if (s.page == "detail") "返回笔记" else "打开侧栏",
                            )
                        }
                        Text(
                            if (s.page == "history") "全部笔记"
                            else if (s.page == "detail") "笔记" else "Murmur",
                            fontSize = 22.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (s.page == "capture")
                            IconButton(onClick = { vm.editor(true) }, enabled = !s.recording) {
                                Icon(Icons.Outlined.Edit, "打开草稿")
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
                                if (recording) RecordingArea(s.liveText, s.captionVisible, s.level)
                                else
                                    Box(
                                        Modifier.fillMaxWidth()
                                            .aspectRatio(1f)
                                            .then(
                                                if (s.draft.images.isEmpty())
                                                    Modifier.clip(RoundedCornerShape(22.dp))
                                                        .background(Color(0xFFF1F2F3))
                                                else Modifier
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (s.draft.images.isNotEmpty())
                                            DraggablePhoto(
                                                s.draft.images.first(),
                                                photoDrag,
                                                { preview = File(s.draft.images.first()) },
                                                { vm.removePhoto(s.draft.images.first()) },
                                            )
                                        else if (cameraGranted)
                                            AndroidView(
                                                factory = {
                                                    PreviewView(it).apply {
                                                        this.controller = controller
                                                        scaleType =
                                                            PreviewView.ScaleType.FILL_CENTER
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        else
                                            IconButton(
                                                onClick = {
                                                    cameraPermission.launch(
                                                        Manifest.permission.CAMERA
                                                    )
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
                            Spacer(Modifier.weight(.5f))
                            if (photoDrag.path != null) Spacer(Modifier.height(76.dp))
                            else if (s.recording)
                                RoundButton("停止录音", { vm.stop() }) {
                                    Box(
                                        Modifier.size(23.dp)
                                            .clip(RoundedCornerShape(5.dp))
                                            .background(Red)
                                    )
                                }
                            else if (s.draft.images.isNotEmpty())
                                Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
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
                                        .semantics { contentDescription = "拍照，长按录音" }
                                        .background(Color(0xFFF3F3F3), CircleShape)
                                        .padding(9.dp)
                                        .border(1.dp, Color(0xFF252525), CircleShape)
                                        .padding(5.dp)
                                        .background(Color(0xFF252525), CircleShape)
                                        .pointerInput(cameraGranted, s.busy, capturePending) {
                                            detectTapGestures(
                                                onLongPress = { if (!capturePending) record() },
                                                onTap = {
                                                    if (!cameraGranted)
                                                        cameraPermission.launch(
                                                            Manifest.permission.CAMERA
                                                        )
                                                    else if (!s.busy && !capturePending) {
                                                        capturePending = true
                                                        val shutterAt =
                                                            android.os.SystemClock.elapsedRealtime()
                                                        view.performHapticFeedback(
                                                            android.view.HapticFeedbackConstants
                                                                .CONFIRM
                                                        )
                                                        val file =
                                                            File(
                                                                context.cacheDir,
                                                                "capture-${newId()}.jpg",
                                                            )
                                                        controller.takePicture(
                                                            ImageCapture.OutputFileOptions.Builder(
                                                                    file
                                                                )
                                                                .build(),
                                                            ContextCompat.getMainExecutor(context),
                                                            object :
                                                                ImageCapture.OnImageSavedCallback {
                                                                override fun onImageSaved(
                                                                    result:
                                                                        ImageCapture.OutputFileResults
                                                                ) {
                                                                    capturePending = false
                                                                    android.util.Log.d(
                                                                        "MurmurCapture",
                                                                        "jpegMs=${android.os.SystemClock.elapsedRealtime()-shutterAt}",
                                                                    )
                                                                    vm.addCapturedPhoto(
                                                                        file,
                                                                        shutterAt,
                                                                    )
                                                                }

                                                                override fun onError(
                                                                    exception: ImageCaptureException
                                                                ) {
                                                                    capturePending = false
                                                                    vm.error("拍照失败")
                                                                }
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                )
                            Spacer(Modifier.height(72.dp))
                        }
                    "history" -> NotesList(s, vm, padding, listState)
                    "detail" ->
                        Column(Modifier.padding(padding).fillMaxSize()) {
                            if (s.detailLoading)
                                Box(
                                    Modifier.weight(1f).fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            else
                                key(s.selected) {
                                    LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp)) {
                                        item {
                                            TagRow(s.selectedTags, vm::searchTag)
                                            s.syncStates[s.selected]?.let {
                                                TextButton(onClick = vm::retrySync) { Text(it) }
                                            }
                                        }
                                        items(s.details, key = { it.id }) { message ->
                                            var expanded by remember { mutableStateOf(false) }
                                            Column(
                                                Modifier.fillMaxWidth().padding(vertical = 12.dp)
                                            ) {
                                                if (message.role == "user")
                                                    IconButton(
                                                        onClick = { vm.editPublished(message) },
                                                        modifier = Modifier.align(Alignment.End),
                                                    ) {
                                                        Icon(Icons.Outlined.Edit, "编辑笔记")
                                                    }

                                                if (message.process)
                                                    TextButton(onClick = { expanded = !expanded }) {
                                                        Text(if (expanded) "收起执行过程" else "查看执行过程")
                                                    }
                                                if (!message.process || expanded)
                                                    Markdown(message.text)
                                                LazyRow {
                                                    items(message.images) { url ->
                                                        AsyncImage(
                                                            url,
                                                            "记录图片",
                                                            Modifier.padding(
                                                                    top = 12.dp,
                                                                    end = 8.dp,
                                                                )
                                                                .size(150.dp)
                                                                .clickable { preview = url }
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
                                }
                            Row(
                                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                IconButton(onClick = { confirmDelete = true }) {
                                    Icon(Icons.Outlined.Delete, "删除帖子")
                                }
                            }
                        }
                }
            }
        }
        if (s.editor) {
            val editorText = if (s.editingMessage != null) s.editingText else s.draft.text
            var field by remember {
                mutableStateOf(TextFieldValue(editorText, TextRange(editorText.length)))
            }
            LaunchedEffect(editorText) {
                if (field.text != editorText)
                    field = TextFieldValue(editorText, TextRange(editorText.length))
            }
            val format: (String, String) -> Unit = { before, after ->
                val start = field.selection.min
                val end = field.selection.max
                val value =
                    field.text.take(start) +
                        before +
                        field.text.substring(start, end) +
                        after +
                        field.text.drop(end)
                field = TextFieldValue(value, TextRange(start + before.length, end + before.length))
                vm.edit(value)
            }
            val keyboard = LocalSoftwareKeyboardController.current
            BackHandler {
                if (!s.busy && !s.finalizing) {
                    keyboard?.hide()
                    vm.editor(false)
                }
            }
            val focus = remember { FocusRequester() }
            var reveal by remember { mutableStateOf(false) }
            val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
            LaunchedEffect(Unit) {
                delay(350)
                reveal = true
            }
            LaunchedEffect(s.finalizing) {
                if (!s.finalizing) {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)).clickable(
                    enabled = !s.busy && !s.finalizing
                ) {
                    keyboard?.hide()
                    vm.editor(false)
                }
            )
            Column(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .graphicsLayer { alpha = if (imeHeight > 0 || reveal) 1f else 0f }
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color.White)
                    .clickable(
                        interactionSource =
                            remember {
                                androidx.compose.foundation.interaction.MutableInteractionSource()
                            },
                        indication = null,
                    ) {}
                    .padding(22.dp)
            ) {
                if (s.editingMessage == null && s.draft.postId != null)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("补充草稿", modifier = Modifier.weight(1f), color = Color.Gray)
                        TextButton(onClick = vm::saveDraftAsNewNote) { Text("另存为新笔记") }
                    }
                if (s.editingMessage != null)
                    LazyRow {
                        items(s.editingMessage?.images.orEmpty()) { url ->
                            AsyncImage(
                                url,
                                "附件",
                                Modifier.padding(end = 8.dp)
                                    .size(68.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { preview = url },
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                else
                    LazyRow {
                        items(s.draft.images) { path ->
                            Box(Modifier.padding(end = 10.dp).size(68.dp)) {
                                AsyncImage(
                                    File(path),
                                    "附件",
                                    Modifier.fillMaxSize()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { preview = File(path) },
                                    contentScale = ContentScale.Crop,
                                )
                                Box(
                                    Modifier.align(Alignment.TopEnd).size(32.dp).clickable(
                                        enabled = !s.busy
                                    ) {
                                        vm.removePhoto(path)
                                    },
                                    contentAlignment = Alignment.TopEnd,
                                ) {
                                    Box(
                                        Modifier.size(16.dp)
                                            .background(Color.DarkGray, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            "移除附件",
                                            tint = Color.White,
                                            modifier = Modifier.size(11.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp)
                        .padding(top = 14.dp)
                ) {
                    if (editorText.isEmpty())
                        Text("现在的想法是…", color = Color.LightGray, fontSize = 18.sp)
                    BasicTextField(
                        field,
                        {
                            field = it
                            vm.edit(it.text)
                        },
                        enabled = !s.finalizing && !s.busy,
                        textStyle =
                            TextStyle(fontSize = 18.sp, lineHeight = 30.sp, color = Color.DarkGray),
                        cursorBrush = SolidColor(Green),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { format("#", "") }, enabled = !s.busy && !s.finalizing) {
                        Text("#", fontSize = 26.sp)
                    }
                    IconButton(
                        onClick = { format("**", "**") },
                        enabled = !s.busy && !s.finalizing,
                    ) {
                        Icon(Icons.Outlined.FormatBold, "加粗")
                    }
                    IconButton(
                        onClick = {
                            format(
                                if (
                                    field.selection.min == 0 ||
                                        field.text.getOrNull(field.selection.min - 1) == '\n'
                                )
                                    "- "
                                else "\n- ",
                                "",
                            )
                        },
                        enabled = !s.busy && !s.finalizing,
                    ) {
                        Icon(Icons.Outlined.FormatListBulleted, "列表")
                    }
                    IconButton(
                        onClick = { gallery.launch("image/*") },
                        enabled = !s.busy && !s.finalizing && s.editingMessage == null,
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
                                    (editorText.isNotBlank() ||
                                        (s.editingMessage?.images ?: s.draft.images).isNotEmpty()),
                            colors =
                                IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = Color(0xFFE5F7EF),
                                    contentColor = Green,
                                ),
                        ) {
                            Icon(
                                if (s.editingMessage != null) Icons.Outlined.Check
                                else Icons.Outlined.ArrowUpward,
                                if (s.editingMessage != null) "保存修改" else "保存笔记",
                            )
                        }
                }
            }
        }
        PhotoDragOverlay(photoDrag)
        if (settings) {
            var url by remember { mutableStateOf(s.base) }
            var token by remember { mutableStateOf(s.token) }
            var directAsr by remember { mutableStateOf(s.directAsr) }
            var probing by remember { mutableStateOf(false) }
            var probeResult by remember(url, token) { mutableStateOf<String?>(null) }
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
                        TextButton(
                            enabled = !probing,
                            onClick = {
                                val testUrl = url
                                val testToken = token
                                probing = true
                                scope.launch {
                                    try {
                                        val result =
                                            try {
                                                MurmurApi(testUrl, testToken).probe()
                                            } catch (_: IllegalArgumentException) {
                                                "服务器地址格式错误"
                                            }
                                        if (url == testUrl && token == testToken) {
                                            probeResult = result
                                            if (!result.startsWith("连接正常")) vm.error(result)
                                        }
                                    } finally {
                                        probing = false
                                    }
                                }
                            },
                        ) {
                            Text(if (probing) "正在测试…" else "测试连接与延迟")
                        }
                        probeResult?.let { Text(it, fontSize = 13.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("语音直连", modifier = Modifier.weight(1f))
                            Switch(checked = directAsr, onCheckedChange = { directAsr = it })
                        }
                        Text(if (directAsr) "短期凭证 · 手机直连语音服务" else "由服务器转发语音", fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.settings(url, token, directAsr)
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
        preview?.let { PhotoViewer(it) { preview = null } }
        // 独立错误窗口位于编辑器、键盘和图片预览上方，用户确认前不自动消失。
        s.error?.let { message ->
            AlertDialog(
                onDismissRequest = vm::clearError,
                title = { Text("操作未完成") },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = vm::clearError) { Text("知道了") } },
                dismissButton = {
                    if (message.contains("401") || message.contains("令牌"))
                        TextButton(
                            onClick = {
                                vm.clearError()
                                settings = true
                            }
                        ) {
                            Text("连接设置")
                        }
                },
            )
        }
    }
}

/** 圆形主操作按钮，保留足够触摸区域及可访问语义。 */
@Composable
private fun RoundButton(label: String, click: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(76.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFFF3F3F3))
            .clickable(onClick = click)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
