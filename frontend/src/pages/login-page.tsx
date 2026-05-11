import { useState } from "react";
import { LayoutGrid, Lock, LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuthStore } from "@/lib/auth";

/**
 * Single-card login screen. Rendered by {@code App.tsx} whenever the
 * auth store reports ``isAuthenticated === false``; the dashboard's
 * normal shell takes over the moment {@link useAuthStore} flips it back.
 *
 * Behaviour:
 *   - The form posts to the auth store, which probes the backend's
 *     /api/auth/me with a Basic Auth header. Success → credentials are
 *     stashed in sessionStorage and the SPA navigates to the previous
 *     state. Failure → an error message is rendered under the password
 *     field and the form stays mounted.
 *   - We don't block paint with a spinner because the verify call is
 *     fast (single SELECT on the backend); the button just becomes
 *     disabled with a "Signing in…" label.
 */
export function LoginPage() {
  const login   = useAuthStore((s) => s.login);
  const pending = useAuthStore((s) => s.pending);
  const error   = useAuthStore((s) => s.authError);

  const [user, setUser] = useState("");
  const [pass, setPass] = useState("");

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!user || !pass) return;
    login(user, pass);
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-muted/30 via-background to-muted/30 p-4">
      <Card className="w-full max-w-sm p-6 sm:p-8 shadow-xl border-border/60">
        {/* Brand block — matches the sticky header of the main app so the
            transition from login to dashboard feels continuous. */}
        <div className="flex flex-col items-center text-center mb-6">
          <div className="grid h-12 w-12 place-items-center rounded-xl bg-primary text-primary-foreground shadow-md mb-3">
            <LayoutGrid className="h-6 w-6" />
          </div>
          <div className="text-[10px] font-medium uppercase tracking-[0.18em] text-muted-foreground">
            NDBI046
          </div>
          <h1 className="text-base font-semibold tracking-tight leading-tight mt-0.5">
            Czech Real-Estate Dashboard
          </h1>
          <p className="text-xs text-muted-foreground mt-2">
            Sign in to continue
          </p>
        </div>

        <form onSubmit={onSubmit} className="space-y-4">
          <div className="space-y-1.5">
            <Label htmlFor="user">Username</Label>
            <Input
              id="user"
              type="text"
              value={user}
              onChange={(e) => setUser(e.target.value)}
              autoComplete="username"
              autoFocus
              required
              disabled={pending}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="pass">Password</Label>
            <Input
              id="pass"
              type="password"
              value={pass}
              onChange={(e) => setPass(e.target.value)}
              autoComplete="current-password"
              required
              disabled={pending}
            />
          </div>

          {error && (
            <div
              role="alert"
              className="flex items-start gap-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive"
            >
              <Lock className="h-3.5 w-3.5 mt-0.5 shrink-0" />
              <span>{error}</span>
            </div>
          )}

          <Button
            type="submit"
            className="w-full"
            disabled={pending || !user || !pass}
          >
            <LogIn className="h-4 w-4 mr-2" />
            {pending ? "Signing in…" : "Sign in"}
          </Button>
        </form>

        <p className="mt-6 text-center text-[11px] text-muted-foreground">
          Session-only — closing the browser logs you out.
        </p>
      </Card>
    </div>
  );
}
