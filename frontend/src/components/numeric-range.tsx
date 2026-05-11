import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

/**
 * Tiny "from – to" pair of inputs used by the listings filter card.
 */
export function NumericRange({
  label, suffix,
  min, max,
  onMinChange, onMaxChange,
}: {
  label: string;
  suffix?: string;
  min: number | null;
  max: number | null;
  onMinChange: (v: number | null) => void;
  onMaxChange: (v: number | null) => void;
}) {
  const parse = (s: string): number | null => {
    if (s.trim() === "") return null;
    const n = Number(s);
    return Number.isFinite(n) ? n : null;
  };

  return (
    <div className="flex flex-col gap-1.5">
      <Label>{label}{suffix && <span className="ml-1 normal-case text-muted-foreground">({suffix})</span>}</Label>
      <div className="flex items-center gap-2">
        <Input
          type="number"
          inputMode="numeric"
          placeholder="from"
          value={min ?? ""}
          onChange={(e) => onMinChange(parse(e.target.value))}
        />
        <span className="text-xs text-muted-foreground">—</span>
        <Input
          type="number"
          inputMode="numeric"
          placeholder="to"
          value={max ?? ""}
          onChange={(e) => onMaxChange(parse(e.target.value))}
        />
      </div>
    </div>
  );
}
