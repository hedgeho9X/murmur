/** Serialization helpers for durable HTTP response snapshots. */
/** Converts database timestamps into the exact wire representation stored for replay. */
export function wire<T>(value: T): T {
  return JSON.parse(JSON.stringify(value));
}
