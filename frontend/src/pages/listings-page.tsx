import { RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ListingsTable } from "@/components/listings-table";
import { NumericRange } from "@/components/numeric-range";
import { RegionPicker } from "@/components/region-picker";
import { DEAL_LABELS, PROPERTY_LABELS, type DealType, type PropertyType } from "@/lib/types";
import { useListingsStore } from "@/lib/store";
import { cn } from "@/lib/utils";

const DEALS: DealType[] = ["sale", "rent", "auction"];
const PROPS: PropertyType[] = ["apartment", "house", "land", "commercial"];

export function ListingsPage() {
  const f = useListingsStore();

  return (
    <div className="grid gap-6 lg:grid-cols-[360px_1fr]">
      {/* Sidebar — sticky filter panel */}
      <aside className="lg:sticky lg:top-[5.5rem] lg:max-h-[calc(100vh-7rem)] lg:overflow-y-auto">
        <Card className="rounded-2xl border-0 shadow-[0_2px_20px_-12px_rgb(0_0_0_/_0.15)]">
          <div className="flex items-center justify-between p-5 pb-3">
            <h2 className="font-semibold tracking-tight">Filters</h2>
            <Button variant="ghost" size="sm" onClick={f.reset} className="rounded-full">
              <RotateCcw className="h-3 w-3" />
              Reset
            </Button>
          </div>

          <div className="space-y-5 px-5 pb-5">
            {/* Deal type pills */}
            <Section label="Deal type">
              <div className="flex gap-1.5">
                {DEALS.map((d) => (
                  <Button
                    key={d}
                    size="sm"
                    variant={f.deal === d ? "default" : "outline"}
                    onClick={() => f.setDeal(d)}
                    className={cn("flex-1 rounded-full text-xs")}
                  >
                    {DEAL_LABELS[d]}
                  </Button>
                ))}
              </div>
            </Section>

            {/* Property type pills */}
            <Section label="Property types">
              <div className="flex flex-wrap gap-1.5">
                {PROPS.map((p) => {
                  const active = f.propertyTypes.includes(p);
                  return (
                    <Button
                      key={p}
                      size="sm"
                      variant={active ? "default" : "outline"}
                      onClick={() => f.togglePropertyType(p)}
                      className="rounded-full text-xs"
                    >
                      {PROPERTY_LABELS[p]}
                    </Button>
                  );
                })}
              </div>
            </Section>

            <Section label="Location">
              <RegionPicker />
            </Section>

            <NumericRange
              label="Price"
              suffix="CZK"
              min={f.priceMin}
              max={f.priceMax}
              onMinChange={(v) => f.patch({ priceMin: v })}
              onMaxChange={(v) => f.patch({ priceMax: v })}
            />
            <NumericRange
              label="Price / m²"
              suffix="CZK"
              min={f.perM2Min}
              max={f.perM2Max}
              onMinChange={(v) => f.patch({ perM2Min: v })}
              onMaxChange={(v) => f.patch({ perM2Max: v })}
            />
            <NumericRange
              label="Area"
              suffix="m²"
              min={f.areaMin}
              max={f.areaMax}
              onMinChange={(v) => f.patch({ areaMin: v })}
              onMaxChange={(v) => f.patch({ areaMax: v })}
            />

            <Section
              label="Max unemployment"
              note="Only municipalities whose latest CSU unemployment rate is ≤ this value."
            >
              <NumericRange
                label=""
                suffix="%"
                min={null}
                max={f.unemploymentMax}
                onMinChange={() => {}}
                onMaxChange={(v) => f.patch({ unemploymentMax: v })}
              />
            </Section>
          </div>
        </Card>
      </aside>

      {/* Main — results */}
      <section>
        <ListingsTable />
      </section>
    </div>
  );
}

function Section({
  label, note, children,
}: { label: string; note?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2">
      <div>
        <div className="stat-label">{label}</div>
        {note && <p className="mt-0.5 text-[11px] text-muted-foreground/80">{note}</p>}
      </div>
      {children}
    </div>
  );
}
