/**
 * 定义可向客户端公开的业务异常；底层数据库或存储错误不通过此类直接透出。
 */
/** 可公开的业务异常，携带状态码和错误码；不包含底层凭据或原始请求。 */
export class ApiError extends Error {
  /**
   * 创建包含 HTTP 状态码、稳定业务码和公开提示的异常，不执行日志或网络操作。
   */
  constructor(
    public status: 400 | 404 | 409 | 410 | 422,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
