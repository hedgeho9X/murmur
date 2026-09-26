/**
 * 提供响应快照的 JSON 序列化辅助函数，不负责业务字段校验。
 */
/**
 * 将可序列化数据转换为 JSON 快照，日期转为 ISO 字符串，供接口响应使用。
 * 返回新的对象；循环引用等不可序列化输入会抛错。泛型保留调用侧结构，不描述日期的转换。
 */
export function wire<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}
