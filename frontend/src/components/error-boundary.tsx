import { Component, type ReactNode } from "react";

interface State {
  error: Error | null;
}

/**
 * Catches render errors anywhere below it and shows them on the page
 * instead of letting React unmount the whole tree into a blank screen.
 *
 * Production builds normally swallow render errors silently; without
 * this, a `TypeError` in a deep component leaves you with an empty body
 * and only the browser console as evidence.
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: { componentStack: string }) {
    // Surface the full stack to the browser console, where the user can
    // copy it out if they need to file a bug.
    console.error("ErrorBoundary caught:", error, info);
  }

  render() {
    if (this.state.error) {
      return (
        <div className="min-h-screen flex items-center justify-center p-8">
          <div className="max-w-2xl w-full rounded-2xl border border-destructive/40 bg-destructive/5 p-6 shadow-lg">
            <h1 className="text-xl font-bold text-destructive">
              Something broke while rendering.
            </h1>
            <p className="mt-2 text-sm text-muted-foreground">
              {this.state.error.message}
            </p>
            <pre className="mt-4 max-h-[60vh] overflow-auto rounded-lg bg-background p-3 text-xs font-mono whitespace-pre-wrap break-all">
              {this.state.error.stack}
            </pre>
            <button
              className="mt-4 rounded-md bg-primary text-primary-foreground px-4 py-2 text-sm font-medium"
              onClick={() => this.setState({ error: null })}
            >
              Try again
            </button>
          </div>
        </div>
      );
    }
    return this.props.children;
  }
}
