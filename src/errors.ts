/** Expected API failures carry stable machine codes without leaking infrastructure errors. */
export class ApiError extends Error {
  /** Creates an HTTP failure that is safe to expose to the caller. */
  constructor(public status: 400 | 404 | 409 | 410 | 422, public code: string, message: string) { super(message); }
}
