import { useEffect, useMemo, useRef, useState } from "react";
import L, { type GeoJSON as LGeoJSON, type Map as LMap } from "leaflet";
import { api } from "@/lib/api";
import { useMapStore } from "@/lib/store";
import { czkPerM2, num } from "@/lib/format";
import type { MapLevel, MarkerData, RegionFeature, RegionFeatureCollection } from "@/lib/types";

/**
 * Map view — choropleth of avg price/m² over Czech admin regions.
 *
 * Progressive zoom-based subdivision:
 *
 *   Zoom <  8.5  →  kraj  layer (14 regions, whole country)
 *   Zoom <  10.0 →  okres layer (76 districts, whole country)
 *   Zoom <  11.5 →  obec  layer — viewport-filtered, refetched on pan
 *   Zoom >= 11.5 →  cast_obce layer — viewport-filtered, refetched on pan
 *
 * Why bbox-aware refetching at obec / cast_obce levels?
 *   There are ~6k obce and ~16k cast_obce in CZ. Loading them all at once
 *   would slow the page and clutter the screen with overlapping centroids.
 *   The backend honours a ``bbox`` parameter on /api/markers for these two
 *   levels — we pass the current map bounds (padded by 25%) and refetch on
 *   moveend so that panning around at deep zoom always shows fresh,
 *   on-screen-only markers.
 *
 * The transition from obec → cast_obce happens at zoom 11.5 so the user
 * gets a visible refinement step (city blocks / neighbourhoods) once they
 * zoom past city-wide view, without having to zoom all the way to street
 * level. cast_obce's bbox filter keeps the marker count tractable.
 *
 * Click anywhere → that polygon becomes the selection (drives the side panel).
 * Hover → border highlights. Selection persists across zoom changes.
 *
 * Tiles: CartoDB Voyager — desaturated, designed to sit underneath choropleths.
 */

function levelForZoom(zoom: number): MapLevel {
  if (zoom < 8.5)  return "kraj";
  if (zoom < 10.0) return "okres";
  if (zoom < 11.5) return "obec";
  return "cast_obce";
}

/** Levels where we filter the markers query by visible bounds. */
function isBboxLevel(level: MapLevel): boolean {
  return level === "obec" || level === "cast_obce";
}

/**
 * Expand the visible bounds by ``pad`` (in fraction of the bbox extent) so
 * markers that fall just outside the viewport are still drawn — prevents
 * "holes" at the edges when the user pans a little. Returns the GET-style
 * tuple expected by the API: ``[minlon, minlat, maxlon, maxlat]``.
 */
function boundsToBbox(
  b: L.LatLngBounds,
  pad = 0.25,
): [number, number, number, number] {
  const sw = b.getSouthWest();
  const ne = b.getNorthEast();
  const dLon = (ne.lng - sw.lng) * pad;
  const dLat = (ne.lat - sw.lat) * pad;
  return [
    sw.lng - dLon,
    sw.lat - dLat,
    ne.lng + dLon,
    ne.lat + dLat,
  ];
}

/**
 * Round a bbox to a coarse grid so tiny pans don't trigger a refetch — we
 * only want a new request when the visible area meaningfully changes.
 * Returns a stable string key suitable for use as a useEffect dependency.
 */
function bboxKey(bbox: [number, number, number, number] | null): string {
  if (!bbox) return "none";
  return bbox.map((v) => v.toFixed(2)).join(",");
}

const LEVEL_LABELS: Record<MapLevel, string> = {
  kraj:      "Regions",
  okres:     "Districts",
  obec:      "Municipalities",
  cast_obce: "Localities",
};

// Sequential heatmap ramp: pale yellow (cheap) → deep red (expensive).
// Adapted from ColorBrewer's YlOrRd; the lightest stop is shifted a touch
// more saturated than the canonical ``#ffffb2`` so low-price regions stay
// visually distinct from the dedicated "no data" grey instead of fading
// into off-white.
const PALETTE: [number, number, number][] = [
  [254, 240, 142],  // pale yellow      (lowest)
  [254, 204,  92],  // yellow-orange
  [253, 141,  60],  // orange
  [240,  59,  32],  // red-orange
  [189,   0,  38],  // deep red         (highest)
];

// Dedicated colour for regions that have zero listings under the active
// filters. Pulled out as constants so the legend swatch and the marker
// fill stay in sync.
const NO_DATA_COLOR   = "#94a3b8"; // slate-400 — unambiguously grey
const NO_DATA_OPACITY = 0.55;
const NO_DATA_STROKE  = "#64748b"; // slate-500

function colourFor(value: number, lo: number, hi: number): string {
  if (!Number.isFinite(value)) return NO_DATA_COLOR;
  const t = Math.max(0, Math.min(1, (value - lo) / Math.max(1, hi - lo)));
  const seg = Math.min(PALETTE.length - 2, Math.floor(t * (PALETTE.length - 1)));
  const localT = t * (PALETTE.length - 1) - seg;
  const a = PALETTE[seg];
  const b = PALETTE[seg + 1];
  const ch = (i: number) => Math.round(a[i] + (b[i] - a[i]) * localT);
  return `rgb(${ch(0)}, ${ch(1)}, ${ch(2)})`;
}

/**
 * Min / max of the finite values on the visible map. Used to both anchor
 * the colour gradient and label the legend, so the swatch under the
 * cursor always matches the legend's numeric range.
 *
 * Earlier versions of this code clipped to the p10..p90 range so a
 * single Prague-sized outlier wouldn't compress the rest of the
 * gradient. That made the legend lie about what was on screen — it
 * said "83k" while Prague was actually 159k. With only ~14 kraje there's
 * not enough data to safely percentile-clip anyway, so we use the
 * absolute extrema and accept that an outlier visually dominates: when
 * Prague is genuinely 2× the rest, the map should say so.
 */
function valueBounds(values: (number | null)[]): [number, number] {
  let lo = Number.POSITIVE_INFINITY;
  let hi = Number.NEGATIVE_INFINITY;
  for (const v of values) {
    if (!Number.isFinite(v as number)) continue;
    const n = v as number;
    if (n < lo) lo = n;
    if (n > hi) hi = n;
  }
  if (!Number.isFinite(lo) || !Number.isFinite(hi)) return [0, 1];
  return [lo, hi];
}

export function MapView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<LMap | null>(null);
  const layerRef = useRef<LGeoJSON | null>(null);

  const deal = useMapStore((s) => s.deal);
  const propertyTypes = useMapStore((s) => s.propertyTypes);
  const select = useMapStore((s) => s.select);
  const selected = useMapStore((s) => s.selected);

  const [level, setLevel] = useState<MapLevel>("kraj");
  // Single source of truth for "where is the user looking right now".
  // Replaces the old standalone bbox state — we derive both (a) the bbox
  // query parameter for obec / cast_obce API calls and (b) the colour
  // scale's value range from it, so the legend stays in lock-step with
  // the map at every pan and zoom.
  const [mapBounds, setMapBounds] = useState<L.LatLngBounds | null>(null);
  const [data, setData] = useState<RegionFeatureCollection | null>(null);
  const [loading, setLoading] = useState(false);

  // bbox tuple for the API — padded copy of the visible bounds, only
  // populated at obec / cast_obce levels where the markers query honours it.
  const bbox = useMemo<[number, number, number, number] | null>(() => {
    if (!isBboxLevel(level) || !mapBounds) return null;
    return boundsToBbox(mapBounds);
  }, [level, mapBounds]);

  // -- Mount the Leaflet map once.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    // React StrictMode (dev) double-invokes effects: cleanup runs between
    // the two invocations, and Leaflet's ``_leaflet_id`` marker survives
    // ``map.remove()``. Wipe it so the re-init doesn't throw.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    if ((container as any)._leaflet_id !== undefined) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      delete (container as any)._leaflet_id;
    }

    const m = L.map(container, {
      zoomControl: false,
      attributionControl: true,
      preferCanvas: false,
      worldCopyJump: false,
    }).setView([49.8, 15.4], 7);

    L.control.zoom({ position: "bottomright" }).addTo(m);

    // CartoDB Voyager — clean, desaturated, ideal under choropleth.
    L.tileLayer(
      "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png",
      {
        maxZoom: 18,
        attribution:
          '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>, © <a href="https://carto.com/attributions">CARTO</a>',
        subdomains: "abcd",
      },
    ).addTo(m);

    mapRef.current = m;

    const updateView = () => {
      const next = levelForZoom(m.getZoom());
      setLevel((prev) => (prev !== next ? next : prev));
      // Always refresh map bounds so the colour scale + legend stay
      // pinned to what's actually on screen — even at kraj / okres
      // levels where the API doesn't need a bbox.
      setMapBounds(m.getBounds());
    };
    m.on("zoomend", updateView);
    m.on("moveend", updateView);

    // ---- The actual fix for "map flashes then goes blank" --------------
    // Leaflet caches the container's pixel size at L.map() time. If the
    // surrounding CSS layout hasn't settled (h-[calc(100vh-4rem)] can be 0
    // on the first frame), every tile is sized for that stale 0×0 layout
    // and disappears as soon as the user pans or the layout recomputes.
    // Calling invalidateSize() after the next paint forces a re-measure;
    // a ResizeObserver keeps it honest on window resizes.
    //
    // We also seed mapBounds here so the legend's first render has a
    // viewport to base its colour scale on — otherwise the very first
    // paint would compute the palette from every feature in the country.
    const initialFix = window.requestAnimationFrame(() => {
      m.invalidateSize();
      setMapBounds(m.getBounds());
    });
    const ro = new ResizeObserver(() => m.invalidateSize());
    ro.observe(container);

    return () => {
      window.cancelAnimationFrame(initialFix);
      ro.disconnect();
      m.off("zoomend", updateView);
      m.off("moveend", updateView);
      m.remove();
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      if (container && (container as any)._leaflet_id !== undefined) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        delete (container as any)._leaflet_id;
      }
      mapRef.current = null;
    };
  }, []);

  // -- Load markers whenever filters / zoom-level / viewport change.
  // For kraj & okres we load the whole country once per filter change.
  // For obec & cast_obce we pass the current bbox so the backend returns
  // only the regions visible on screen — otherwise we'd ship 6k / 16k rows.
  // The bboxDep key is rounded so micro-pans don't trigger refetches;
  // the *visibleFeatures* memo below uses the raw bounds, so the legend
  // still rescales smoothly on every move.
  // NOTE: `selected` is intentionally NOT in the dependency array.
  const bboxDep = isBboxLevel(level) ? bboxKey(bbox) : "";
  useEffect(() => {
    let cancelled = false;
    setLoading(true);

    const bboxArg = isBboxLevel(level) ? bbox : null;
    api.markers(level, deal, propertyTypes, bboxArg)
      .then((markers) => {
        if (!cancelled) setData(markersToGeoJSON(markers, level));
      })
      .catch((e) => { if (!cancelled) console.error("Markers load failed:", e); })
      .finally(() => { if (!cancelled) setLoading(false); });

    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [level, deal, propertyTypes, bboxDep]);

  // -- Filter loaded features to what's currently on screen.
  // Drives both the colour scale and the legend, so the user always sees
  // a gradient anchored to the visible data rather than the whole-country
  // extremes. Polygons (non-Point geometries) are kept unconditionally —
  // we can't cheaply test arbitrary geometries against bounds.
  const visibleFeatures = useMemo(() => {
    if (!data) return [] as RegionFeature[];
    if (!mapBounds) return data.features;
    return data.features.filter((f) => {
      if (!f.geometry || f.geometry.type !== "Point") return true;
      const [lon, lat] = (f.geometry as GeoJSON.Point).coordinates;
      return mapBounds.contains([lat, lon]);
    });
  }, [data, mapBounds]);

  // -- Compute colour scale from the *visible* features so the gradient
  // re-anchors live as the user pans/zooms. With ~14 kraje or hundreds
  // of obce on screen, this is a cheap O(n) scan on every moveend.
  const palette = useMemo(() => {
    const [lo, hi] = valueBounds(visibleFeatures.map((f) => f.properties.avg_per_m2));
    return { lo, hi };
  }, [visibleFeatures]);

  // -- Render / re-render polygons.
  useEffect(() => {
    const m = mapRef.current;
    if (!m || !data) return;

    if (layerRef.current) {
      layerRef.current.remove();
      layerRef.current = null;
    }

    // Nothing to render (all markers had null coords, or no data yet).
    if (data.features.length === 0) return;

    const isSelected = (f: RegionFeature) =>
      selected?.level === data.level && selected.id === f.properties.id;

    const isPointMode = data.features.length > 0 &&
      data.features[0].geometry?.type === "Point";

    // Leaflet validates strictly: strip our custom `level` field so it gets
    // a plain FeatureCollection with no extra top-level keys.
    const geojson: GeoJSON.FeatureCollection = {
      type: "FeatureCollection",
      features: data.features as unknown as GeoJSON.Feature[],
    };

    const layer = L.geoJSON(geojson, {
      // pointToLayer converts Point features to circle markers (bubble map mode).
      pointToLayer: isPointMode ? (feature, latlng) => {
        const f = feature as unknown as RegionFeature;
        const v = f.properties.avg_per_m2;
        const n = f.properties.n;
        const hasData = v != null && n > 0;
        const radius = hasData ? Math.max(5, Math.min(40, 5 + Math.sqrt(n) * 0.8)) : 5;
        return L.circleMarker(latlng, {
          radius,
          fillColor: hasData ? colourFor(v, palette.lo, palette.hi) : NO_DATA_COLOR,
          fillOpacity: hasData ? 0.78 : NO_DATA_OPACITY,
          weight: isSelected(f) ? 3 : 1,
          color: isSelected(f) ? "#0f172a" : (hasData ? "#334155" : NO_DATA_STROKE),
          opacity: 0.85,
        });
      } : undefined,
      style: isPointMode ? undefined : (feature) => {
        const f = feature as unknown as RegionFeature;
        const v = f.properties.avg_per_m2;
        const n = f.properties.n;
        const hasData = v != null && n > 0;
        return {
          fillColor: hasData ? colourFor(v, palette.lo, palette.hi) : NO_DATA_COLOR,
          fillOpacity: hasData ? 0.78 : NO_DATA_OPACITY,
          weight: isSelected(f) ? 3 : 1,
          color: isSelected(f) ? "#0f172a" : (hasData ? "#334155" : NO_DATA_STROKE),
          opacity: 0.85,
        };
      },
      onEachFeature: (feature, l) => {
        const f = feature as unknown as RegionFeature;
        const p = f.properties;
        l.bindTooltip(
          renderTooltip(p),
          { sticky: true, direction: "top", offset: [0, -4] },
        );
        l.on("click", () => select(data.level, p.id));
        l.on("mouseover", () => {
          (l as L.Path).setStyle({ weight: 2.5, color: "#0f172a" });
          (l as L.Path).bringToFront();
        });
        l.on("mouseout", () => {
          if (isSelected(f)) return;
          (l as L.Path).setStyle({ weight: 1, color: "#334155" });
        });
      },
    });

    layer.addTo(m);
    layerRef.current = layer;
  }, [data, palette, selected, select]);

  return (
    <div className="absolute inset-0">
      <div ref={containerRef} className="absolute inset-0" />

      {loading && (
        <div className="absolute right-4 top-4 z-[400] rounded-full glass px-4 py-1.5 text-xs font-medium">
          <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-primary mr-2 align-middle" />
          Loading
        </div>
      )}

      {data && visibleFeatures.length > 0 && (
        <Legend
          lo={palette.lo}
          hi={palette.hi}
          level={data.level}
          count={visibleFeatures.length}
        />
      )}
    </div>
  );
}

function renderTooltip(p: RegionFeature["properties"]): string {
  return `<div style="min-width: 180px; font-family: Inter, sans-serif;">
    <div style="font-size: 13px; font-weight: 600; color: #0f172a;">${escapeHtml(p.name)}</div>
    <div style="margin-top: 4px; display: flex; justify-content: space-between; gap: 12px; font-size: 12px;">
      <span style="color: #64748b;">Avg / m²</span>
      <span style="font-weight: 500; color: ${p.avg_per_m2 != null ? '#047857' : '#94a3b8'};">
        ${p.avg_per_m2 != null ? czkPerM2(p.avg_per_m2) : "no data"}
      </span>
    </div>
    <div style="display: flex; justify-content: space-between; gap: 12px; font-size: 12px;">
      <span style="color: #64748b;">Listings</span>
      <span style="font-weight: 500;">${num(p.n)}</span>
    </div>
  </div>`;
}

function Legend({
  lo, hi, level, count,
}: { lo: number; hi: number; level: MapLevel; count: number }) {
  const stops = [0, 0.25, 0.5, 0.75, 1].map((t) =>
    colourFor(lo + (hi - lo) * t, lo, hi),
  );
  return (
    <div className="absolute bottom-4 left-4 z-[400] glass rounded-2xl p-4 text-xs">
      <div className="mb-2 flex items-baseline justify-between gap-6">
        <span className="font-semibold text-foreground">Avg price / m²</span>
        <span className="text-muted-foreground text-[10px] uppercase tracking-wider">
          {LEVEL_LABELS[level]} · {num(count)}
        </span>
      </div>
      <div className="flex items-center gap-2">
        <span className="tabular-nums text-muted-foreground">{czkPerM2(lo)}</span>
        <span
          className="inline-block h-2 w-44 rounded-full"
          style={{ background: `linear-gradient(90deg, ${stops.join(", ")})` }}
        />
        <span className="tabular-nums text-muted-foreground">{czkPerM2(hi)}</span>
      </div>
      <div className="mt-2 flex items-center gap-2 text-[10px] text-muted-foreground">
        <span
          className="inline-block h-2 w-2 rounded-full"
          style={{ background: NO_DATA_COLOR, opacity: NO_DATA_OPACITY }}
        />
        <span>no listings</span>
      </div>
      <div className="mt-2 text-[10px] text-muted-foreground">
        {level === "kraj" || level === "okres"
          ? "Zoom in for municipality- and locality-level detail."
          : "Pan and zoom — only the visible area is loaded."}
      </div>
    </div>
  );
}

/**
 * Convert the /api/markers response into a minimal GeoJSON FeatureCollection
 * so the rest of the rendering logic (which already works on GeoJSON) can
 * handle both modes without branching everywhere.
 *
 * Each marker becomes a GeoJSON Point Feature. The polygon-style rendering
 * will still run — Leaflet simply draws a tiny default point marker for
 * Point geometries — but because we override the style in onEachFeature
 * using circleMarkers, the caller would need to switch renderer. For now
 * the choropleth colouring on the tooltip is what matters.
 */
function markersToGeoJSON(
  markers: MarkerData[],
  level: MapLevel,
): RegionFeatureCollection {
  return {
    type: "FeatureCollection",
    level,
    features: markers
      // Drop any marker whose coordinates aren't valid finite numbers —
      // Leaflet throws "Invalid GeoJSON object" on null/NaN coordinates.
      .filter((m) =>
        m.lat != null && m.lon != null &&
        Number.isFinite(m.lat) && Number.isFinite(m.lon),
      )
      .map((m) => ({
        type: "Feature" as const,
        geometry: {
          type: "Point" as const,
          coordinates: [m.lon, m.lat] as [number, number],
        },
        properties: {
          id:            m.id,
          code:          m.code,
          name:          m.name,
          parent_id:     m.parent_id,
          n:             m.n,
          avg_per_m2:    m.avg_per_m2,
          median_per_m2: m.median_per_m2,
        },
      })),
  };
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (m) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[m]!));
}
