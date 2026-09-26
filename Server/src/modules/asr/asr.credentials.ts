/** 向百炼申请短期客户端凭据；永久密钥只用于服务端请求，响应不得被缓存。 */
import type { AsrOptions } from "./asr.session.js";
import { ApiError } from "../../common/errors.js";

/** 为十分钟内的录音签发十五分钟凭据，沿用当前配置的地域、业务空间与模型。 */
export async function issueAsrCredentials(
  options: AsrOptions | undefined,
  fetcher: typeof fetch = fetch,
) {
  if (!options?.key)
    throw new ApiError(503, "ASR_NOT_CONFIGURED", "ASR is not configured");
  const endpoint = new URL(options.endpoint);
  if (
    endpoint.protocol !== "wss:" ||
    !endpoint.hostname.endsWith(".aliyuncs.com")
  )
    throw new ApiError(
      503,
      "ASR_DIRECT_UNAVAILABLE",
      "Direct mode requires a supported ASR endpoint",
    );
  const tokenUrl = new URL("/api/v1/tokens", endpoint);
  tokenUrl.protocol = "https:";
  tokenUrl.searchParams.set("expire_in_seconds", "900");
  try {
    const response = await fetcher(tokenUrl, {
      method: "POST",
      headers: { Authorization: `Bearer ${options.key}` },
      signal: AbortSignal.timeout(10000),
      redirect: "error",
    });
    if (!response.ok) throw new Error("upstream rejected credential request");
    const body = (await response.json()) as {
      token?: unknown;
      expires_at?: unknown;
    };
    const now = Math.floor(Date.now() / 1000);
    if (
      typeof body.token !== "string" ||
      !body.token.startsWith("st-") ||
      body.token === options.key ||
      typeof body.expires_at !== "number" ||
      body.expires_at < now + 30 ||
      body.expires_at > now + 1860
    )
      throw new Error("invalid credential response");
    return {
      endpoint: options.endpoint,
      model: options.model,
      token: body.token,
      expires_at: body.expires_at,
    };
  } catch {
    throw new ApiError(
      503,
      "ASR_CREDENTIALS_UNAVAILABLE",
      "Unable to issue temporary ASR credentials",
    );
  }
}
