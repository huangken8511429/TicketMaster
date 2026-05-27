/**
 * Thin fetch wrapper.
 * - Resolves base URL from VITE_API_BASE_URL.
 * - Tolerates both `{error: string}` and plain-text error shapes (api-contract.md §1).
 * - Throws `ApiError` so React Query can pick up `.status`.
 */

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? '';

export class ApiError extends Error {
  status: number;
  payload: unknown;

  constructor(status: number, message: string, payload: unknown) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.payload = payload;
  }
}

type FetchOptions = Omit<RequestInit, 'body'> & {
  body?: unknown;
  /** Return raw Response without parsing — for SSE / 202 polling. */
  raw?: boolean;
};

export async function apiFetch<T = unknown>(path: string, opts: FetchOptions = {}): Promise<T> {
  const { body, raw, headers, ...rest } = opts;
  const url = path.startsWith('http') ? path : `${API_BASE_URL}${path}`;

  const init: RequestInit = {
    ...rest,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  };

  const res = await fetch(url, init);

  if (raw) {
    return res as unknown as T;
  }

  if (res.status === 204) {
    return undefined as T;
  }

  // Read body once — try JSON, fall back to text.
  const text = await res.text();
  let parsed: unknown = text;
  if (text) {
    try {
      parsed = JSON.parse(text);
    } catch {
      // keep as text
    }
  }

  if (!res.ok) {
    const message =
      (parsed && typeof parsed === 'object' && 'error' in parsed
        ? String((parsed as { error: unknown }).error)
        : typeof parsed === 'string'
          ? parsed
          : res.statusText) || `HTTP ${res.status}`;
    throw new ApiError(res.status, message, parsed);
  }

  return parsed as T;
}

/** Same as apiFetch but exposes raw Response — for endpoints where we need status differentiation (e.g. 202 vs 200). */
export async function apiFetchRaw(path: string, opts: FetchOptions = {}): Promise<Response> {
  const { body, headers, ...rest } = opts;
  const url = path.startsWith('http') ? path : `${API_BASE_URL}${path}`;
  return fetch(url, {
    ...rest,
    headers: {
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
}
