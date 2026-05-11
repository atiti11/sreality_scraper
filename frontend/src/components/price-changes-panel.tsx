import { useEffect, useState } from "react";
import { ArrowDownRight, ArrowUpRight, ExternalLink } from "lucide-react";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { ScrollArea } from "@/components/ui/scroll-area";
import { api } from "@/lib/api";
import { useMapStore } from "@/lib/store";
import { czk, relativeDate } from "@/lib/format";
import {
  PROPERTY_LABELS,
  REGION_LEVEL_LABELS,
  type PriceChange,
} from "@/lib/types";

/**
 * Bottom strip on the Map page — listings whose price moved recently in the
 * currently selected region. Sorted by ``changed_at`` descending so the most
 * recent change is at the top; ties broken by ``id`` server-side.
 */
export function PriceChangesPanel() {
  const selected = useMapStore((s) => s.selected);
  const deal = useMapStore((s) => s.deal);
  const propertyTypes = useMapStore((s) => s.propertyTypes);

  const [rows, setRows] = useState<PriceChange[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!selected) {
      setRows([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    api.regionPriceChanges(selected.level, selected.id, deal, propertyTypes, 30)
      .then((r) => { if (!cancelled) setRows(r); })
      .catch(() => { if (!cancelled) setRows([]); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [selected, deal, propertyTypes]);

  return (
    <Card className="h-[260px]">
      <CardHeader className="flex flex-row items-center justify-between pb-2">
        <CardTitle className="text-base">Recent price changes</CardTitle>
        {selected ? (
          <Badge variant="secondary">
            {REGION_LEVEL_LABELS[selected.level]} · last 30 changes
          </Badge>
        ) : (
          <span className="text-xs text-muted-foreground">Pick a region first</span>
        )}
      </CardHeader>
      <CardContent className="px-0 pb-0 pt-0">
        <ScrollArea className="h-[195px] px-6 pb-4">
          {loading && <div className="py-3 text-sm text-muted-foreground">Loading…</div>}
          {!loading && rows.length === 0 && selected && (
            <div className="py-3 text-sm text-muted-foreground">
              No tracked price changes for this region yet.
            </div>
          )}
          <ul className="space-y-1.5">
            {rows.map((r) => (
              <PriceChangeRow key={`${r.hash_id}-${r.changed_at}-${r.field}`} row={r} />
            ))}
          </ul>
        </ScrollArea>
      </CardContent>
    </Card>
  );
}

function PriceChangeRow({ row }: { row: PriceChange }) {
  const oldNum = Number(row.old_value);
  const newNum = Number(row.new_value);
  const dropping = Number.isFinite(oldNum) && Number.isFinite(newNum) && newNum < oldNum;
  const Icon = dropping ? ArrowDownRight : ArrowUpRight;
  const colour = dropping ? "text-emerald-600" : "text-rose-600";

  return (
    <li className="flex items-center justify-between gap-3 border-b py-2 last:border-b-0">
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <Badge variant="outline" className="capitalize">
            {PROPERTY_LABELS[row.property_type]}
          </Badge>
          <span className="truncate font-medium">{row.obec ?? "—"}</span>
          <span className="text-xs text-muted-foreground">{relativeDate(row.changed_at)}</span>
        </div>
        <div className="mt-0.5 flex items-center gap-1.5 text-xs text-muted-foreground">
          <span>{czk(Number.isFinite(oldNum) ? oldNum : null)}</span>
          <span>→</span>
          <span className={colour + " font-medium"}>
            {czk(Number.isFinite(newNum) ? newNum : null)}
          </span>
          {row.delta != null && (
            <span className={colour + " inline-flex items-center"}>
              <Icon className="h-3 w-3" />
              {czk(Math.abs(row.delta))}
            </span>
          )}
        </div>
      </div>
      {row.url && (
        <a
          href={row.url}
          target="_blank"
          rel="noreferrer"
          className="text-xs text-primary hover:underline inline-flex items-center gap-1"
        >
          open <ExternalLink className="h-3 w-3" />
        </a>
      )}
    </li>
  );
}
