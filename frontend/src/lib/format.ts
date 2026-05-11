/**
 * Number / currency formatters. We display in CZK because that's the source
 * data; UI text is in English per the spec.
 */

const en = new Intl.NumberFormat("en-US");

export function num(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return "—";
  return en.format(Math.round(v));
}

export function czk(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return "—";
  if (Math.abs(v) >= 1e9) return `${en.format(Number((v / 1e9).toFixed(2)))} bn CZK`;
  if (Math.abs(v) >= 1e6) return `${en.format(Number((v / 1e6).toFixed(2)))} mn CZK`;
  if (Math.abs(v) >= 1e3) return `${en.format(Math.round(v / 1000))} k CZK`;
  return `${en.format(Math.round(v))} CZK`;
}

export function czkPerM2(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return "—";
  return `${en.format(Math.round(v))} CZK/m²`;
}

export function area(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return "—";
  return `${en.format(Math.round(v))} m²`;
}

export function pct(v: number | null | undefined, digits = 1): string {
  if (v == null || Number.isNaN(v)) return "—";
  return `${v.toFixed(digits)} %`;
}

export function relativeDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  const d = new Date(iso);
  const days = Math.floor((Date.now() - d.getTime()) / (24 * 3600 * 1000));
  if (days === 0) return "today";
  if (days === 1) return "yesterday";
  if (days < 30) return `${days} days ago`;
  if (days < 365) return `${Math.floor(days / 30)} months ago`;
  return d.toLocaleDateString("en-GB");
}
