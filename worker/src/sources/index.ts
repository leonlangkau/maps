import { nswSource } from './nsw';
import { qldSource } from './qld';
import { tomtomSource } from './tomtom';
import { vicSource } from './vic';
import { waSource } from './wa';
import { wazeSource } from './waze';
import type { Source } from './common';

export const SOURCES: Source[] = [
  nswSource,
  qldSource,
  vicSource,
  waSource,
  tomtomSource,
  wazeSource,
];

export type { Source } from './common';
