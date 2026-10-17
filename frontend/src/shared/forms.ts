import { toApiRequestError } from './api/client';

/** Maps backend field errors to { field: message } for inline display. */
export function fieldErrors(error: unknown): Record<string, string> {
  if (!error) return {};
  const err = toApiRequestError(error);
  const map: Record<string, string> = {};
  for (const fe of err.fieldErrors) {
    // Bean Validation reports record-level checks on synthetic properties (e.g. "bloodPressureValid").
    map[fe.field] = fe.message;
  }
  return map;
}

/** Errors not tied to a specific field (shown above the form). */
export function formLevelError(error: unknown, knownFields: string[]): string | null {
  if (!error) return null;
  const err = toApiRequestError(error);
  const unmatched = err.fieldErrors.filter((f) => !knownFields.includes(f.field));
  if (err.fieldErrors.length > 0 && unmatched.length === 0) return null;
  if (unmatched.length > 0) return unmatched.map((f) => f.message).join('; ');
  return err.message;
}
