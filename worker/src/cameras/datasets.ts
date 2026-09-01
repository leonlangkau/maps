import type { CameraKind } from '../lib/types';

/**
 * Where the government camera datasets live.
 *
 * Both NSW and Queensland publish through CKAN portals, so the datastore API
 * gives stable JSON keyed by resource id — no scraping and no HTML parsing.
 * Victoria publishes through DataVic, also CKAN.
 *
 * Resource ids are the one thing that changes when an agency re-publishes a
 * dataset. They are collected here, and only here, so fixing a moved dataset is
 * a one-line edit rather than a hunt through parsing code.
 */
export interface CameraDataset {
  /** Stable prefix for generated camera ids; also the delete key on refresh. */
  source: string;
  label: string;
  state: string;
  /** CKAN portal base, e.g. https://www.data.qld.gov.au */
  portal: string;
  resourceId: string;
  kind: CameraKind;
  /** Column-name candidates, most specific first. */
  roadColumns: string[];
  suburbColumns: string[];
  speedColumns?: string[];
  /** Confirmed against the live portal, or still needs the id pasted in. */
  verified: boolean;
}

export const CAMERA_DATASETS: CameraDataset[] = [
  {
    source: 'qld-mobile',
    label: 'QLD active mobile speed camera sites',
    state: 'QLD',
    portal: 'https://www.data.qld.gov.au',
    resourceId: 'f6b5c37e-de9d-4041-8c18-f4d4b6c593a8',
    kind: 'mobile_zone',
    roadColumns: ['road_name', 'location', 'site', 'street'],
    suburbColumns: ['locality', 'suburb', 'town', 'district'],
    speedColumns: ['speed_limit', 'posted_speed'],
    verified: true,
  },
  {
    source: 'qld-trailer',
    label: 'QLD road safety camera trailer sites',
    state: 'QLD',
    portal: 'https://www.data.qld.gov.au',
    resourceId: 'd059503f-3685-4669-8c43-df5b74da8ba8',
    kind: 'trailer',
    roadColumns: ['road_name', 'location', 'site', 'street'],
    suburbColumns: ['locality', 'suburb', 'town'],
    speedColumns: ['speed_limit', 'posted_speed'],
    verified: true,
  },
  // NSW and VIC publish the same way, but their resource ids need pasting in
  // from the portal. Until then these are skipped rather than guessed at:
  // a wrong id would silently import the wrong dataset.
  {
    source: 'nsw-fixed',
    label: 'NSW fixed and red-light speed cameras',
    state: 'NSW',
    portal: 'https://data.nsw.gov.au',
    resourceId: '',
    kind: 'fixed_speed',
    roadColumns: ['road_name', 'location', 'street'],
    suburbColumns: ['suburb', 'locality'],
    speedColumns: ['speed_limit'],
    verified: false,
  },
  {
    source: 'nsw-mobile',
    label: 'NSW mobile speed camera locations',
    state: 'NSW',
    portal: 'https://data.nsw.gov.au',
    resourceId: '',
    kind: 'mobile_zone',
    roadColumns: ['road_name', 'location', 'street'],
    suburbColumns: ['suburb', 'locality'],
    speedColumns: ['speed_limit'],
    verified: false,
  },
  {
    source: 'vic-mobile',
    label: 'VIC road safety camera network mobile locations',
    state: 'VIC',
    portal: 'https://discover.data.vic.gov.au',
    resourceId: '',
    kind: 'mobile_zone',
    roadColumns: ['road_name', 'location', 'street', 'site_description'],
    suburbColumns: ['suburb', 'locality'],
    speedColumns: ['speed_limit'],
    verified: false,
  },
];
