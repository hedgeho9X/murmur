/** 从用户正文派生搜索标签；不改写正文，不把代码或链接片段当作标签。 */
import type { NewPost } from "./posts.contracts.js";

/** 提取独立的 #标签，统一 Unicode 和大小写；去重并忽略超过 32 字符的名称。 */
export function extractTags(content: NewPost["content"]): string[] {
  const tags = new Set<string>();
  for (const part of content.parts) {
    if (part.type !== "text") continue;
    const text = part.text.replace(/```[\s\S]*?(?:```|$)|`[^`\n]*`/g, " ");
    for (const match of text.matchAll(
      /(?:^|[\s（(，,。！？!?；;：:])#([\p{L}\p{N}_][\p{L}\p{N}_/-]*)/gu,
    )) {
      const tag = match[1].normalize("NFKC").toLowerCase();
      if (tag.length <= 32) tags.add(tag);
    }
  }
  return [...tags];
}
