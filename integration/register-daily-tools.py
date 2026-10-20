"""Register the seven fixed scripts; existing names are left untouched unless explicitly requested."""
import argparse
import http.client
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit

parser=argparse.ArgumentParser()
parser.add_argument('--catalog-url',default='http://127.0.0.1:8000')
parser.add_argument('--token-file',type=Path)
parser.add_argument('--host',required=True)
parser.add_argument('--script-path',required=True)
parser.add_argument('--ssh-user',default='agent')
parser.add_argument('--ssh-port',type=int,default=22)
parser.add_argument('--update-existing',action='store_true')
args=parser.parse_args()
url=urlsplit(args.catalog_url)
if url.scheme not in ('http','https') or not url.hostname or url.username or url.password or url.path not in ('','/') or url.query or url.fragment:
    parser.error('Use a base HTTP(S) URL without credentials or path')
if any(c.isspace() for c in args.script_path) or not args.script_path.lower().endswith('daily-tools.ps1'):
    parser.error('Use the absolute daily-tools.ps1 path without spaces')
token=args.token_file.read_text(encoding='utf-8-sig').strip() if args.token_file else os.environ.get('TOOLSTORE_TOKEN','')
if not token:parser.error('Supply TOOLSTORE_TOKEN or --token-file')

def api(method,route,body=None):
    kind=http.client.HTTPSConnection if url.scheme=='https' else http.client.HTTPConnection
    connection=kind(url.hostname,url.port,timeout=15)
    try:
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'}
        connection.request(method,route,body=json.dumps(body).encode() if body is not None else None,headers=headers)
        response=connection.getresponse();data=response.read(1024*1024+1)
        if response.status not in (200,201) or len(data)>1024*1024:raise RuntimeError('Catalog request failed: HTTP '+str(response.status))
        return json.loads(data)
    finally:connection.close()

try:
    root=Path(__file__).resolve().parents[1]
    records=json.loads((root/'examples/daily-tools.windows.json').read_text(encoding='utf-8-sig'))
    existing={item['name']:item for item in api('GET','/api/tools')}
    results=[]
    for item in records:
        item.update(target_host=args.host,user=args.ssh_user,port=args.ssh_port)
        item['command_template']=item['command_template'].replace('C:\\AgentTools\\daily-tools.ps1',args.script_path)
        previous=existing.get(item['name'])
        if previous and not args.update_existing:
            results.append({'name':item['name'],'id':previous['id'],'action':'kept'});continue
        route='/api/tools/'+str(previous['id']) if previous else '/api/tools'
        saved=api('PUT' if previous else 'POST',route,item)
        results.append({'name':item['name'],'id':saved['id'],'action':'updated' if previous else 'created'})
    print(json.dumps({'tools':results},separators=(',',':')))
except Exception as exc:
    print(json.dumps({'status':'error','message':str(exc)}),file=sys.stderr);sys.exit(1)
