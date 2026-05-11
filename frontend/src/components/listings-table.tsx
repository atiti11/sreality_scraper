import { useEffect, useState } from "react";
import { ChevronLeft, ChevronRight, ExternalLink } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { useListingsStore } from "@/lib/store";
import { area, czk, czkPerM2, num } from "@/lib/format";
import { PROPERTY_LABELS, type Listing, type ListingsFilters } from "@/lib/types";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 50;
const SORT_LABELS: Record<ListingsFilters["sort"], string> = {
  newest:       "Newest first",
  price_asc:    "Price ↑",
  price_desc:   "Price ↓",
  per_m2_asc:   "Price/m² ↑",
  per_m2_desc:  "Price/m² ↓",
  area_desc:    "Largest first",
};

export function ListingsTable() {
  const filters = useListingsStore();
  const [rows, setRows] = useState<Listing[]>([]);
  const [count, setCount] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);

  // Re-fetch when filters change (but not when only sort changes — re-fetch
  // is handled by the dependency below). Reset page to 0 on filter change.
  const filterKey = JSON.stringify({
    deal: filters.deal,
    propertyTypes: filters.propertyTypes,
    regionLevel: filters.regionLevel,
    regionId: filters.regionId,
    priceMin: filters.priceMin,
    priceMax: filters.priceMax,
    perM2Min: filters.perM2Min,
    perM2Max: filters.perM2Max,
    areaMin: filters.areaMin,
    areaMax: filters.areaMax,
    unemploymentMax: filters.unemploymentMax,
  });

  useEffect(() => { setPage(0); }, [filterKey]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      api.listings(filters, PAGE_SIZE, page * PAGE_SIZE),
      page === 0 ? api.listingsCount(filters) : Promise.resolve(null),
    ])
      .then(([list, c]) => {
        if (cancelled) return;
        setRows(list);
        if (c) setCount(c.n);
      })
      .catch(() => { if (!cancelled) setRows([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [filterKey, page, filters.sort]);

  return (
    <div className="space-y-4">
      {/* Headline + sort */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="stat-label">Results</div>
          <h1 className="stat-number">
            {count !== null ? num(count) : "…"}
            <span className="ml-2 text-base font-normal text-muted-foreground">
              {count === 1 ? "listing" : "listings"}
            </span>
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <Select
            value={filters.sort}
            onValueChange={(v) => filters.patch({ sort: v as ListingsFilters["sort"] })}
          >
            <SelectTrigger className="h-9 w-[180px] rounded-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {Object.entries(SORT_LABELS).map(([k, v]) => (
                <SelectItem key={k} value={k}>{v}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <div className="flex items-center gap-1 rounded-full border p-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0 || loading}
              className="h-7 w-7 rounded-full"
              aria-label="Previous page"
            >
              <ChevronLeft />
            </Button>
            <span className="min-w-[2.5rem] text-center text-sm font-medium tabular-nums">
              {page + 1}
            </span>
            <Button
              variant="ghost"
              size="icon"
              onClick={() => setPage((p) => p + 1)}
              disabled={loading || rows.length < PAGE_SIZE}
              className="h-7 w-7 rounded-full"
              aria-label="Next page"
            >
              <ChevronRight />
            </Button>
          </div>
        </div>
      </div>

      <Card className="overflow-hidden rounded-2xl border-0 shadow-[0_2px_20px_-12px_rgb(0_0_0_/_0.15)]">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
                <Th>Type</Th>
                <Th>Listing</Th>
                <Th align="right">Area</Th>
                <Th align="right">Price</Th>
                <Th align="right">Price / m²</Th>
                <Th align="right">Listed</Th>
                <Th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr
                  key={`${r.property_type}-${r.hash_id}`}
                  className="border-b transition-colors last:border-0 hover:bg-muted/40"
                >
                  <Td>
                    <Badge variant="secondary" className="rounded-full font-medium">
                      {PROPERTY_LABELS[r.property_type]}
                    </Badge>
                  </Td>
                  <Td>
                    {/* Synthesised name (e.g. "Apartment 2+1, 65 m²") is the
                        row's primary identifier; the obec / okres / kraj
                        chain sits under it as muted secondary context. */}
                    <div className="font-medium">{r.title || "Listing"}</div>
                    <div className="text-xs text-muted-foreground">
                      {[r.obec, r.okres, r.kraj].filter(Boolean).join(" · ") || "—"}
                    </div>
                  </Td>
                  <Td align="right">{area(r.area)}</Td>
                  <Td align="right">
                    <span className="font-medium tabular-nums">{czk(r.price)}</span>
                  </Td>
                  <Td align="right">
                    <span className="tabular-nums text-primary">{czkPerM2(r.per_m2)}</span>
                  </Td>
                  <Td align="right" className="text-xs text-muted-foreground tabular-nums">
                    {r.first_seen_date ?? "—"}
                  </Td>
                  <Td>
                    {r.url ? (
                      <a
                        href={r.url}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-flex items-center gap-1 text-primary hover:underline"
                      >
                        open <ExternalLink className="h-3 w-3" />
                      </a>
                    ) : (
                      <span className="text-muted-foreground">—</span>
                    )}
                  </Td>
                </tr>
              ))}
              {!loading && rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="p-12 text-center text-muted-foreground">
                    <div className="font-medium">No listings match the current filters.</div>
                    <div className="text-xs mt-1">Try widening price/area ranges or clearing the region.</div>
                  </td>
                </tr>
              )}
              {loading && rows.length === 0 && (
                <tr>
                  <td colSpan={7} className="p-12 text-center text-muted-foreground">
                    Loading…
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>
    </div>
  );
}

function Th({ children, align = "left" }: { children?: React.ReactNode; align?: "left" | "right" }) {
  return <th className={cn("px-4 py-3 font-medium", align === "right" && "text-right")}>{children}</th>;
}

function Td({
  children, align = "left", className,
}: { children?: React.ReactNode; align?: "left" | "right"; className?: string }) {
  return <td className={cn("px-4 py-3", align === "right" && "text-right tabular-nums", className)}>{children}</td>;
}
