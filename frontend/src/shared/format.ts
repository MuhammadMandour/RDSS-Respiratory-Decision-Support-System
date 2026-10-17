const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
const dateTimeFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' });
const timeFmt = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' });

export const formatDate = (iso?: string | null) => (iso ? dateFmt.format(new Date(iso)) : '—');
export const formatDateTime = (iso?: string | null) => (iso ? dateTimeFmt.format(new Date(iso)) : '—');
export const formatTime = (iso?: string | null) => (iso ? timeFmt.format(new Date(iso)) : '—');

const ACRONYMS = new Set(['copd', 'news2', 'spo2', 'dicom', 'hl7', 'icd', 'ct', 'cr', 'dx', 'mr', 'pft']);

/** "SOME_ENUM_VALUE" -> "Some enum value" (known clinical acronyms stay upper-case). */
export function humanize(value?: string | null): string {
  if (!value) return '—';
  const s = value
    .replace(/_/g, ' ')
    .toLowerCase()
    .split(' ')
    .map((w) => (ACRONYMS.has(w) ? w.toUpperCase() : w))
    .join(' ');
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export const orDash = (v: unknown) => (v === null || v === undefined || v === '' ? '—' : String(v));

/** Value for <input type="datetime-local"> in the browser's local time. */
export function toLocalInputValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Local date (YYYY-MM-DD) for <input type="date">. */
export function toDateInputValue(date: Date): string {
  return toLocalInputValue(date).slice(0, 10);
}

/** Converts a datetime-local value to an ISO instant. */
export const localInputToIso = (value: string) => new Date(value).toISOString();

/** Empty strings become undefined so optional fields are omitted from requests. */
export function emptyToUndefined<T extends Record<string, unknown>>(obj: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) out[k] = v === '' ? undefined : v;
  return out as T;
}

export const numOrUndefined = (v: string) => (v.trim() === '' ? undefined : Number(v));
