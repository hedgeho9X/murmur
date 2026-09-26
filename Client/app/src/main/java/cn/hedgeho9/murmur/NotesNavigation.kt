/** 笔记侧栏与分页列表；列表只绘制轻量摘要，完整 Markdown 留给详情页。 */
package cn.hedgeho9.murmur

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** 侧栏提供页面导航，设置作为独立入口固定在底部，不引入底部 Tab。 */
@Composable
fun NotesSidebar(page: String, onCapture: () -> Unit, onNotes: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxHeight()
            .width(292.dp)
            .background(Color(0xFFFAFAFA))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp)
    ) {
        Text(
            "Murmur",
            fontSize = 26.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 28.dp),
        )
        NavigationDrawerItem(
            label = { Text("记录") },
            selected = page == "capture",
            onClick = onCapture,
            icon = { Icon(Icons.Outlined.PhotoCamera, null) },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            label = { Text("全部笔记") },
            selected = page != "capture",
            onClick = onNotes,
            icon = { Icon(Icons.Outlined.Notes, null) },
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.weight(1f))
        NavigationDrawerItem(
            label = { Text("设置") },
            selected = false,
            onClick = onSettings,
            icon = { Icon(Icons.Outlined.Settings, null) },
            shape = RoundedCornerShape(12.dp),
        )
    }
}

/** 接近列表末尾时读取下一页；请求中或失败时停止自动重试，保留已加载条目。 */
@Composable
fun NotesList(s: UiState, vm: MurmurModel, padding: PaddingValues, listState: LazyListState) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(s.cursor, s.listLoading, s.listError) {
        if (s.cursor == null || s.listLoading || s.listError) return@LaunchedEffect
        snapshotFlow {
                val info = listState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
            }
            .distinctUntilChanged()
            .filter { it }
            .collect { vm.history(true) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.padding(padding).fillMaxSize().background(Color(0xFFF7F7F7)),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "search") {
            OutlinedTextField(
                s.query,
                vm::query,
                placeholder = { Text("搜索笔记或 #标签", color = Color.Gray) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color(0xFFE5E5E5),
                        focusedBorderColor = Color.Gray,
                        unfocusedContainerColor = Color.White,
                        focusedContainerColor = Color.White,
                    ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            keyboard?.hide()
                            vm.history()
                        }
                    ),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            vm.history()
                        }
                    ) {
                        Icon(Icons.Outlined.Search, "搜索")
                    }
                },
            )
            s.syncError?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.clickable { vm.retrySync() }.padding(vertical = 6.dp),
                )
            }
            if (s.syncStates.isNotEmpty())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${s.syncStates.size} 条待同步",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::retrySync) { Text("重试同步") }
                }
            if (s.query.startsWith("#"))
                TagSuggestions(s.tagSuggestions) {
                    keyboard?.hide()
                    vm.searchTag(it)
                }
        }
        items(s.posts, key = { it.id.toString() }, contentType = { "note" }) { post ->
            val date =
                remember(post.createdAt) {
                    java.time.Instant.parse(post.createdAt)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                }
            val preview =
                remember(post.title, post.preview) {
                    (post.title ?: post.preview.orEmpty())
                        .replace(Regex("(?m)^#{1,6}\\s+"), "")
                        .replace("**", "")
                        .replace("`", "")
                        .ifBlank { "照片笔记" }
                }
            Column(
                Modifier.fillMaxWidth()
                    .background(Color.White, RoundedCornerShape(14.dp))
                    .clickable { vm.open(post.id.toString()) }
                    .padding(16.dp)
            ) {
                Text(date, color = Color.Gray, fontSize = 12.sp)
                s.syncStates[post.id.toString()]?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = if (it == "待同步") Color.Gray else Color(0xFFB34D43),
                        maxLines = 3,
                    )
                }
                if (s.syncStates[post.id.toString()]?.contains("404") == true)
                    TextButton(onClick = { vm.recoverMissingNote(post.id.toString()) }) {
                        Text("另存为新笔记")
                    }
                TagRow(post.tags, vm::searchTag)
                Spacer(Modifier.height(6.dp))
                Text(
                    preview,
                    fontSize = 16.sp,
                    lineHeight = 25.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF292929),
                )
            }
        }
        item(key = "loading") {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                if (s.listLoading)
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else if (s.listError)
                    TextButton(onClick = { vm.history(s.cursor != null) }) { Text("加载失败，点击重试") }
                else if (s.posts.isEmpty()) Text("还没有笔记", color = Color.Gray)
            }
        }
    }
}
