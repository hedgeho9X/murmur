/** 轻量标签组件：显示已有标签与前缀补全，不负责网络与持久化。 */
package cn.hedgeho9.murmur

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.hedgeho9.murmur.api.models.TagSuggestion

/** 横向显示标签，点击行为由搜索调用方决定。 */
@Composable
fun TagRow(tags: List<String>, onClick: (String) -> Unit) {
    if (tags.isNotEmpty())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(tags) { tag ->
                Text(
                    "#$tag",
                    color = Color(0xFF6B88BD),
                    modifier = Modifier.clickable { onClick(tag) }.padding(vertical = 8.dp),
                )
            }
        }
}

/** 展示服务端补全及帖子数量，选择后交给调用方精确搜索。 */
@Composable
fun TagSuggestions(items: List<TagSuggestion>, onSelect: (String) -> Unit) {
    Column {
        items.forEach { tag ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(tag.name) }.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("#${tag.name}")
                Text(tag.count.toString(), color = Color.Gray)
            }
        }
    }
}
