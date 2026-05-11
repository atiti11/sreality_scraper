import { create } from "zustand";

/**
 * Session-scoped HTTP-Basic auth store.
 *
 * The dashboard backend validates ``Authorization: Basic <base64>`` on
 * every /api/* call. This store holds the user-entered credentials in
 * sessionStorage (so an F5 doesn't force a re-login but closing the
 * browser does), exposes them as a request header for ``api.ts`` and
 * tracks an ``isAuthenticated`` flag the React shell uses to choose
 * between the login screen and the normal app.
 *
 * Security tradeoffs are inherent to Basic Auth: the password lives in
 * memory + sessionStorage and is therefore visible to any script
 * running on this origin. For a single-user internal dashboard that's
 * an acceptable price for a smooth UX (no native browser dialog, no
 * server-side session table to manage). We never write to
 * localStorage so the secret evaporates the moment the tab closes.
 */

interface AuthState {
  user: string | null;
  password: string | null;
  isAuthenticated: boolean;
  authError: string | null;
  pending: boolean;
  login: (user: string, password: string) => Promise<boolean>;
  logout: () => void;
  /** Bypassed-auth mode reported by the backend (no DASHBOARD_USER set). */
  setBypass: () => void;
  /** Header injected by ``api.ts`` into every fetch. */
  getAuthHeader: () => Record<string, string>;
}

const SESSION_KEY = "sreality-dashboard-auth";

/**
 * Read previously-saved creds out of sessionStorage. Survives F5 in the
 * same tab; cleared on browser close.
 */
function loadSavedCreds(): { user: string; password: string } | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (typeof parsed?.user === "string" && typeof parsed?.password === "string") {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * UTF-8 safe base64 encoder. Plain ``btoa`` chokes on non-Latin-1
 * characters; the username might contain Czech diacritics, so we route
 * the string through TextEncoder first.
 */
function utf8Btoa(s: string): string {
  const bytes = new TextEncoder().encode(s);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

function buildHeader(user: string, password: string): Record<string, string> {
  return { Authorization: `Basic ${utf8Btoa(`${user}:${password}`)}` };
}

const saved = loadSavedCreds();

export const useAuthStore = create<AuthState>((set, get) => ({
  user: saved?.user ?? null,
  password: saved?.password ?? null,
  isAuthenticated: !!saved,
  authError: null,
  pending: false,

  login: async (user, password) => {
    set({ pending: true, authError: null });
    try {
      const r = await fetch("/api/auth/me", {
        headers: buildHeader(user, password),
      });
      if (r.status === 401) {
        set({ pending: false, authError: "Invalid username or password." });
        return false;
      }
      if (!r.ok) {
        set({ pending: false, authError: `Server error (${r.status}).` });
        return false;
      }
      // Persist for the rest of the tab's lifetime — sessionStorage,
      // not localStorage, so a closed window forgets the password.
      sessionStorage.setItem(SESSION_KEY, JSON.stringify({ user, password }));
      set({
        user,
        password,
        isAuthenticated: true,
        pending: false,
        authError: null,
      });
      return true;
    } catch {
      set({ pending: false, authError: "Cannot reach the server." });
      return false;
    }
  },

  logout: () => {
    sessionStorage.removeItem(SESSION_KEY);
    set({
      user: null,
      password: null,
      isAuthenticated: false,
      authError: null,
    });
  },

  setBypass: () => {
    // Backend says auth is disabled (DASHBOARD_USER unset). Pretend the
    // user is signed in so the shell renders the normal app — the
    // backend will accept un-authenticated requests anyway.
    set({
      user: null,
      password: null,
      isAuthenticated: true,
      authError: null,
    });
  },

  getAuthHeader: () => {
    const { user, password } = get();
    if (!user || !password) return {};
    return buildHeader(user, password);
  },
}));
