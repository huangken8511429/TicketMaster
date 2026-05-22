import { forwardRef } from 'react';
import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'sm' | 'md' | 'lg';

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: ButtonVariant;
  size?: ButtonSize;
  loading?: boolean;
  leadingIcon?: ReactNode;
};

const VARIANT_STYLES: Record<ButtonVariant, string> = {
  primary:
    'bg-accent text-fg-inverse hover:bg-accent-hover active:bg-accent-pressed hover:shadow-glow-accent',
  secondary:
    'bg-transparent text-fg-primary border border-line-strong hover:bg-surface-2',
  ghost: 'bg-transparent text-fg-secondary hover:bg-surface hover:text-fg-primary',
  danger: 'bg-signal-error text-fg-primary hover:brightness-95',
};

const SIZE_STYLES: Record<ButtonSize, string> = {
  sm: 'px-4 py-2 text-body-sm',
  md: 'px-5 py-3 text-body-md',
  lg: 'px-6 py-4 text-body-lg font-bold',
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant = 'primary',
    size = 'md',
    loading = false,
    leadingIcon,
    className,
    disabled,
    children,
    ...rest
  },
  ref,
) {
  const isDisabled = disabled || loading;
  return (
    <button
      ref={ref}
      disabled={isDisabled}
      className={cn(
        'inline-flex items-center justify-center gap-2 rounded-sm font-medium tracking-tight',
        'transition-[background-color,color,box-shadow,transform] duration-base ease-standard',
        'disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none',
        'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent',
        VARIANT_STYLES[variant],
        SIZE_STYLES[size],
        className,
      )}
      {...rest}
    >
      {loading ? (
        <span
          aria-hidden
          className="h-4 w-4 animate-spin rounded-full border-2 border-current border-r-transparent"
        />
      ) : (
        leadingIcon
      )}
      <span>{children}</span>
    </button>
  );
});
