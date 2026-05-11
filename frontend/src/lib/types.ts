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
