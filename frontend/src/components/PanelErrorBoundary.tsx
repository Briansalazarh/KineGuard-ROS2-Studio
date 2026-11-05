import React from "react";

interface PanelErrorBoundaryProps {
  children: React.ReactNode;
  panelName: string;
  resetKey?: string | null;
}

export class PanelErrorBoundary extends React.Component<
  PanelErrorBoundaryProps,
  { hasError: boolean }
> {
  state = { hasError: false };

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error(`Rendering failed in ${this.props.panelName}`, error, info);
  }

  componentDidUpdate(previousProps: PanelErrorBoundaryProps) {
    if (this.state.hasError && previousProps.resetKey !== this.props.resetKey) {
      this.setState({ hasError: false });
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <div role="alert" className="rounded-xl border border-amber-500/40 bg-slate-900 p-4 text-amber-200">
          <p>{this.props.panelName} could not be displayed.</p>
          <button className="mt-2 underline" onClick={() => this.setState({ hasError: false })}>
            Retry panel
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
