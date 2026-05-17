import { useEffect, useState } from "react";
import { ExternalLink, ImageIcon } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { api } from "@/lib/api";
import { area, czk, czkPerM2, num } from "@/lib/format";
import type { DealType, ListingDetail, PropertyType } from "@/lib/types";

/**
 * Detail panel rendered in the expanded row of the listings (or price-
 * changes) table. Fetches /api/listing/.../detail lazily — only when the
 * row is actually opened — and caches the result for the lifetime of the
 * page so toggling open/close doesn't re-hit the backend.
 *
 * The shape of the detail varies by property type (apartments expose
 * has_balcony, houses expose garden_area_m2, …), so we render whatever
 * the backend sends back in a few thematic buckets:
 *   - description text (long-form text from estate_detail)
 *   - core specs (price, area, per-m², floor, year built …)
 *   - feature flags (boolean has_* columns)
 *   - metadata (media counts, URL, hash id)
 *
 * Pictures: the warehouse only stores ``advert_images_count`` — the
 * image URLs themselves are not scraped. We show the count + an
 * "open on sreality" link so the user can see them in their original
 * context.
 */
export function ListingDetailCard({
  propertyType, deal, hashId, srealityUrl,
}: {
  propertyType: PropertyType;
  deal: DealType;
  hashId: number;
  srealityUrl: string | null;
}) {
  const [detail, setDetail] = useState<ListingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api.listingDetail(propertyType, deal, hashId)
      .then((d) => { if (!cancelled) setDetail(d); })
      .catch((e: Error) => { if (!cancelled) setError(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [propertyType, deal, hashId]);

  if (loading) {
    return <div className="px-4 py-4 text-xs text-muted-foreground">Loading details…</div>;
  }
  if (error) {
    return <div className="px-4 py-4 text-xs text-destructive">Couldn't load details: {error}</div>;
  }
  if (!detail) {
    return <div className="px-4 py-4 text-xs text-muted-foreground">No details available.</div>;
  }

  // Pull out fields we know we want to highlight; everything else lands
  // in the "Other fields" grid below.
  const description = (detail.detail_description as string | null) ?? null;
  const imageCount = (detail.advert_images_count as number | null) ?? null;
  const hasFloorPlan = detail.has_floor_plan === true;
  const hasVideo = detail.has_video === true;

  // Group the well-known columns into thematic buckets for readability.
  const specs   = pickGrid(detail, SPECS_KEYS);
  const flags   = pickFlags(detail, FLAGS_KEYS);
  const meta    = pickGrid(detail, META_KEYS);

  return (
    <div className="bg-muted/30 px-5 py-4 space-y-4">
      {/* Description */}
      {description && (
        <Section title="Description">
          <p className="text-sm leading-relaxed whitespace-pre-line line-clamp-[10]">
            {description}
          </p>
        </Section>
      )}

      {/* Specs grid */}
      {specs.length > 0 && (
        <Section title="Specs">
          <dl className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-x-6 gap-y-2 text-sm">
            {specs.map(({ key, label, value }) => (
              <div key={key}>
                <dt className="text-[10px] uppercase tracking-wider text-muted-foreground">
                  {label}
                </dt>
                <dd className="font-medium tabular-nums">{value}</dd>
              </div>
            ))}
          </dl>
        </Section>
      )}

      {/* Feature flags */}
      {flags.length > 0 && (
        <Section title="Features">
          <div className="flex flex-wrap gap-1.5">
            {flags.map((f) => (
              <Badge key={f.key} variant="secondary" className="rounded-full">
                {f.label}
              </Badge>
            ))}
          </div>
        </Section>
      )}

      {/* Media + meta */}
      <Section title="Media & links">
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-muted px-3 py-1 text-xs">
            <ImageIcon className="h-3.5 w-3.5" />
            {imageCount != null
              ? `${num(imageCount)} ${imageCount === 1 ? "image" : "images"} on sreality`
              : "No image count"}
          </span>
          {hasFloorPlan && (
            <Badge variant="secondary" className="rounded-full">Floor plan</Badge>
          )}
          {hasVideo && (
            <Badge variant="secondary" className="rounded-full">Video</Badge>
          )}
          {srealityUrl && (
            <a
              href={srealityUrl}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1 text-primary text-xs hover:underline"
            >
              View on sreality.cz <ExternalLink className="h-3 w-3" />
            </a>
          )}
        </div>
        <p className="mt-2 text-[10px] text-muted-foreground">
          The warehouse stores only the image count; original images live
          on sreality.cz. Click the link to see them in context.
        </p>
      </Section>

      {/* Quiet meta */}
      {meta.length > 0 && (
        <Section title="Source">
          <dl className="grid grid-cols-2 sm:grid-cols-4 gap-x-6 gap-y-1 text-xs text-muted-foreground">
            {meta.map(({ key, label, value }) => (
              <div key={key}>
                <dt className="uppercase tracking-wider text-[10px]">{label}</dt>
                <dd className="text-foreground tabular-nums">{value}</dd>
              </div>
            ))}
          </dl>
        </Section>
      )}
    </div>
  );
}

// ----------------------------------------------------------------------------

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <h4 className="text-[10px] font-semibold uppercase tracking-wider text-muted-foreground mb-1.5">
        {title}
      </h4>
      {children}
    </div>
  );
}

// ----- Whitelisted columns + how to display each ---------------------------
//
// The fact tables have different column sets but a lot of overlap. We
// curate two whitelists (specs + flags) so the dropdown shows the
// useful columns in a stable order and ignores internal plumbing
// (content_hash, valid_from, …). Anything not in either list is hidden.

interface FieldSpec { key: string; label: string; fmt: "number" | "area" | "czk" | "czkPerM2" | "date" | "string"; }

const SPECS_KEYS: FieldSpec[] = [
  { key: "sub_category",             label: "Disposition",         fmt: "string" },
  { key: "usable_area_m2",           label: "Usable area",         fmt: "area" },
  { key: "plot_area_m2",             label: "Plot area",           fmt: "area" },
  { key: "garden_area_m2",           label: "Garden area",         fmt: "area" },
  { key: "floor_number",             label: "Floor",               fmt: "number" },
  { key: "total_floors",             label: "Floors total",        fmt: "number" },
  { key: "price_asked_czk",          label: "Asking price",        fmt: "czk" },
  { key: "price_monthly_czk",        label: "Monthly price",       fmt: "czk" },
  { key: "price_starting_bid_czk",   label: "Starting bid",        fmt: "czk" },
  { key: "price_asked_per_m2",       label: "Asked / m²",          fmt: "czkPerM2" },
  { key: "price_monthly_per_m2",     label: "Monthly / m²",        fmt: "czkPerM2" },
  { key: "ownership_label",          label: "Ownership",           fmt: "string" },
  { key: "building_type_label",      label: "Building type",       fmt: "string" },
  { key: "building_condition_label", label: "Condition",           fmt: "string" },
  { key: "energy_rating_label",      label: "Energy rating",       fmt: "string" },
  { key: "obec",                     label: "Municipality",        fmt: "string" },
  { key: "cast_obce",                label: "Locality",            fmt: "string" },
  { key: "okres",                    label: "District",            fmt: "string" },
  { key: "kraj",                     label: "Region",              fmt: "string" },
];

const FLAGS_KEYS: { key: string; label: string }[] = [
  { key: "is_new_building",   label: "New building" },
  { key: "is_low_energy",     label: "Low energy" },
  { key: "is_furnished",      label: "Furnished" },
  { key: "is_barrier_free",   label: "Barrier-free" },
  { key: "has_balcony",       label: "Balcony" },
  { key: "has_terrace",       label: "Terrace" },
  { key: "has_loggia",        label: "Loggia" },
  { key: "has_cellar",        label: "Cellar" },
  { key: "has_elevator",      label: "Elevator" },
  { key: "has_parking",       label: "Parking" },
  { key: "has_garage",        label: "Garage" },
  { key: "has_pool",          label: "Pool" },
];

const META_KEYS: FieldSpec[] = [
  { key: "hash_id",         label: "Hash id",     fmt: "number" },
  { key: "first_seen_date", label: "First seen",  fmt: "date" },
  { key: "valid_from",      label: "Valid from",  fmt: "date" },
];

// ----------------------------------------------------------------------------

function pickGrid(detail: ListingDetail, keys: FieldSpec[]):
  Array<{ key: string; label: string; value: string }>
{
  const out: Array<{ key: string; label: string; value: string }> = [];
  for (const k of keys) {
    const raw = detail[k.key];
    if (raw == null || raw === "") continue;
    out.push({ key: k.key, label: k.label, value: format(raw, k.fmt) });
  }
  return out;
}

function pickFlags(detail: ListingDetail, keys: { key: string; label: string }[]):
  Array<{ key: string; label: string }>
{
  return keys.filter((k) => detail[k.key] === true);
}

function format(raw: unknown, fmt: FieldSpec["fmt"]): string {
  switch (fmt) {
    case "number":   return num(raw as number);
    case "area":     return area(raw as number);
    case "czk":      return czk(raw as number);
    case "czkPerM2": return czkPerM2(raw as number);
    case "date":     return typeof raw === "string" ? raw : "—";
    case "string":   return raw == null ? "—" : String(raw);
  }
}
