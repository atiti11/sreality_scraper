import { FiltersBar } from "@/components/filters-bar";
import { MapView } from "@/components/map-view";
import { RegionStatsPanel } from "@/components/region-stats-panel";
import { useMapStore } from "@/lib/store";

/**
 * Map page — full-bleed map (under the sticky 64 px header) with floating
 * controls.
 *
 *   - filter pill: top-center
 *   - choropleth map: fills the rest of the viewport
 *   - region detail card: slides in from the right when a polygon is clicked
 *   - legend: bottom-left
 */
export function MapPage() {
  const deal = useMapStore((s) => s.deal);
  const setDeal = useMapStore((s) => s.setDeal);
  const propertyTypes = useMapStore((s) => s.propertyTypes);
  const togglePropertyType = useMapStore((s) => s.togglePropertyType);

  return (
    <div className="relative h-[calc(100vh-4rem)] w-full overflow-hidden">
      <MapView />

      {/* Floating filter pill at top center */}
      <div className="pointer-events-none absolute inset-x-0 top-4 z-[400] flex justify-center">
        <div className="pointer-events-auto">
          <FiltersBar
            deal={deal}
            onDealChange={setDeal}
            propertyTypes={propertyTypes}
            onPropertyToggle={togglePropertyType}
          />
        </div>
      </div>

      {/* Region detail panel — appears on right when polygon clicked */}
      <RegionStatsPanel />
    </div>
  );
}
