import { ChevronDown, Filter } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";
import { DEAL_LABELS, PROPERTY_LABELS, type DealType, type PropertyType } from "@/lib/types";

interface Props {
  deal: DealType;
  onDealChange: (d: DealType) => void;
  propertyTypes: PropertyType[];
  onPropertyToggle: (t: PropertyType) => void;
  className?: string;
}

const DEALS: DealType[] = ["sale", "rent", "auction"];
const PROPS: PropertyType[] = ["apartment", "house", "land", "commercial"];

/**
 * Compact filter bar — designed to float on top of the map as a single pill.
 * Deal type is a select (single choice); property types are toggle buttons
 * that can be combined.
 */
export function FiltersBar({
  deal, onDealChange, propertyTypes, onPropertyToggle, className,
}: Props) {
  return (
    <div
      className={cn(
        "glass rounded-full px-1.5 py-1.5",
        "flex flex-wrap items-center gap-1.5",
        className,
      )}
    >
      <div className="flex items-center gap-1.5 px-3 py-1 text-xs font-medium text-muted-foreground">
        <Filter className="h-3.5 w-3.5" /> Filters
      </div>

      <div className="h-6 w-px bg-border" />

      <Select value={deal} onValueChange={(v) => onDealChange(v as DealType)}>
        <SelectTrigger className="h-8 w-[100px] rounded-full border-0 bg-transparent shadow-none focus:ring-0">
          <SelectValue />
          <ChevronDown className="h-3 w-3 opacity-50" />
        </SelectTrigger>
        <SelectContent>
          {DEALS.map((d) => (
            <SelectItem key={d} value={d}>{DEAL_LABELS[d]}</SelectItem>
          ))}
        </SelectContent>
      </Select>

      <div className="h-6 w-px bg-border" />

      <div className="flex items-center gap-1">
        {PROPS.map((p) => {
          const active = propertyTypes.includes(p);
          return (
            <Button
              key={p}
              size="sm"
              variant={active ? "default" : "ghost"}
              onClick={() => onPropertyToggle(p)}
              className={cn(
                "h-8 rounded-full px-3 text-xs",
                active && "shadow-sm",
              )}
            >
              {PROPERTY_LABELS[p]}
            </Button>
          );
        })}
      </div>
    </div>
  );
}
