import { useEffect, useMemo, useState } from "react";
import { X } from "lucide-react";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { api } from "@/lib/api";
import { useListingsStore } from "@/lib/store";
import type { RegionsTree } from "@/lib/types";

/**
 * Cascading Region → District → Municipality picker for the Listings tab.
 * Cast_obce isn't exposed here because the dropdown would have ~15 000 rows
 * and the use-case is "pick a town" anyway.
 */
export function RegionPicker() {
  const regionLevel = useListingsStore((s) => s.regionLevel);
  const regionId = useListingsStore((s) => s.regionId);
  const setRegion = useListingsStore((s) => s.setRegion);

  const [tree, setTree] = useState<RegionsTree | null>(null);

  useEffect(() => {
    api.regionsTree().then(setTree).catch(() => setTree(null));
  }, []);

  // Derive currently selected kraj/okres so the cascading selects stay in sync
  // when the user came from the map (e.g. they clicked an obec on the map).
  const selectedObec = useMemo(
    () => (regionLevel === "obec" && regionId != null ? tree?.obce.find((o) => o.id === regionId) : null),
    [tree, regionLevel, regionId],
  );
  const selectedOkres = useMemo(() => {
    if (regionLevel === "okres" && regionId != null) return tree?.okresy.find((o) => o.id === regionId);
    if (selectedObec) return tree?.okresy.find((o) => o.id === selectedObec.okres_id);
    return null;
  }, [tree, regionLevel, regionId, selectedObec]);
  const selectedKraj = useMemo(() => {
    if (regionLevel === "kraj" && regionId != null) return tree?.kraje.find((k) => k.id === regionId);
    if (selectedOkres) return tree?.kraje.find((k) => k.id === selectedOkres.kraj_id);
    return null;
  }, [tree, regionLevel, regionId, selectedOkres]);

  const okresyForKraj = useMemo(() =>
    selectedKraj && tree ? tree.okresy.filter((o) => o.kraj_id === selectedKraj.id) : [],
  [tree, selectedKraj]);

  const obceForOkres = useMemo(() =>
    selectedOkres && tree ? tree.obce.filter((o) => o.okres_id === selectedOkres.id) : [],
  [tree, selectedOkres]);

  if (!tree) {
    return <p className="text-sm text-muted-foreground">Loading regions…</p>;
  }

  const onKrajChange = (idStr: string) => {
    if (idStr === "_all") return setRegion(null, null);
    const id = Number(idStr);
    setRegion("kraj", id);
  };
  const onOkresChange = (idStr: string) => {
    if (idStr === "_all") {
      if (selectedKraj) return setRegion("kraj", selectedKraj.id);
      return setRegion(null, null);
    }
    setRegion("okres", Number(idStr));
  };
  const onObecChange = (idStr: string) => {
    if (idStr === "_all") {
      if (selectedOkres) return setRegion("okres", selectedOkres.id);
      if (selectedKraj) return setRegion("kraj", selectedKraj.id);
      return setRegion(null, null);
    }
    setRegion("obec", Number(idStr));
  };

  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
      <PickerSelect
        label="Region"
        value={selectedKraj ? String(selectedKraj.id) : "_all"}
        onChange={onKrajChange}
        options={[{ id: "_all", name: "All regions" }, ...tree.kraje.map((k) => ({ id: String(k.id), name: k.name }))]}
      />
      <PickerSelect
        label="District"
        value={selectedOkres ? String(selectedOkres.id) : "_all"}
        onChange={onOkresChange}
        disabled={!selectedKraj}
        options={[
          { id: "_all", name: selectedKraj ? "All districts in region" : "Pick a region first" },
          ...okresyForKraj.map((o) => ({ id: String(o.id), name: o.name })),
        ]}
      />
      <PickerSelect
        label="Municipality"
        value={selectedObec ? String(selectedObec.id) : "_all"}
        onChange={onObecChange}
        disabled={!selectedOkres}
        options={[
          { id: "_all", name: selectedOkres ? "All municipalities in district" : "Pick a district first" },
          ...obceForOkres.map((o) => ({ id: String(o.id), name: o.name })),
        ]}
      />
      {regionId !== null && (
        <div className="md:col-span-3">
          <Button variant="ghost" size="sm" onClick={() => setRegion(null, null)}>
            <X /> Clear region
          </Button>
        </div>
      )}
    </div>
  );
}

function PickerSelect({
  label, value, onChange, options, disabled = false,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  options: { id: string; name: string }[];
  disabled?: boolean;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label>{label}</Label>
      <Select value={value} onValueChange={onChange} disabled={disabled}>
        <SelectTrigger>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {options.map((o) => (
            <SelectItem key={o.id} value={o.id}>{o.name}</SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
