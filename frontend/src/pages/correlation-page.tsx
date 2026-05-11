import { useEffect, useMemo, useState } from "react";
import { ChevronDown, ScatterChart as ScatterIcon } from "lucide-react";
import {
  CartesianGrid,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
  ZAxis,
} from "recharts";

import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { FiltersBar } from "@/components/filters-bar";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";

import { api } from "@/lib/api";
import { useCorrelationStore } from "@/lib/store";
import { czkPerM2, num } from "@/lib/format";
import type {
  CsuMetricInfo, CsuMetricKey, ScatterPoint, ScatterResponse,
} from "@/lib/types";

/**
 * Correlation page — scatter of avg price/m² (per obec) vs a chosen CSU
 * metric. One dot per municipality with enough listings to back a stable
 * average. The correlation coefficient sits in a stat card next to the
 * chart so the user can see at a glance how tightly the cloud trends.
 *
 * Filtering by property type (apartments / houses / land / commercial) and
 * deal (sale / rent / auction) re-runs the aggregation; switching the CSU
 * metric only changes which column from fact_obec_stats we plot on x.
 */
export function CorrelationPage() {
  const deal             = useCorrelationStore((s) => s.deal);
  const setDeal          = useCorrelationStore((s) => s.setDeal);
  const propertyTypes    = useCorrelationStore((s) => s.propertyTypes);
  const togglePropertyType = useCorrelationStore((s) => s.togglePropertyType);
  const metric           = useCorrelationStore((s) => s.metric);
  const setMetric        = useCorrelationStore((s) => s.setMetric);
  const minListings      = useCorrelationStore((s) => s.minListings);

  const [metricsCatalog, setMetricsCatalog] = useState<CsuMetricInfo[]>([]);
  const [data, setData] = useState<ScatterResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Pull the metric catalog once so the dropdown labels match the backend.
  useEffect(() => {
    let cancelled = false;
    api.csuMetrics()
      .then((m) => { if (!cancelled) setMetricsCatalog(m); })
      .catch(() => { /* fallback: dropdown shows keys only */ });
    return () => { cancelled = true; };
  }, []);

  // Re-fetch whenever filters change.
  useEffect(() => {
    if (propertyTypes.length === 0) {
      setData(null);
      setError("Pick at least one property type to plot.");
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    api.scatterPriceVsCsu(metric, deal, propertyTypes, minListings)
      .then((r) => { if (!cancelled) setData(r); })
      .catch((e: Error) => { if (!cancelled) setError(e.message); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [metric, deal, propertyTypes, minListings]);

  // Recharts wants vanilla objects; thread our typed points through unchanged.
  const chartData = useMemo(() => data?.points ?? [], [data]);

  return (
    <div className="space-y-4">
      {/* Headline + filters */}
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="stat-label">Correlation</div>
          <h1 className="stat-number">
            Price&nbsp;/&nbsp;m² vs {data?.metric_label ?? "…"}
          </h1>
          <p className="mt-1 text-sm text-muted-foreground max-w-prose">
            One dot per municipality; x = latest CSU value, y = avg listing
            price per m² under the current filters. Obce with fewer than
            {" "}{minListings} listings are omitted.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <FiltersBar
            deal={deal}
            onDealChange={setDeal}
            propertyTypes={propertyTypes}
            onPropertyToggle={togglePropertyType}
          />
          <MetricPicker
            metric={metric}
            metrics={metricsCatalog}
            onChange={setMetric}
          />
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr,260px] gap-4">
        {/* Chart */}
        <Card className="p-4 h-[640px]">
          {loading && !data && (
            <p className="text-sm text-muted-foreground">Loading…</p>
          )}
          {error && (
            <p className="text-sm text-red-600">{error}</p>
          )}
          {!loading && !error && data && data.points.length === 0 && (
            <p className="text-sm text-muted-foreground">
              No obce match the current filters with at least {minListings}{" "}
              listings. Try widening the property-type filter or lowering the
              ``min_listings`` threshold.
            </p>
          )}
          {data && data.points.length > 0 && (
            <ResponsiveContainer width="100%" height="100%">
              <ScatterChart margin={{ top: 16, right: 24, bottom: 48, left: 56 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis
                  type="number"
                  dataKey="metric_value"
                  name={data.metric_label}
                  label={{
                    value: `${data.metric_label}${data.metric_unit ? ` (${data.metric_unit})` : ""}`,
                    position: "insideBottom",
                    offset: -16,
                    style: { fill: "#475569", fontSize: 12 },
                  }}
                  tick={{ fill: "#64748b", fontSize: 11 }}
                />
                <YAxis
                  type="number"
                  dataKey="avg_per_m2"
                  name="Avg price/m²"
                  label={{
                    value: "Avg price (CZK/m²)",
                    angle: -90,
                    position: "insideLeft",
                    offset: 10,
                    style: { fill: "#475569", fontSize: 12 },
                  }}
                  tick={{ fill: "#64748b", fontSize: 11 }}
                  tickFormatter={(v: number) => czkPerM2(v).replace(" CZK/m²", "")}
                />
                {/* ZAxis with range controls the bubble radius. We map the
                    listing count (n) into [40, 360] px². */}
                <ZAxis
                  type="number"
                  dataKey="n"
                  range={[40, 360]}
                  name="Listings"
                />
                <Tooltip
                  cursor={{ strokeDasharray: "3 3" }}
                  content={<ScatterTooltip metricLabel={data.metric_label} />}
                />
                <Scatter
                  data={chartData}
                  fill="#10b981"
                  fillOpacity={0.65}
                  stroke="#047857"
                  strokeOpacity={0.7}
                />
              </ScatterChart>
            </ResponsiveContainer>
          )}
        </Card>

        {/* Side stats */}
        <Card className="p-4 space-y-4">
          <div>
            <div className="stat-label">Pearson r</div>
            <div className="mt-1 stat-number">
              {data?.correlation != null
                ? data.correlation.toFixed(3)
                : "—"}
            </div>
            <p className="mt-1 text-[11px] text-muted-foreground">
              Linear correlation between {data?.metric_label ?? "metric"} and
              {" "}avg price/m². <strong>+1</strong> = perfectly aligned,
              {" "}<strong>0</strong> = no linear relationship,
              {" "}<strong>–1</strong> = inverse.
            </p>
          </div>

          <div className="h-px bg-border" />

          <div className="grid grid-cols-2 gap-3">
            <div>
              <div className="stat-label">Obce plotted</div>
              <div className="mt-1 text-2xl font-semibold tabular-nums">
                {num(data?.n_points ?? 0)}
              </div>
            </div>
            <div>
              <div className="stat-label">Min listings</div>
              <div className="mt-1 text-2xl font-semibold tabular-nums">
                {num(minListings)}
              </div>
            </div>
          </div>

          {data && (
            <>
              <div className="h-px bg-border" />
              <div className="flex flex-wrap gap-1">
                <Badge variant="secondary" className="rounded-full">
                  {data.deal}
                </Badge>
                {data.property_types.map((p) => (
                  <Badge key={p} variant="secondary" className="rounded-full">
                    {p}
                  </Badge>
                ))}
              </div>
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

// ----------------------------------------------------------------------------

function MetricPicker({
  metric, metrics, onChange,
}: {
  metric: CsuMetricKey;
  metrics: CsuMetricInfo[];
  onChange: (k: CsuMetricKey) => void;
}) {
  return (
    <div className="glass rounded-full px-1.5 py-1.5 flex items-center gap-1.5">
      <div className="flex items-center gap-1.5 px-3 py-1 text-xs font-medium text-muted-foreground">
        <ScatterIcon className="h-3.5 w-3.5" /> Metric
      </div>
      <div className="h-6 w-px bg-border" />
      <Select value={metric} onValueChange={(v) => onChange(v as CsuMetricKey)}>
        <SelectTrigger className="h-8 w-[180px] rounded-full border-0 bg-transparent shadow-none focus:ring-0">
          <SelectValue />
          <ChevronDown className="h-3 w-3 opacity-50" />
        </SelectTrigger>
        <SelectContent>
          {(metrics.length > 0
            ? metrics
            : DEFAULT_METRICS_FALLBACK
          ).map((m) => (
            <SelectItem key={m.key} value={m.key}>
              {m.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

/**
 * Used if the /api/scatter/csu-metrics call hasn't returned yet — keeps the
 * dropdown populated so it isn't visually empty on first render.
 */
const DEFAULT_METRICS_FALLBACK: CsuMetricInfo[] = [
  { key: "unemployment_pct",  label: "Unemployment",       unit: "%" },
  { key: "population",        label: "Population",         unit: "people" },
  { key: "marriages",         label: "Marriages",          unit: "per year" },
  { key: "divorces",          label: "Divorces",           unit: "per year" },
  { key: "births",            label: "Births",             unit: "per year" },
  { key: "deaths",            label: "Deaths",             unit: "per year" },
  { key: "migration_balance", label: "Migration balance",  unit: "per year" },
];

// ----------------------------------------------------------------------------

function ScatterTooltip({
  active, payload, metricLabel,
}: {
  // Recharts' typings for tooltip content props are awkward, hence the
  // pragmatic loose typing.
  active?: boolean;
  payload?: Array<{ payload: ScatterPoint }>;
  metricLabel: string;
}) {
  if (!active || !payload || payload.length === 0) return null;
  const p = payload[0].payload;
  return (
    <div className="rounded-lg border bg-popover px-3 py-2 text-xs shadow-md min-w-[200px]">
      <div className="font-semibold text-sm">{p.obec_name}</div>
      <div className="text-muted-foreground text-[11px]">
        {[p.okres, p.kraj].filter(Boolean).join(", ")}
      </div>
      <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 tabular-nums">
        <span className="text-muted-foreground">{metricLabel}</span>
        <span className="text-right font-medium">{num(p.metric_value)}</span>
        <span className="text-muted-foreground">Avg / m²</span>
        <span className="text-right font-medium">{czkPerM2(p.avg_per_m2)}</span>
        <span className="text-muted-foreground">Listings</span>
        <span className="text-right font-medium">{num(p.n)}</span>
        {p.metric_year && (
          <>
            <span className="text-muted-foreground">Year</span>
            <span className="text-right font-medium">{p.metric_year}</span>
          </>
        )}
      </div>
    </div>
  );
}
