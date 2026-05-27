import { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type { EventResponse, SectionAvailability } from '@/api/types';
import { Button } from '@/components/Button';
import { useFocusTrap } from '@/hooks/useFocusTrap';
import { cn } from '@/lib/cn';

export type BookingConfirmModalProps = {
  open: boolean;
  event: EventResponse;
  section: SectionAvailability;
  onConfirm: (seatCount: number) => Promise<void> | void;
  onCancel: () => void;
};

const MIN_SEATS = 1;
const MAX_SEATS = 4;

function formatTwd(n: number): string {
  return new Intl.NumberFormat('zh-TW').format(n);
}

function formatDate(iso: string): string {
  try {
    const d = new Date(iso);
    return new Intl.DateTimeFormat('zh-TW', {
      month: 'numeric',
      day: 'numeric',
      weekday: 'short',
      hour: '2-digit',
      minute: '2-digit',
    }).format(d);
  } catch {
    return iso;
  }
}

export function BookingConfirmModal({
  open,
  event,
  section,
  onConfirm,
  onCancel,
}: BookingConfirmModalProps) {
  const [seatCount, setSeatCount] = useState<number>(1);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descId = useId();

  useFocusTrap(dialogRef, open);

  // Reset internal state each time the modal opens for a new section.
  useEffect(() => {
    if (open) {
      setSeatCount(1);
      setSubmitting(false);
      setError(null);
    }
  }, [open, section.section]);

  // ESC to close.
  useEffect(() => {
    if (!open) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !submitting) {
        onCancel();
      }
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [open, submitting, onCancel]);

  // Lock body scroll while open.
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [open]);

  if (!open) return null;

  const hasPrice = typeof section.basePrice === 'number' && section.basePrice > 0;
  const subtotal = hasPrice ? (section.basePrice as number) * seatCount : null;

  const dec = () => setSeatCount((n) => Math.max(MIN_SEATS, n - 1));
  const inc = () => setSeatCount((n) => Math.min(MAX_SEATS, n + 1));

  const handleConfirm = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await onConfirm(seatCount);
    } catch (e) {
      setSubmitting(false);
      setError(e instanceof Error ? e.message : '搶票失敗，請重試');
    }
  };

  const node = (
    <div
      role="presentation"
      onMouseDown={(e) => {
        // Click-outside cancel — only on backdrop, not on content.
        if (e.target === e.currentTarget && !submitting) onCancel();
      }}
      className={cn(
        'fixed inset-0 z-modal-backdrop flex items-center justify-center p-4',
        'bg-black/70 backdrop-blur-[2px]',
        'animate-fade-up',
      )}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={descId}
        className={cn(
          'relative w-full max-w-[480px]',
          'bg-surface-elevated border border-line-strong rounded-md shadow-md',
          'flex flex-col gap-6 p-6 md:p-7',
        )}
      >
        {/* Marker corner — editorial flourish */}
        <span
          aria-hidden
          className="absolute -top-px left-6 h-1 w-12 bg-accent"
        />

        <header className="flex flex-col gap-2">
          <span className="text-caption uppercase tracking-[0.18em] text-fg-tertiary">
            / Confirm Selection
          </span>
          <h2 id={titleId} className="text-display-md font-extrabold tracking-tight leading-tight">
            搶 <span className="text-accent">{section.section}</span> 區門票
          </h2>
          <p id={descId} className="text-body-sm text-fg-secondary">
            {event.name} · {formatDate(event.eventStartTime)}
          </p>
        </header>

        <div className="flex flex-col gap-5">
          <div className="flex flex-col gap-3">
            <span className="text-caption uppercase tracking-[0.12em] text-fg-tertiary">
              張數
            </span>
            <div className="flex items-center gap-4">
              <button
                type="button"
                onClick={dec}
                disabled={seatCount <= MIN_SEATS || submitting}
                aria-label="減少張數"
                className={cn(
                  'h-12 w-12 rounded-sm border border-line-strong',
                  'text-heading-md font-bold text-fg-primary',
                  'hover:bg-surface-2 transition-colors duration-fast',
                  'disabled:opacity-40 disabled:cursor-not-allowed',
                  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
                )}
              >
                −
              </button>
              <span
                aria-live="polite"
                aria-atomic="true"
                className="font-mono text-display-md font-bold text-accent tabular w-16 text-center"
              >
                {seatCount}
              </span>
              <button
                type="button"
                onClick={inc}
                disabled={seatCount >= MAX_SEATS || submitting}
                aria-label="增加張數"
                className={cn(
                  'h-12 w-12 rounded-sm border border-line-strong',
                  'text-heading-md font-bold text-fg-primary',
                  'hover:bg-surface-2 transition-colors duration-fast',
                  'disabled:opacity-40 disabled:cursor-not-allowed',
                  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
                )}
              >
                ＋
              </button>
              <span className="text-caption uppercase tracking-[0.08em] text-fg-tertiary ml-auto">
                上限 {MAX_SEATS} 張
              </span>
            </div>
          </div>

          <div className="border-t border-line-subtle pt-4 flex items-end justify-between">
            <span className="text-body-sm text-fg-secondary">預估金額</span>
            {subtotal !== null ? (
              <span className="font-mono text-heading-lg font-bold text-fg-primary tabular">
                NT$ <span className="text-accent">{formatTwd(subtotal)}</span>
              </span>
            ) : (
              <span className="text-body-sm text-fg-tertiary italic">票價未定</span>
            )}
          </div>
        </div>

        {error && (
          <p role="alert" className="text-body-sm text-signal-error border-l-2 border-signal-error pl-3">
            {error}
          </p>
        )}

        <footer className="flex items-center justify-end gap-3 pt-2">
          <Button variant="ghost" onClick={onCancel} disabled={submitting}>
            取消
          </Button>
          <Button variant="primary" onClick={handleConfirm} loading={submitting}>
            確認搶票
          </Button>
        </footer>
      </div>
    </div>
  );

  // Render into <body> so transforms/overflow on ancestors don't trap it.
  if (typeof document === 'undefined') return node;
  return createPortal(node, document.body);
}
