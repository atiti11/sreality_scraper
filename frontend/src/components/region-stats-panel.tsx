import { useEffect, useState } from "react";
import { ArrowRight, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Separator } from "@/components/ui/separator";
import { api } from "@/lib/api";
import { useAppStore, useListingsStore, useMapStore } from "@/lib/store";
import { czk, czkPerM2, num, pct } from "@/lib/format";
import { REGION_LEVEL_LABELS, type RegionStats } from "@/lib/types";
import { cn } from "@/lib/utils";

/**
 * Right-hand floating glass card. Materialises when the user clicks
 * a polygon on the map. Shows region context, price stats, and CSU
 * socio-economics; ends with an "Open in Listings" CTA that hands the
 * current filter context off to the second tab.
 */
export function RegionStatsPanel() {
  const selected = useMapStore((s) => s.selected);
  const clearSelection = useMapStore((s) => s.clearSelection);
  const deal = useMapStore((s) => s.deal);
  const propertyTypes = useMapStore((s) => s.propertyTypes);
  const applyFromMap = useListingsStore((s) => s.applyFromMap);
  const setTab = useAppStore((s) => s.setTab);

  const [stats, setStats] = useState<RegionStats | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!selected) {
      setStats(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    api.regionStats(selected.level, selected.id, deal, propertyTypes)
      .then((s) => { if (!cancelled) setStats(s); })
      .catch(() => { if (!cancelled) setStats(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [selected, deal, propertyTypes]);

  return (
    <div
      className={cn(
        "pointer-events-none absolute right-4 top-4 z-[400]",
        "w-[360px] max-w-[calc(100vw-2rem)]",
        "transition-all duration-300",
        selected ? "opacity-100 translate-x-0" : "opacity-0 translate-x-6",
      )}
    >
      {selected && (
        <div className="pointer-events-auto glass-strong rounded-2xl overflow-hidden">
          <header className="flex items-start justify-between gap-3 p-5 pb-3">
            <div className="min-w-0">
              <Badge variant="secondary" className="mb-2 rounded-full font-medium">
                {REGION_LEVEL_LABELS[selected.level]}
              </Badge>
              <h2 className="font-semibold text-2xl tracking-tight leading-tight">
                {stats?.name ?? "…"}
              </h2>
              {stats?.parent_name && (
                <p className="mt-0.5 text-sm text-muted-foreground">{stats.parent_name}</p>
              )}
            </div>
            <Button
              variant="ghost"
              size="icon"
              onClick={clearSelection}
              className="rounded-full shrink-0"
              aria-label="Close"
            >
              <X />
            </Button>
          </header>

          <div className="px-5 pb-4 space-y-4">
            {loading && !stats && (
              <p className="text-sm text-muted-foreground">Loading…</p>
            )}

            {stats && (
              <>
                <BigStat label="Listings" value={num(stats.n)} suffix="active" />

                <div className="grid grid-cols-2 gap-3 rounded-xl bg-muted/50 p-3">
                  <SmallStat label="Avg / m²" value={czkPerM2(stats.avg_per_m2)} />
                  <SmallStat label="Median / m²" value={czkPerM2(stats.median_per_m2)} />
                  <SmallStat label="Avg price" value={czk(stats.avg_price)} />
                  <SmallStat label="Median price" value={czk(stats.median_price)} />
                </div>

                <Separator />

                <div className="space-y-2">
                  <div className="flex items-baseline justify-between">
                    <h3 className="text-sm font-semibold">Socio-economics</h3>
                    <div className="flex items-center gap-2">
                      {stats.csu_aggregated && (
                        <span className="text-[10px] text-muted-foreground/70 italic">aggregated</span>
                      )}
                      {selected.level === "cast_obce" && (
                        // CSU publishes per municipality, not per locality
                        // — so for cast_obce we surface the parent obec's
                        // stats and flag that scope to the user.
                        <span className="text-[10px] text-muted-foreground/70 italic">for whole obec</span>
                      )}
                      {stats.csu?.year && (
                        <span className="text-[10px] uppercase tracking-wider text-muted-foreground">
                          CSU {stats.csu.year}
                        </span>
                      )}
                    </div>
                  </div>
                  {stats.csu ? (
                    <div className="grid grid-cols-2 gap-3 rounded-xl bg-muted/50 p-3">
                      <SmallStat label="Population" value={num(stats.csu.population)} />
                      <SmallStat label="Unemployment" value={pct(stats.csu.unemployment_pct)} />
                      <SmallStat label="Births" value={num(stats.csu.births)} />
                      <SmallStat label="Deaths" value={num(stats.csu.deaths)} />
                      <SmallStat label="Marriages" value={num(stats.csu.marriages)} />
                      <SmallStat label="Divorces" value={num(stats.csu.divorces)} />
                      {stats.csu.migration_balance != null && (
                        <SmallStat
                          label="Migration"
                          value={(stats.csu.migration_balance > 0 ? "+" : "") + num(stats.csu.migration_balance)}
                        />
                      )}
                    </div>
                  ) : (
                    <p className="text-sm text-muted-foreground">
                      CSU data unavailable for this region.
                    </p>
                  )}
                </div>
              </>
            )}
          </div>

          <footer className="border-t bg-muted/30 p-3">
            <Button
              className="w-full rounded-xl"
              disabled={!stats}
              onClick={() => {
                applyFromMap({
                  deal, propertyTypes,
                  regionLevel: selected.level,
                  regionId: selected.id,
                });
                setTab("listings");
              }}
            >
              Open in Listings
              <ArrowRight />
            </Button>
          </footer>
        </div>
      )}
    </div>
  );
}

function BigStat({ label, value, suffix }: { label: string; value: string; suffix?: string }) {
  return (
    <div>
      <div className="stat-label">{label}</div>
      <div className="mt-1 flex items-baseline gap-2">
        <div className="stat-number">{value}</div>
        {suffix && <span className="text-sm text-muted-foreground">{suffix}</span>}
      </div>
    </div>
  );
}

function SmallStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="space-y-0.5">
      <div className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground">
        {label}
      </div>
      <div className="text-base font-semibold tabular-nums leading-tight">{value}</div>
    </div>
  );
}
