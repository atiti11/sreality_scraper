/**
 * Shared TypeScript types — kept thin and aligned with the FastAPI responses.
 */

export type DealType = "sale" | "rent" | "auction";

export type PropertyType = "apartment" | "house" | "land" | "commercial";

/**
 * Admin levels used across the dashboard. The map only renders kraj/okres
 * (obec/cast_obce are kept here for the listings-page region picker — those
 * filter by FK joins in the DB, no polygons needed).
 */
export type RegionLevel = "kraj" | "okres" | "obec" | "cast_obce";

export type MapLevel = "kraj" | "okres" | "obec" | "cast_obce";

export interface Filters {
  deal: DealType;
  propertyTypes: PropertyType[];
}

export interface ListingsFilters extends Filters {
  regionLevel: RegionLevel | null;
  regionId: number | null;
  priceMin: number | null;
  priceMax: number | null;
  perM2Min: number | null;
  perM2Max: number | null;
  areaMin: number | null;
  areaMax: number | null;
  unemploymentMax: number | null;
  sort: "newest" | "price_asc" | "price_desc" | "per_m2_asc" | "per_m2_desc" | "area_desc";
}

export interface RegionFeatureProps {
  id: number;
  code: string;
  name: string;
  parent_id: number | null;
  n: number;
  avg_per_m2: number | null;
  median_per_m2: number | null;
}

export interface RegionFeature {
  type: "Feature";
  geometry: GeoJSON.Geometry;
  properties: RegionFeatureProps;
}

export interface RegionFeatureCollection {
  type: "FeatureCollection";
  level: MapLevel;
  features: RegionFeature[];
}

export interface RegionStats {
  level: RegionLevel;
  id: number;
  name: string;
  parent_name: string | null;
  n: number;
  avg_per_m2: number | null;
  median_per_m2: number | null;
  avg_price: number | null;
  median_price: number | null;
  csu_aggregated: boolean;
  csu: {
    year: number;
    population: number | null;
    divorces: number | null;
    marriages: number | null;
    births: number | null;
    deaths: number | null;
    unemployment_pct: number | null;
    migration_balance: number | null;
  } | null;
}

export interface PriceChange {
  property_type: PropertyType;
  hash_id: number;
  url: string | null;
  obec: string | null;
  area: number | null;
  current_price: number | null;
  current_per_m2: number | null;
  changed_at: string | null;
  field: string;
  old_value: string;
  new_value: string;
  delta: number | null;
}

export interface Listing {
  property_type: PropertyType;
  hash_id: number;
  /** Server-synthesised label, e.g. "Apartment 2+1, 65 m²" or "House, 220 m²". */
  title: string;
  /** Apartments only (e.g. "1+kk", "2+1"); null elsewhere. */
  sub_category: string | null;
  price: number | null;
  per_m2: number | null;
  area: number | null;
  first_seen_date: string | null;
  url: string | null;
  obec: string | null;
  okres: string | null;
  kraj: string | null;
}

export interface RegionsTree {
  kraje: { id: number; code: string; name: string }[];
  okresy: { id: number; code: string; name: string; kraj_id: number }[];
  obce: { id: number; code: string; name: string; okres_id: number }[];
}

export interface MarkerData {
  id: number;
  code: string;
  name: string;
  parent_id: number | null;
  lat: number;
  lon: number;
  n: number;
  avg_per_m2: number | null;
  median_per_m2: number | null;
}

// ---------------------------------------------------------------------------
// Correlation page — scatter of avg price / m² vs CSU metric.
// ---------------------------------------------------------------------------
export type CsuMetricKey =
  | "population"
  | "unemployment_pct"
  | "marriages"
  | "divorces"
  | "births"
  | "deaths"
  | "migration_balance";

export interface CsuMetricInfo {
  key: CsuMetricKey;
  label: string;
  unit: string;
}

export interface ScatterPoint {
  obec_id: number;
  obec_name: string;
  okres: string | null;
  kraj: string | null;
  n: number;
  avg_per_m2: number;
  metric_value: number;
  metric_year: number | null;
}

export interface ScatterResponse {
  metric: CsuMetricKey;
  metric_label: string;
  metric_unit: string;
  deal: DealType;
  property_types: PropertyType[];
  min_listings: number;
  n_points: number;
  correlation: number | null;
  points: ScatterPoint[];
}

export const DEAL_LABELS: Record<DealType, string> = {
  sale: "Sale",
  rent: "Rent",
  auction: "Auction",
};

export const PROPERTY_LABELS: Record<PropertyType, string> = {
  apartment: "Apartments",
  house: "Houses",
  land: "Land",
  commercial: "Commercial",
};

export const REGION_LEVEL_LABELS: Record<RegionLevel, string> = {
  kraj: "Region",
  okres: "District",
  obec: "Municipality",
  cast_obce: "Locality",
};

export const CSU_METRIC_DEFAULT: CsuMetricKey = "unemployment_pct";

// ---------------------------------------------------------------------------
// Price-changes page — listings sorted by biggest price move in a window.
// ---------------------------------------------------------------------------
export type PriceWindow = "1d" | "3d" | "1w" | "1m";

export const PRICE_WINDOW_LABELS: Record<PriceWindow, string> = {
  "1d": "Last 24 h",
  "3d": "Last 3 days",
  "1w": "Last week",
  "1m": "Last month",
};

export type PriceSort =
  | "abs_desc"       // biggest absolute move first (default)
  | "delta_desc"     // biggest rise first
  | "delta_asc"      // biggest drop first
  | "delta_pct_desc"
  | "delta_pct_asc";

export interface PriceMover {
  property_type: PropertyType;
  hash_id: number;
  title: string;
  sub_category: string | null;
  current_price: number | null;
  old_price: number | null;
  delta: number | null;
  delta_pct: number | null;
  last_changed_at: string | null;
  change_count: number;
  area: number | null;
  per_m2: number | null;
  obec: string | null;
  okres: string | null;
  kraj: string | null;
  url: string | null;
}

export interface PriceMoversResponse {
  window_days: number;
  count: number;
  limit: number;
  offset: number;
  rows: PriceMover[];
}

// ---------------------------------------------------------------------------
// Listing detail — raw row from the active fact table + estate_detail.
// The fact tables have different shapes per property type so we can't type
// every column; the panel renders whatever's present in known buckets.
// ---------------------------------------------------------------------------
export type ListingDetail = Record<string, unknown> & {
  hash_id: number;
  property_type?: string;
  detail_description?: string | null;
  advert_images_count?: number | null;
  has_floor_plan?: boolean | null;
  has_video?: boolean | null;
};
