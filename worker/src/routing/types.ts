export interface RouteStep {
  instruction: string;
  distanceM: number;
  durationS: number;
  /** Turn modifier: 'left', 'slight right', 'straight', ... */
  modifier: string | null;
  /** Road name for the step, when the provider gives one. */
  name: string | null;
}

export interface RouteLeg {
  distanceM: number;
  durationS: number;
  steps: RouteStep[];
  /**
   * Traffic level for each pair of adjacent geometry points, so a leg with N
   * points has N-1 entries: 'low' | 'moderate' | 'heavy' | 'severe' | 'unknown'.
   *
   * This is what makes the route line readable at a glance and what lets the
   * picker say how much of an alternative is spent crawling.
   */
  congestion: string[];
}

export interface RouteOption {
  distanceM: number;
  durationS: number;
  /** Encoded polyline, precision 6. */
  geometry: string;
  legs: RouteLeg[];
  /**
   * What this route would take with clear roads. The difference against
   * durationS is the delay traffic is currently adding, which is the number a
   * driver actually wants when choosing between alternatives.
   */
  durationFreeFlowS: number;
}

export interface RouteResult {
  provider: string;
  routes: RouteOption[];
}

export interface Waypoint {
  lat: number;
  lon: number;
}

export interface PlaceResult {
  name: string;
  address: string | null;
  lat: number;
  lon: number;
}

/**
 * The apps only ever see this shape. Swapping Mapbox for a self-hosted Valhalla
 * is a provider change here, not a change in either app.
 */
export interface RoutingProvider {
  id: string;
  route(from: Waypoint, to: Waypoint, signal?: AbortSignal): Promise<RouteResult>;
  search(
    query: string,
    near: Waypoint | null,
    signal?: AbortSignal,
  ): Promise<PlaceResult[]>;
}
