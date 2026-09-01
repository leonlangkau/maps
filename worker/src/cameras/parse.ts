/**
 * Minimal RFC 4180 CSV reader. The state camera datasets are published as CSV
 * with quoted, comma-bearing road names, so splitting on commas is not enough.
 */
export function parseCsv(text: string): Record<string, string>[] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let inQuotes = false;

  // Strip a UTF-8 BOM: it otherwise becomes part of the first header name.
  const input = text.charCodeAt(0) === 0xfeff ? text.slice(1) : text;

  for (let i = 0; i < input.length; i++) {
    const char = input[i]!;

    if (inQuotes) {
      if (char === '"') {
        if (input[i + 1] === '"') {
          field += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        field += char;
      }
      continue;
    }

    if (char === '"') {
      inQuotes = true;
    } else if (char === ',') {
      row.push(field);
      field = '';
    } else if (char === '\n' || char === '\r') {
      // Treat CRLF as one break, and ignore blank lines entirely.
      if (char === '\r' && input[i + 1] === '\n') i++;
      row.push(field);
      field = '';
      if (row.some((c) => c.trim() !== '')) rows.push(row);
      row = [];
    } else {
      field += char;
    }
  }
  row.push(field);
  if (row.some((c) => c.trim() !== '')) rows.push(row);

  const header = rows.shift();
  if (!header) return [];

  const keys = header.map((h) => h.trim());
  return rows.map((cells) => {
    const record: Record<string, string> = {};
    for (const [i, key] of keys.entries()) {
      record[key] = (cells[i] ?? '').trim();
    }
    return record;
  });
}

/** Case- and separator-insensitive column lookup. */
export function column(
  record: Record<string, string>,
  candidates: string[],
): string | null {
  const normalise = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');
  const index = new Map<string, string>();
  for (const key of Object.keys(record)) {
    index.set(normalise(key), key);
  }
  for (const candidate of candidates) {
    const key = index.get(normalise(candidate));
    if (key !== undefined) {
      const value = record[key];
      if (value !== undefined && value.trim() !== '') return value.trim();
    }
  }
  return null;
}

export const LAT_COLUMNS = [
  'latitude', 'lat', 'y', 'ycoord', 'y_coord', 'gps_lat', 'wgs84_latitude',
];
export const LON_COLUMNS = [
  'longitude', 'long', 'lon', 'lng', 'x', 'xcoord', 'x_coord', 'gps_long',
  'wgs84_longitude',
];
