import { useEffect, useState } from "react";
import {
  LayoutGrid, LogOut, Map as MapIcon, Table as TableIcon,
  ScatterChart as ScatterIcon,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useAppStore } from "@/lib/store";
import { useAuthStore } from "@/lib/auth";
import { probeAuth } from "@/lib/api";
import { MapPage } from "@/pages/map-page";
import { ListingsPage } from "@/pages/listings-page";
import { CorrelationPage } from "@/pages/correlation-page";
import { LoginPage } from "@/pages/login-page";

/**
 * Top-level shell.
 *
 * Render order:
 *   1. While we're probing /api/auth/me on first paint, show a thin
 *      loading state so we don't flash the login screen at users who
 *      already have valid sessionStorage creds (or at deployments where
 *      auth is disabled).
 *   2. If the backend reports it has no DASHBOARD_USER configured, skip
 *      the login screen entirely — there's nothing to verify against.
 *   3. Otherwise, gate everything behind {@link LoginPage}.
 */
export default function App() {
  const tab               = useAppStore((s) => s.tab);
  const setTab            = useAppStore((s) => s.setTab);
  const isAuthenticated   = useAuthStore((s) => s.isAuthenticated);
  const setBypass         = useAuthStore((s) => s.setBypass);

  // Initial probe — figures out whether auth is even required on this
  // deployment, and validates any sessionStorage creds the user might
  // still have from a previous tab.
  const [probed, setProbed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    probeAuth().then((r) => {
      if (cancelled) return;
      // Server says auth is disabled — log the SPA in unconditionally.
      if (r.authenticated && !useAuthStore.getState().user) {
        setBypass();
      }
      setProbed(true);
    });
    return () => { cancelled = true; };
  }, [setBypass]);

  // Spinner pre-empts both LoginPage and the dashboard until the probe
  // resolves. Keeps a refreshed tab from flashing the login form.
  if (!probed) {
    return (
      <div className="min-h-screen grid place-items-center bg-muted/20">
        <div className="text-xs uppercase tracking-[0.18em] text-muted-foreground">
          Loading…
        </div>
      </div>
    );
  }

  if (!isAuthenticated) {
    return <LoginPage />;
  }

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-[500] border-b bg-background/80 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-[1600px] items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <div className="grid h-9 w-9 place-items-center rounded-xl bg-primary text-primary-foreground shadow-sm">
              <LayoutGrid className="h-5 w-5" />
            </div>
            <div>
              <div className="text-[10px] font-medium uppercase tracking-[0.18em] text-muted-foreground">
                NDBI046
              </div>
              <div className="text-sm font-semibold tracking-tight leading-tight">
                Czech Real-Estate Dashboard
              </div>
            </div>
            <Badge variant="secondary" className="ml-2 rounded-full hidden md:inline-flex">
              sreality.cz
            </Badge>
          </div>

          <div className="flex items-center gap-3">
            <nav className="flex items-center gap-1 rounded-full border bg-background/60 p-1 shadow-sm">
              <TabButton
                active={tab === "map"}
                onClick={() => setTab("map")}
                icon={<MapIcon className="h-3.5 w-3.5" />}
                label="Map"
              />
              <TabButton
                active={tab === "listings"}
                onClick={() => setTab("listings")}
                icon={<TableIcon className="h-3.5 w-3.5" />}
                label="Listings"
              />
              <TabButton
                active={tab === "correlation"}
                onClick={() => setTab("correlation")}
                icon={<ScatterIcon className="h-3.5 w-3.5" />}
                label="Correlation"
              />
            </nav>

            <UserMenu />
          </div>
        </div>
      </header>

      {tab === "map" && <MapPage />}
      {tab === "listings" && (
        <main className="mx-auto max-w-[1600px] px-6 py-6">
          <ListingsPage />
        </main>
      )}
      {tab === "correlation" && (
        <main className="mx-auto max-w-[1600px] px-6 py-6">
          <CorrelationPage />
        </main>
      )}
    </div>
  );
}

// ----------------------------------------------------------------------------

function TabButton({
  active, onClick, icon, label,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-4 py-1.5 text-sm font-medium transition-all",
        active
          ? "bg-primary text-primary-foreground shadow-sm"
          : "text-muted-foreground hover:text-foreground hover:bg-muted",
      )}
    >
      {icon}
      {label}
    </button>
  );
}

/**
 * Header chip showing the signed-in user and a sign-out button. Hidden
 * entirely in bypass mode (no DASHBOARD_USER on the backend) since
 * there's nothing meaningful to log out of.
 */
function UserMenu() {
  const user   = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);

  if (!user) return null;

  return (
    <div className="flex items-center gap-2">
      <Badge variant="secondary" className="rounded-full hidden md:inline-flex">
        {user}
      </Badge>
      <Button
        variant="ghost"
        size="sm"
        onClick={logout}
        className="rounded-full text-muted-foreground hover:text-foreground"
        aria-label="Sign out"
        title="Sign out"
      >
        <LogOut className="h-4 w-4" />
        <span className="ml-1.5 hidden sm:inline">Sign out</span>
      </Button>
    </div>
  );
}
