import { Link, NavLink, Outlet } from 'react-router-dom';
import { ToastViewport } from '@/components/Toast';
import { cn } from '@/lib/cn';

const NAV_ITEMS = [
  { to: '/', label: 'Events' },
];

export function Layout() {
  return (
    <div className="min-h-screen flex flex-col">
      <header
        className={cn(
          'sticky top-0 z-sticky',
          'bg-ink/85 backdrop-blur border-b border-line-subtle',
        )}
      >
        <div className="mx-auto max-w-7xl px-6 py-4 flex items-center justify-between gap-6">
          <Link to="/" className="flex items-center gap-2 group">
            <span className="inline-block h-2 w-6 bg-accent rounded-sm" aria-hidden />
            <span className="text-heading-md font-extrabold tracking-tight">
              ticket<span className="text-accent">/</span>master
            </span>
          </Link>
          <nav className="flex items-center gap-1">
            {NAV_ITEMS.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.to === '/'}
                className={({ isActive }) =>
                  cn(
                    'px-3 py-2 rounded-sm text-body-sm transition-colors duration-fast',
                    isActive ? 'text-accent' : 'text-fg-secondary hover:text-fg-primary',
                  )
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </div>
      </header>

      <main className="flex-1">
        <Outlet />
      </main>

      <footer className="border-t border-line-subtle">
        <div className="mx-auto max-w-7xl px-6 py-6 text-caption text-fg-tertiary uppercase tracking-[0.12em] flex items-center justify-between">
          <span>TicketMaster MVP — Frontend Phase 2 Skeleton</span>
          <span className="font-mono">v0.1.0</span>
        </div>
      </footer>

      <ToastViewport />
    </div>
  );
}
