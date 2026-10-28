#!/usr/bin/env python3
"""Compile allowlisted catalogue-only SQL in a disposable PostgreSQL cluster; never connect to a store."""
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]
SOURCE=Path('/Users/matthewanton/IdeaProjects/OnlinePosSystem/SQL files/migration.sql')
BIN=Path(os.environ.get('POS_TEST_PG_BIN','/opt/homebrew/opt/postgresql@16/bin'))
SCHEMA=json.loads((ROOT/'store-server/src/main/resources/catalogue-schema-v1.json').read_text())


def statements(text):
    # SQL-aware statement boundaries: quoted strings, identifiers, comments and dollar bodies.
    out=[]; buf=[]; i=0; quote=None; dollar=None
    while i<len(text):
        if dollar:
            if text.startswith(dollar,i): buf.append(dollar);i+=len(dollar);dollar=None
            else: buf.append(text[i]);i+=1
            continue
        if quote:
            ch=text[i];buf.append(ch);i+=1
            if ch==quote:
                if i<len(text) and text[i]==quote:buf.append(text[i]);i+=1
                else:quote=None
            continue
        if text.startswith('--',i):
            end=text.find('\n',i);i=len(text) if end<0 else end;buf.append('\n');continue
        if text.startswith('/*',i):
            end=text.find('*/',i+2)
            if end<0:raise ValueError('Unterminated SQL comment')
            i=end+2;buf.append(' ');continue
        if text[i] in "'\"":quote=text[i];buf.append(text[i]);i+=1;continue
        match=re.match(r'\$[A-Za-z_0-9]*\$',text[i:])
        if match:dollar=match[0];buf.append(dollar);i+=len(dollar);continue
        if text[i]==';':
            item=''.join(buf).strip()
            if item:out.append(item+';')
            buf=[];i+=1;continue
        buf.append(text[i]);i+=1
    return out


def main():
    allowed=set(SCHEMA)|{'new_special_seed'}; kept=[]
    for sql in statements(SOURCE.read_text()):
        match=re.match(r'(?:CREATE\s+(?:TEMP\s+)?TABLE\s+(?:IF NOT EXISTS\s+)?|ALTER TABLE\s+|INSERT INTO\s+|UPDATE\s+)(?:public\.)?(\w+)',sql,re.I)
        if match and match[1].lower() in allowed:
            kept.append(sql)
        elif sql.startswith('WITH inserted AS') and 'INSERT INTO special ' in sql and 'INSERT INTO new_special_seed' in sql:
            kept.append(sql)
    # The extracted executable must never access authentication, customers or historical orders.
    for sql in kept:
        if re.search(r'\b(customers|staff|customer_order|orders|password_reset_tokens|app_security)\b',sql,re.I):
            raise ValueError('Unexpected non-catalogue reference in selected SQL')
    with tempfile.TemporaryDirectory(prefix='onlinepos-catalogue-compile-') as temp:
        root=Path(temp);root.chmod(0o700)
        def run(name,args,**kwargs):return subprocess.run([str(BIN/name),*args],check=True,capture_output=True,text=True,**kwargs)
        run('initdb',['-D',str(root/'data'),'--username=catalogue_compile','--auth=trust','--encoding=UTF8','--locale=C'])
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        run('pg_ctl',['-D',str(root/'data'),'-l',str(root/'server.log'),'-o',f'-h 127.0.0.1 -p {port} -k {root}','-w','start'])
        psql=['-X','-h','127.0.0.1','-p',str(port),'-U','catalogue_compile','-d','postgres','-v','ON_ERROR_STOP=1','-A','-t']
        try:
            try:run('psql',psql,input='BEGIN;\n'+'\n'.join(kept)+'\nCOMMIT;')
            except subprocess.CalledProcessError as failure:raise RuntimeError(failure.stderr) from None
            tables={}
            for table,meta in SCHEMA.items():
                columns=','.join(meta['columns'])
                raw=run('psql',psql+['-c',f"SELECT coalesce(jsonb_agg(to_jsonb(t)),'[]') FROM (SELECT {columns} FROM public.{table}) t;"]).stdout
                tables[table]=json.loads(raw)
            target=ROOT/'store-server/src/main/resources/catalogue-h2-seed.json'
            target.write_text(json.dumps({'sourceSha256':hashlib.sha256(SOURCE.read_bytes()).hexdigest(),'tables':tables},indent=2)+'\n')
            print('Compiled catalogue-only seed:',sum(map(len,tables.values())),'rows across',len(tables),'tables')
        finally:run('pg_ctl',['-D',str(root/'data'),'-m','fast','-w','stop'])


if __name__=='__main__':main()
