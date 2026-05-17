import { Fragment, useEffect, useState } from "react";
import {
  ChevronDown, ChevronUp, ExternalLink, TrendingDown, TrendingUp,
} from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { FiltersBar } from "@/components/filters-bar";
import { ListingDetailCard } from "@/components/listing-detail-card";
import { api } from "@/lib/api";
import { usePriceChangesStore } from "@/lib/store";
import { area, czk, czkPerM2, num, pct } from "@/lib/format";
import {
  PRICE_WINDOW_LABELS, PROPERTY_LABELS,
  type PriceMover, type PriceSort, type PriceWindow,
} from "@/lib/types";
import { cn } from "@/lib/utils";

/**
 * Price-changes page. Lists the estates whose price has moved the most
 * inside the chosen window, oldest-change-in-window as the reference
 * point. Sort options surface either absolute or signed deltas.
 *
 * The detail dropdown is the same card the listings page uses — opens
 * lazily, fetches every column from the active fact table + the long
 * description text. Image URLs aren't stored anywhere; the detail panel
 * shows the count + a link to sreality.cz where the actual photos live.
 */
const SORT_LABELS: Record<PriceSort, string> = {
  abs_desc:      "Biggest absolute move",
  delta_desc:    "Biggest rise",
  delta_asc:     "Biggest drop",
  delta_pct_desc:"Biggest % rise",
  delta_pct_asc: "Biggest % drop",
};

const WINDOW_ORDER: PriceWindow[] = ["1d", "3d", "1w", "1m"];
const SORT_ORDER: PriceSort[] = [
  "abs_desc", "delta_asc", "delta_desc", "delta_pct_asc", "delta_pct_desc",
];

export function PriceChangesPage() {
  const deal             = usePriceChangesStore((s) => s.deal);
  const setDeal          = usePriceChangesStore((s) => s.setDeal);
  const propertyTypes    = usePriceChangesStore((s) => s.propertyTypes);
  const togglePropertyType = usePriceChangesStore((s) => s.togglePropertyType);
  const windowKey        = usePriceChangesStore((s) => s.window);
  const setWindow        = usePriceChangesStore((s) => s.setWindow);
  const sort             = usePriceChangesStore((s) => s.sort);
  const setSort          = usePriceChangesStore((s) => s.setSort);
  const regionLevel      = usePriceChangesStore((s) => s.regionLevel);
  const regionId         = usePriceChangesStore((s) => s.regionId);

  const [rows, setRows] = useState<PriceMover[]>([]);
  const [loading, setLoading] = useState(false);
  const [windowDays, setWindowDays] = useState<number>(30);

  // Per-row detail dropdown state, same idiom as the listings table.
  const [openRows, setOpenRows] = useState<Set<string>>(new Set());
  function toggleRow(key: string) {
    setOpenRows((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setOpenRows(new Set());
    api.priceMovers(
      deal, propertyTypes, windowKey, sort, regionLevel, regionId, 50, 0,
    )
      .then((r) => {
        if (cancelled) return;
        setRows(r.rows);
        setWindowDays(r.window_days);
      })
      .catch(() => { if (!cancelled) setRows([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [deal, propertyTypes, windowKey, sort, regionLevel, regionId]);

  return (
    <div className="space-y-4">
      {/* Headline + filter bar */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="stat-label">Price changes</div>
          <h1 className="stat-number">
            {num(rows.length)}
            <span className="ml-2 text-base font-normal text-muted-foreground">
              {rows.length === 1 ? "listing" : "listings"} moved in the last {windowDays}d
            </span>
          </h1>
          <p className="mt-1 text-sm text-muted-foreground max-w-prose">
            The reference point is the listing's price at the start of the
            window — taken from the earliest{" "}
            <code className="text-[11px]">estate_field_changes</code> entry
            inside the window. Δ {"="} current price − reference.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <FiltersBar
            deal={deal}
            onDealChange={setDeal}
            propertyTypes={propertyTypes}
            onPropertyToggle={togglePropertyType}
          />

          <Select value={windowKey} onValueChange={(v) => setWindow(v as PriceWindow)}>
            <SelectTrigger className="h-9 w-[150px] rounded-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {WINDOW_ORDER.map((w) => (
                <SelectItem key={w} value={w}>{PRICE_WINDOW_LABELS[w]}</SelectItem>
              ))}
            </SelectContent>
          </Select>

          <Select value={sort} onValueChange={(v) => setSort(v as PriceSort)}>
            <SelectTrigger className="h-9 w-[200px] rounded-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SORT_ORDER.map((s) => (
                <SelectItem key={s} value={s}>{SORT_LABELS[s]}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <Card className="overflow-hidden rounded-2xl border-0 shadow-[0_2px_20px_-12px_rgb(0_0_0_/_0.15)]">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
                <Th />
                <Th>Type</Th>
                <Th>Listing</Th>
                <Th align="right">Price now</Th>
                <Th align="right">Price then</Th>
                <Th align="right">Δ</Th>
                <Th align="right">Δ %</Th>
                <Th align="right">Last change</Th>
                <Th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const rowKey = `${r.property_type}-${r.hash_id}`;
                const isOpen = openRows.has(rowKey);
                const delta = r.delta ?? 0;
                const deltaPositive = delta > 0;
                const deltaZero = delta === 0;
                return (
                  <Fragment key={rowKey}>
                    <tr
                      className="border-b transition-colors last:border-0 hover:bg-muted/40 cursor-pointer"
                      onClick={() => toggleRow(rowKey)}
                    >
                      <Td className="w-8">
                        <span
                          className="inline-grid h-6 w-6 place-items-center rounded-full text-muted-foreground hover:bg-muted"
                          aria-label={isOpen ? "Hide details" : "Show details"}
                        >
                          {isOpen ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
                        </span>
                      </Td>
                      <Td>
                        <Badge variant="secondary" className="rounded-full font-medium">
                          {PROPERTY_LABELS[r.property_type]}
                        </Badge>
                      </Td>
                      <Td>
                        <div className="font-medium">{r.title || "Listing"}</div>
                        <div className="text-xs text-muted-foreground">
                          {[r.obec, r.okres, r.kraj].filter(Boolean).join(" · ") || "—"}
                        </div>
                      </Td>
                      <Td align="right">
                        <span className="font-medium tabular-nums">{czk(r.current_price)}</span>
                      </Td>
                      <Td align="right">
                        <span className="tabular-nums text-muted-foreground">{czk(r.old_price)}</span>
                      </Td>
                      <Td align="right">
                        {/* Drops are green (good for buyers), rises are red.
                            Sale and rent both treated the same way — the
                            user can flip the sort if they're a seller. */}
                        <span
                          className={cn(
                            "inline-flex items-center gap-1 font-medium tabular-nums",
                            deltaZero
                              ? "text-muted-foreground"
                              : deltaPositive
                                ? "text-red-600"
                                : "text-emerald-600",
                          )}
                        >
                          {deltaZero
                            ? "—"
                            : deltaPositive
                              ? <TrendingUp className="h-3.5 w-3.5" />
                              : <TrendingDown className="h-3.5 w-3.5" />}
                          {deltaZero ? "" : `${deltaPositive ? "+" : ""}${czk(r.delta)}`}
                        </span>
                      </Td>
                      <Td align="right">
                        <span
                          className={cn(
                            "tabular-nums",
                            r.delta_pct == null || r.delta_pct === 0
                              ? "text-muted-foreground"
                              : r.delta_pct > 0
                                ? "text-red-600"
                                : "text-emerald-600",
                          )}
                        >
                          {r.delta_pct == null
                            ? "—"
                            : (r.delta_pct > 0 ? "+" : "") + pct(r.delta_pct)}
                        </span>
                      </Td>
                      <Td align="right" className="text-xs text-muted-foreground tabular-nums">
                        {r.last_changed_at ?? "—"}
                      </Td>
                      <Td onClick={(e) => e.stopPropagation()}>
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
                    {isOpen && (
                      <tr className="border-b last:border-0">
                        <td colSpan={9} className="p-0">
                          <ListingDetailCard
                            propertyType={r.property_type}
                            deal={deal}
                            hashId={r.hash_id}
                            srealityUrl={r.url}
                          />
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
              {!loading && rows.length === 0 && (
                <tr>
                  <td colSpan={9} className="p-12 text-center text-muted-foreground">
                    <div className="font-medium">No price changes in this window.</div>
                    <div className="text-xs mt-1">Try a longer window or a different deal type.</div>
                  </td>
                </tr>
              )}
              {loading && rows.length === 0 && (
                <tr>
                  <td colSpan={9} className="p-12 text-center text-muted-foreground">
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

// ----------------------------------------------------------------------------

function Th({ children, align = "left" }: { children?: React.ReactNode; align?: "left" | "right" }) {
  return <th className={cn("px-4 py-3 font-medium", align === "right" && "text-right")}>{children}</th>;
}

function Td({
  children, align = "left", className, onClick,
}: {
  children?: React.ReactNode;
  align?: "left" | "right";
  className?: string;
  onClick?: (e: React.MouseEvent) => void;
}) {
  return (
    <td
      onClick={onClick}
      className={cn("px-4 py-3", align === "right" && "text-right tabular-nums", className)}
    >
      {children}
    </td>
  );
}
