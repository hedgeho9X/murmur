/**
 * 读取并校验后端运行配置。密钥仅供服务和本地脚本使用，不向 API 响应输出。
 */
import { z } from "zod";
export const Config = z.object({
  DATABASE_URL: z.string().url(),
  API_TOKEN: z.string().min(24),
  PORT: z.coerce.number().default(8787),
  HOST: z.string().default("127.0.0.1"),
  S3_ENDPOINT: z.string().url(),
  S3_PUBLIC_ENDPOINT: z.string().url(),
  S3_ACCESS_KEY: z.string().min(1),
  S3_SECRET_KEY: z.string().min(24),
  S3_BUCKET: z.string().min(1),
  S3_REGION: z.string().default("us-east-1"),
});
/**
 * 从进程环境读取配置并补齐默认值，返回通过校验的配置对象。
 * 缺失或无效时抛错，错误中只包含配置项名称，不包含密钥值。
 */
export function config() {
  const result = Config.safeParse(process.env);
  if (!result.success)
    throw new Error(
      "Invalid configuration keys: " +
        result.error.issues.map((i) => i.path.join(".")).join(", "),
    );
  return result.data;
}
