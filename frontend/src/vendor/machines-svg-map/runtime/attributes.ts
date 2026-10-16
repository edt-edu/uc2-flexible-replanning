/**
 * Conversions of raw controller attribute values (as published by the PLC / the gateway: booleans, numbers, or their
 * string forms).
 */

/** Lower case, without separators: `sensor_SL_in` and `sensorSLIn` both give `sensorslin`. */
export function normalizeAttribute(attribute: string): string {
  return attribute.toLowerCase().replace(/[^a-z0-9]/g, '');
}

/** true / false, 1 / 0 (any non-zero number is true), "on" / "off"; null for anything else. */
export function asBoolean(value: unknown): boolean | null {
  if (typeof value === 'boolean') {
    return value;
  }
  if (typeof value === 'number') {
    return Number.isNaN(value) ? null : Math.trunc(value) !== 0;
  }
  if (typeof value === 'string') {
    const text = value.trim().toLowerCase();
    if (text === 'true' || text === '1' || text === 'on') return true;
    if (text === 'false' || text === '0' || text === 'off') return false;
  }
  return null;
}

/** A number or a numeric string; null for anything else. */
export function asNumber(value: unknown): number | null {
  if (typeof value === 'number') {
    return Number.isFinite(value) ? value : null;
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const number = Number(value.trim());
    return Number.isFinite(number) ? number : null;
  }
  return null;
}

/** An object, or a JSON object string; null for anything else. */
export function asRecord(value: unknown): Record<string, unknown> | null {
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  if (typeof value === 'string') {
    try {
      return asRecord(JSON.parse(value));
    } catch {
      return null;
    }
  }
  return null;
}
