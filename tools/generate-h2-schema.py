#!/usr/bin/env python3
"""Generate the H2 structural mirror only; never execute PostgreSQL business/seed/security SQL."""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
FILES = ['migration.sql', 'rls-v5-address.sql', 'rls-v6-drivers.sql',
         'rls-v7-driver-times.sql', 'rls-v8-driver-pickup.sql', 'rls-v9-driver-rates.sql']


def convert(sql):
    sql = re.sub(r'::regclass', '', sql, flags=re.I)
    sql = re.sub(r'\btimestamptz\b', 'TIMESTAMP WITH TIME ZONE', sql, flags=re.I)
    sql = re.sub(r'\bjsonb\b', 'JSON', sql, flags=re.I)
    return sql


def generate(source):
    tables, sequences, additions = {}, {}, []
    for name in FILES:
        text = re.sub(r'--[^\n]*', '', (source / name).read_text())
        for match in re.finditer(r'CREATE SEQUENCE IF NOT EXISTS\s+([\w.]+).*?;', text, re.I | re.S):
            sequences[match[1]] = f'CREATE SEQUENCE IF NOT EXISTS {match[1]} START WITH 1;'
        for match in re.finditer(r'CREATE TABLE (?:IF NOT EXISTS\s+)?([\w.]+)\s*\(', text, re.I):
            start, depth, quote, end = match.end(), 1, False, match.end()
            while depth:
                ch = text[end]
                if ch == "'":
                    quote = not quote
                if not quote:
                    depth += (ch == '(') - (ch == ')')
                end += 1
            table = match[1].lower().removeprefix('public.')
            tables[table] = convert(f'CREATE TABLE IF NOT EXISTS {match[1]} (' + text[start:end]) + ';'
        # Preserve additive fields; IF NOT EXISTS also handles fields in CREATE.
        for match in re.finditer(r'ALTER TABLE\s+([\w.]+)\s+(.*?);', text, re.I | re.S):
            body = match[2]
            for column in re.finditer(r'ADD COLUMN (?:IF NOT EXISTS\s+)?(\w+)\s+(.*?)(?=,\s*ADD |$)', body, re.I | re.S):
                value = column[2].strip()
                additions.append(convert(f'ALTER TABLE {match[1]} ADD COLUMN IF NOT EXISTS {column[1]} {value};'))
    header = '-- Generated from reviewed website SQL by tools/generate-h2-schema.py.\n-- Structural development mirror: no website accounts, orders, RLS, functions or triggers.\n'
    return header + '\n\n'.join([*sequences.values(), *tables.values(), *additions]) + '\n'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    target = ROOT / 'store-server/src/main/resources/db/h2/schema.sql'
    target.write_text(generate(args.source))
    print('Generated', target)
