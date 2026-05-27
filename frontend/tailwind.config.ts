import type { Config } from 'tailwindcss';

const config: Config = {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        ink: 'var(--bg-ink)',
        surface: {
          DEFAULT: 'var(--bg-surface)',
          2: 'var(--bg-surface-2)',
          elevated: 'var(--bg-elevated)',
        },
        line: {
          subtle: 'var(--border-subtle)',
          strong: 'var(--border-strong)',
        },
        fg: {
          primary: 'var(--fg-primary)',
          secondary: 'var(--fg-secondary)',
          tertiary: 'var(--fg-tertiary)',
          inverse: 'var(--fg-inverse)',
        },
        accent: {
          DEFAULT: 'var(--accent)',
          hover: 'var(--accent-hover)',
          pressed: 'var(--accent-pressed)',
          muted: 'var(--accent-muted)',
        },
        status: {
          plenty: 'var(--status-plenty)',
          limited: 'var(--status-limited)',
          few: 'var(--status-few)',
          'sold-out': 'var(--status-sold-out)',
        },
        signal: {
          error: 'var(--error)',
          warning: 'var(--warning)',
          info: 'var(--info)',
        },
      },
      fontFamily: {
        sans: ['"Inter Tight"', 'system-ui', '-apple-system', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      fontSize: {
        'display-xl': ['4.5rem', { lineHeight: '1.05', letterSpacing: '-0.02em' }],
        'display-lg': ['3.5rem', { lineHeight: '1.05', letterSpacing: '-0.02em' }],
        'display-md': ['2.5rem', { lineHeight: '1.1', letterSpacing: '-0.015em' }],
        'heading-lg': ['1.75rem', { lineHeight: '1.2', letterSpacing: '-0.01em' }],
        'heading-md': ['1.375rem', { lineHeight: '1.25', letterSpacing: '-0.01em' }],
        'body-lg': ['1.125rem', { lineHeight: '1.45' }],
        'body-md': ['1rem', { lineHeight: '1.5' }],
        'body-sm': ['0.875rem', { lineHeight: '1.5' }],
        caption: ['0.75rem', { lineHeight: '1.45', letterSpacing: '0.02em' }],
        'mono-display': ['4rem', { lineHeight: '1', letterSpacing: '-0.02em' }],
      },
      spacing: {
        0: '0',
        1: '0.25rem',
        2: '0.5rem',
        3: '0.75rem',
        4: '1rem',
        5: '1.5rem',
        6: '2rem',
        8: '3rem',
        10: '4rem',
        12: '6rem',
        16: '8rem',
      },
      borderRadius: {
        none: '0',
        sm: '4px',
        md: '8px',
        lg: '12px',
        pill: '9999px',
      },
      borderWidth: {
        DEFAULT: '1px',
        2: '2px',
      },
      boxShadow: {
        none: 'none',
        sm: '0 1px 2px rgba(0,0,0,0.4)',
        md: '0 4px 12px rgba(0,0,0,0.5)',
        'glow-accent': '0 0 24px rgba(214,255,61,0.3)',
      },
      transitionTimingFunction: {
        standard: 'cubic-bezier(0.2, 0, 0, 1)',
        decel: 'cubic-bezier(0, 0, 0.2, 1)',
        accel: 'cubic-bezier(0.4, 0, 1, 1)',
        snap: 'cubic-bezier(0.65, 0, 0.35, 1)',
      },
      transitionDuration: {
        instant: '0ms',
        fast: '120ms',
        base: '200ms',
        slow: '320ms',
        slower: '600ms',
        pulse: '1600ms',
        queue: '2400ms',
      },
      zIndex: {
        base: '0',
        sticky: '10',
        dropdown: '100',
        'modal-backdrop': '1000',
        modal: '1010',
        toast: '2000',
        'queue-overlay': '9000',
      },
      screens: {
        md: '768px',
        lg: '1024px',
        xl: '1440px',
      },
      keyframes: {
        'badge-pulse': {
          '0%, 100%': {
            boxShadow: '0 0 0 0 var(--status-few)',
            opacity: '1',
          },
          '50%': {
            boxShadow: '0 0 0 6px transparent',
            opacity: '0.85',
          },
        },
        'dot-pulse': {
          '0%, 100%': { opacity: '1', transform: 'scale(1)' },
          '50%': { opacity: '0.5', transform: 'scale(1.4)' },
        },
        'queue-ring': {
          '0%': { transform: 'rotate(0deg) scale(1)', opacity: '0.6' },
          '50%': { transform: 'rotate(180deg) scale(1.08)', opacity: '0.9' },
          '100%': { transform: 'rotate(360deg) scale(1)', opacity: '0.6' },
        },
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(8px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'badge-pulse': 'badge-pulse 1.6s ease-in-out infinite',
        'dot-pulse': 'dot-pulse 1.6s ease-in-out infinite',
        'queue-ring': 'queue-ring 2.4s cubic-bezier(0.65, 0, 0.35, 1) infinite',
        'fade-up': 'fade-up 320ms cubic-bezier(0.2, 0, 0, 1)',
      },
    },
  },
  plugins: [],
};

export default config;
