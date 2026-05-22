import { useToast } from '@/hooks/useToast';
import { cn } from '@/lib/cn';
import type { ToastVariant } from '@/hooks/useToast';

const VARIANT_BAR: Record<ToastVariant, string> = {
  success: 'bg-status-plenty',
  error: 'bg-signal-error',
  info: 'bg-accent',
};

export function ToastViewport() {
  const { toasts, dismiss } = useToast();

  return (
    <div
      role="region"
      aria-live="polite"
      aria-label="通知"
      className="fixed top-5 right-5 z-toast flex flex-col gap-3 max-w-[400px]"
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          role="status"
          className={cn(
            'relative flex items-start gap-3 pl-4 pr-3 py-3',
            'bg-surface-elevated border border-line-subtle rounded-sm shadow-md',
            'animate-fade-up',
          )}
        >
          <span aria-hidden className={cn('absolute left-0 top-0 bottom-0 w-1', VARIANT_BAR[t.variant])} />
          <p className="flex-1 text-body-sm text-fg-primary">{t.message}</p>
          <button
            type="button"
            onClick={() => dismiss(t.id)}
            aria-label="關閉通知"
            className="text-fg-tertiary hover:text-fg-primary transition-colors text-body-sm leading-none px-2 py-0.5"
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
}
