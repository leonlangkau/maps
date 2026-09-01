import { describe, expect, it } from 'vitest';
import { LAT_COLUMNS, LON_COLUMNS, column, parseCsv } from '../src/cameras/parse';

describe('parseCsv', () => {
  it('reads a simple table', () => {
    const rows = parseCsv('a,b\n1,2\n3,4\n');
    expect(rows).toEqual([
      { a: '1', b: '2' },
      { a: '3', b: '4' },
    ]);
  });

  it('keeps commas inside quoted fields', () => {
    const rows = parseCsv('road,suburb\n"Pacific Hwy, Northbound",Gosford\n');
    expect(rows[0]?.road).toBe('Pacific Hwy, Northbound');
    expect(rows[0]?.suburb).toBe('Gosford');
  });

  it('unescapes doubled quotes', () => {
    const rows = parseCsv('name\n"The ""Big"" Banana"\n');
    expect(rows[0]?.name).toBe('The "Big" Banana');
  });

  it('handles CRLF line endings as one break', () => {
    const rows = parseCsv('a,b\r\n1,2\r\n');
    expect(rows).toEqual([{ a: '1', b: '2' }]);
  });

  it('handles newlines inside quoted fields', () => {
    const rows = parseCsv('a,b\n"line one\nline two",x\n');
    expect(rows.length).toBe(1);
    expect(rows[0]?.a).toBe('line one\nline two');
  });

  it('strips a UTF-8 BOM from the first header', () => {
    const rows = parseCsv('﻿latitude,longitude\n-33.8,151.2\n');
    expect(Object.keys(rows[0] ?? {})).toContain('latitude');
  });

  it('skips blank lines', () => {
    const rows = parseCsv('a,b\n1,2\n\n\n3,4\n');
    expect(rows.length).toBe(2);
  });

  it('returns nothing for empty input', () => {
    expect(parseCsv('')).toEqual([]);
  });

  it('pads rows that are short a column', () => {
    const rows = parseCsv('a,b,c\n1,2\n');
    expect(rows[0]).toEqual({ a: '1', b: '2', c: '' });
  });
});

describe('column', () => {
  it('matches ignoring case, spaces and underscores', () => {
    const record = { 'Road Name': 'Pacific Hwy', LATITUDE: '-33.8' };
    expect(column(record, ['road_name'])).toBe('Pacific Hwy');
    expect(column(record, LAT_COLUMNS)).toBe('-33.8');
  });

  it('tries candidates in order and skips empty values', () => {
    const record = { latitude: '', y: '-33.8' };
    expect(column(record, LAT_COLUMNS)).toBe('-33.8');
  });

  it('returns null when nothing matches', () => {
    expect(column({ foo: 'bar' }, LON_COLUMNS)).toBeNull();
  });
});
