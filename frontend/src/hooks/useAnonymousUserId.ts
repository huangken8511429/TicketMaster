import { useEffect, useState } from 'react';

const STORAGE_KEY = 'tm.anonymousUserId';

function generateId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  // Fallback for older browsers — collision-tolerant enough for an anon UUID.
  return 'anon-' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/**
 * Returns a stable anonymous user id, persisted in localStorage.
 * Empty string returned on first render in SSR / strict mode, hydrates on effect.
 */
export function useAnonymousUserId(): string {
  const [id, setId] = useState<string>(() => {
    if (typeof window === 'undefined') return '';
    const existing = window.localStorage.getItem(STORAGE_KEY);
    if (existing) return existing;
    const next = generateId();
    window.localStorage.setItem(STORAGE_KEY, next);
    return next;
  });

  useEffect(() => {
    if (!id && typeof window !== 'undefined') {
      const existing = window.localStorage.getItem(STORAGE_KEY);
      const next = existing ?? generateId();
      if (!existing) window.localStorage.setItem(STORAGE_KEY, next);
      setId(next);
    }
  }, [id]);

  return id;
}
