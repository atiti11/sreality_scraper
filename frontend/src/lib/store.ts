import { create } from "zustand";
import type {
  CsuMetricKey, Filters, ListingsFilters, PriceSort, PriceWindow,
  PropertyType, RegionLevel,
} from "./types";
import { CSU_METRIC_DEFAULT } from "./types";

/**
 * Two stores — Map view filters and Listings view filters — kept separate
 * so e.g. tweaking the price slider in Listings doesn't cause the
 * choropleth to recompute. The "open in listings" button on the map
 * side-panel hands its current filters to Listings via `applyFromMap`.
 */
interface MapState extends Filters {
  setDeal: (d: Filters["deal"]) => void;
  togglePropertyType: (t: PropertyType) => void;
  setPropertyTypes: (ts: PropertyType[]) => void;

  // Click selection (level + id of the polygon under inspection)
  selected: { level: RegionLevel; id: number } | null;
  select: (level: RegionLevel, id: number) => void;
  clearSelection: () => void;
}

const initialFilters: Filters = {
  deal: "sale",
  propertyTypes: ["apartment", "house"],
};

export const useMapStore = create<MapState>((set) => ({
  ...initialFilters,
  setDeal: (d) => set({ deal: d }),
  togglePropertyType: (t) =>
    set((s) => ({
      propertyTypes: s.propertyTypes.includes(t)
        ? s.propertyTypes.filter((x) => x !== t)
        : [...s.propertyTypes, t],
    })),
  setPropertyTypes: (ts) => set({ propertyTypes: ts }),

  selected: null,
  select: (level, id) => set({ selected: { level, id } }),
  clearSelection: () => set({ selected: null }),
}));

interface ListingsState extends ListingsFilters {
  setDeal: (d: ListingsFilters["deal"]) => void;
  togglePropertyType: (t: PropertyType) => void;
  setPropertyTypes: (ts: PropertyType[]) => void;
  setRegion: (level: RegionLevel | null, id: number | null) => void;
  patch: (p: Partial<ListingsFilters>) => void;
  reset: () => void;

  // Cross-tab handoff: the map-side "Open in listings" button calls this.
  applyFromMap: (m: {
    deal: ListingsFilters["deal"];
    propertyTypes: PropertyType[];
    regionLevel: RegionLevel | null;
    regionId: number | null;
  }) => void;
}

const initialListings: ListingsFilters = {
  deal: "sale",
  propertyTypes: ["apartment", "house"],
  regionLevel: null,
  regionId: null,
  priceMin: null,
  priceMax: null,
  perM2Min: null,
  perM2Max: null,
  areaMin: null,
  areaMax: null,
  unemploymentMax: null,
  sort: "newest",
};

export const useListingsStore = create<ListingsState>((set) => ({
  ...initialListings,
  setDeal: (d) => set({ deal: d }),
  togglePropertyType: (t) =>
    set((s) => ({
      propertyTypes: s.propertyTypes.includes(t)
        ? s.propertyTypes.filter((x) => x !== t)
        : [...s.propertyTypes, t],
    })),
  setPropertyTypes: (ts) => set({ propertyTypes: ts }),
  setRegion: (level, id) => set({ regionLevel: level, regionId: id }),
  patch: (p) => set(p),
  reset: () => set(initialListings),
  applyFromMap: ({ deal, propertyTypes, regionLevel, regionId }) =>
    set({
      ...initialListings,
      deal,
      propertyTypes,
      regionLevel,
      regionId,
    }),
}));

// ---------------------------------------------------------------------------
// Correlation page — scatter of avg price / m² vs CSU metric.
// Has its own store so that picking a metric on this tab doesn't perturb
// the map view's filter state and vice versa.
// ---------------------------------------------------------------------------
interface CorrelationState extends Filters {
  metric: CsuMetricKey;
  minListings: number;
  setDeal: (d: Filters["deal"]) => void;
  setPropertyTypes: (ts: PropertyType[]) => void;
  togglePropertyType: (t: PropertyType) => void;
  setMetric: (m: CsuMetricKey) => void;
  setMinListings: (n: number) => void;
}

export const useCorrelationStore = create<CorrelationState>((set) => ({
  deal: "sale",
  // Apartments are the densest category and behave most like "prices" in
  // common parlance, so default the scatter to them.
  propertyTypes: ["apartment"],
  metric: CSU_METRIC_DEFAULT,
  minListings: 5,
  setDeal: (d) => set({ deal: d }),
  setPropertyTypes: (ts) => set({ propertyTypes: ts }),
  togglePropertyType: (t) =>
    set((s) => ({
      propertyTypes: s.propertyTypes.includes(t)
        ? s.propertyTypes.filter((x) => x !== t)
        : [...s.propertyTypes, t],
    })),
  setMetric: (m) => set({ metric: m }),
  setMinListings: (n) => set({ minListings: n }),
}));

// ---------------------------------------------------------------------------
// Price-changes page — listings sorted by biggest price move in a window.
// Lives in its own store so the user's settings persist when they jump
// between tabs.
// ---------------------------------------------------------------------------
interface PriceChangesState extends Filters {
  window:      PriceWindow;
  sort:        PriceSort;
  regionLevel: RegionLevel | null;
  regionId:    number | null;
  setDeal:           (d: Filters["deal"]) => void;
  setPropertyTypes:  (ts: PropertyType[]) => void;
  togglePropertyType:(t: PropertyType)    => void;
  setWindow:         (w: PriceWindow)     => void;
  setSort:           (s: PriceSort)       => void;
  setRegion:         (level: RegionLevel | null, id: number | null) => void;
}

export const usePriceChangesStore = create<PriceChangesState>((set) => ({
  deal: "sale",
  propertyTypes: ["apartment", "house"],
  window: "1m",
  sort:   "abs_desc",
  regionLevel: null,
  regionId:    null,
  setDeal: (d) => set({ deal: d }),
  setPropertyTypes: (ts) => set({ propertyTypes: ts }),
  togglePropertyType: (t) =>
    set((s) => ({
      propertyTypes: s.propertyTypes.includes(t)
        ? s.propertyTypes.filter((x) => x !== t)
        : [...s.propertyTypes, t],
    })),
  setWindow: (w) => set({ window: w }),
  setSort:   (s) => set({ sort: s }),
  setRegion: (level, id) => set({ regionLevel: level, regionId: id }),
}));

interface AppState {
  tab: "map" | "listings" | "correlation" | "price-changes";
  setTab: (t: "map" | "listings" | "correlation" | "price-changes") => void;
}

export const useAppStore = create<AppState>((set) => ({
  tab: "map",
  setTab: (t) => set({ tab: t }),
}));
