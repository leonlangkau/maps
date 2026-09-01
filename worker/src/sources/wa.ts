import type { AlertKind } from '../lib/types';
import { asString } from './common';
import { geoJsonSource } from './geojson';

/**
 * Main Roads WA publish incidents through their ArcGIS Hub portal. The Hub
 * GeoJSON download is stable for the dataset id below; if Main Roads re-publish
 * the layer, replace the id and nothing else needs to change.
 */
const WA_INCIDENTS_DATASET = 'a385c672d93c4a88b5b232e7fe38f915_0';

const KIND_BY_TYPE: Record<string, AlertKind> = {
  crash: 'crash',
  accident: 'crash',
  collision: 'crash',
  roadworks: 'roadwork',
  roadwork: 'roadwork',
  works: 'roadwork',
  flooding: 'flood',
  flood: 'flood',
  fire: 'fire',
  bushfire: 'fire',
  closure: 'closure',
  'road closure': 'closure',
  congestion: 'congestion',
  event: 'event',
};

export const waSource = geoJsonSource({
  id: 'wa',
  label: 'Main Roads WA incidents',
  state: 'WA',
  url: () =>
    `https://opendata.arcgis.com/datasets/${WA_INCIDENTS_DATASET}.geojson`,
  idKeys: ['OBJECTID', 'objectid', 'IncidentId', 'id'],
  headlineKeys: ['Description', 'description', 'IncidentType', 'Title', 'title'],
  detailKeys: ['Comments', 'comments', 'Advice', 'Detail', 'Impact'],
  roadKeys: ['RoadName', 'road_name', 'Road', 'Location', 'LocationDescription'],
  startKeys: ['StartTime', 'start_time', 'Created', 'CreatedDate'],
  kindOf: (props) => {
    const raw =
      asString(props['IncidentType']) ??
      asString(props['Type']) ??
      asString(props['Category']) ??
      '';
    return KIND_BY_TYPE[raw.toLowerCase()] ?? 'hazard';
  },
  skip: (props) => {
    const status = (
      asString(props['Status']) ??
      asString(props['status']) ??
      ''
    ).toLowerCase();
    return status === 'closed' || status === 'cleared' || status === 'resolved';
  },
  severityOf: (props, kind) => {
    if (kind === 'closure') return 3;
    if (kind === 'roadwork') return 0;
    const impact = (asString(props['Impact']) ?? '').toLowerCase();
    if (impact.includes('major') || impact.includes('closed')) return 3;
    if (impact.includes('moderate')) return 2;
    return 1;
  },
});
