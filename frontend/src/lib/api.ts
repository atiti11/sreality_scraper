import type {
  DealType, PropertyType, RegionLevel, MapLevel,
  MarkerData, RegionFeatureCollection, RegionStats, PriceChange,
  Listing, ListingsFilters, RegionsTree,
  CsuMetricKey, CsuMetricInfo, ScatterResponse,
} from "./types";
import { useAuthStore } from "./auth";

const BASE = "";

function qs(params: Record<string, unknown>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "" || (Array.isArray(v) && v.length === 0)) continue;
    u.set(k, Array.isArray(v) ? v.join(",") : String(v));
  }
  const s = u.toString();
  return s ? `?${s}` : "";
}

async function get<T>(path: string, params: Record<string, unknown> = {}): Promise<T> {
  // Every /api/* call carries the Basic Auth header that the React login
  // screen captured. getAuthHeader() returns ``{}`` when no creds are
  // present (e.g. before the user has signed in), so a stray pre-login
  // request just falls through to the backend as anonymous.
  const r = await fetch(`${BASE}${path}${qs(params)}`, {
    headers: useAuthStore.getState().getAuthHeader(),
  });
  if (r.status === 401) {
    // Server rejected the creds (wrong password, or DASHBOARD_USER changed
    // server-side mid-session). Bounce back to the login screen.
    useAuthStore.getState().logout();
    throw new Error(`401 Unauthorized for ${path}`);
  }
  if (!r.ok) throw new Error(`${r.status} ${r.statusText} for ${path}`);
  return (await r.json()) as T;
}

/**
 * Public probe that returns the auth gate's view of the world without
 * triggering the 401-logout side-effect baked into ``get<T>``. Used by
 * ``App.tsx`` on first paint to decide whether the login screen is
 * needed at all (the gate is bypassed when DASHBOARD_USER is unset).
 */
export async function probeAuth(): Promise<{ user: string | null; authenticated: boolean }> {
  try {
    const r = await fetch("/api/auth/me", {
      headers: useAuthStore.getState().getAuthHeader(),
    });
    if (r.ok) {
      return await r.json() as { user: string | null; authenticated: boolean };
    }
  } catch {
    /* network error — leave the SPA on the login screen */
  }
  return { user: null, authenticated: false };
}

export const api = {
  health:       () => get<{ status: string; db: boolean }>("/api/health"),

  regionsTree:  () => get<RegionsTree>("/api/regions/tree"),

  markers: (
    level: MapLevel,
    deal: DealType,
    propertyTypes: PropertyType[],
    bbox?: [number, number, number, number] | null,
  ) =>
    get<{ level: string; markers: MarkerData[] }>(`/api/markers/${level}`, {
      deal,
      property_types: propertyTypes,
      // Backend expects "minlon,minlat,maxlon,maxlat". Only meaningful for
      // obec / cast_obce — the SQL is bbox-aware for those levels only.
      bbox: bbox ? bbox.join(",") : undefined,
    }).then((r) => r.markers),

  geo: (
    level: MapLevel,
    deal: DealType,
    propertyTypes: PropertyType[],
    parentId?: number,
  ) =>
    get<RegionFeatureCollection>(`/api/geo/${level}`, {
      deal,
      property_types: propertyTypes,
      parent_id: parentId,
    }),

  regionStats: (
    level: RegionLevel, id: number,
    deal: DealType, propertyTypes: PropertyType[],
  ) =>
    get<RegionStats>(`/api/region/${level}/${id}/stats`, {
      deal, property_types: propertyTypes,
    }),

  regionPriceChanges: (
    level: RegionLevel, id: number,
    deal: DealType, propertyTypes: PropertyType[],
    limit = 20,
  ) =>
    get<PriceChange[]>(`/api/region/${level}/${id}/price-changes`, {
      deal, property_types: propertyTypes, limit,
    }),

  listings: (f: ListingsFilters, limit = 50, offset = 0) =>
    get<Listing[]>("/api/listings", {
      deal: f.deal,
      property_types: f.propertyTypes,
      region_level: f.regionLevel,
      region_id: f.regionId,
      price_min: f.priceMin,
      price_max: f.priceMax,
      per_m2_min: f.perM2Min,
      per_m2_max: f.perM2Max,
      area_min: f.areaMin,
      area_max: f.areaMax,
      unemployment_max: f.unemploymentMax,
      sort: f.sort,
      limit, offset,
    }),

  listingsCount: (f: ListingsFilters) =>
    get<{ n: number }>("/api/listings/count", {
      deal: f.deal,
      property_types: f.propertyTypes,
      region_level: f.regionLevel,
      region_id: f.regionId,
      price_min: f.priceMin,
      price_max: f.priceMax,
      per_m2_min: f.perM2Min,
      per_m2_max: f.perM2Max,
      area_min: f.areaMin,
      area_max: f.areaMax,
      unemployment_max: f.unemploymentMax,
    }),

  // -------------------------------------------------------------------------
  // Correlation page — scatter of avg price/m² (per obec) against a chosen
  // CSU metric. The catalog endpoint lets us populate the metric dropdown
  // without duplicating the whitelist on the client.
  // -------------------------------------------------------------------------
  csuMetrics: () => get<CsuMetricInfo[]>("/api/scatter/csu-metrics"),

  scatterPriceVsCsu: (
    metric: CsuMetricKey,
    deal: DealType,
    propertyTypes: PropertyType[],
    minListings = 3,
  ) =>
    get<ScatterResponse>("/api/scatter/price-vs-csu", {
      metric,
      deal,
      property_types: propertyTypes,
      min_listings: minListings,
    }),
};
