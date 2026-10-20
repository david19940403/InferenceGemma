"""Bounded, read-only daily tools. Only aliases are accepted; private paths stay in local settings."""
import base64
import collections
import datetime as dt
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import warnings

SCRIPT_FOLDER = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPT_FOLDER.parent.parent if SCRIPT_FOLDER.parent.name == 'remote-scripts' else SCRIPT_FOLDER
ROOT = Path(os.environ.get('INFERENCE_TOOLS_ROOT', str(DEFAULT_ROOT))).resolve()
PRIVATE = ROOT / '.cache' / 'daily-tools'
PROJECTS = ('inferencegemma', 'scanflex', 'posandroid', 'prestashop')
ACTIONS = {'vpn': ('',), 'git': PROJECTS, 'adb': ('',), 'servicios': ('',),
           'imagenes': ('productos',), 'n8n': ('aperturas_takepos',), 'apks': PROJECTS[:3]}

class ToolError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def settings():
    home = Path.home()
    result = {'projects': {'inferencegemma': str(ROOT), 'scanflex': str(home/'AndroidStudioProjects'/'ScanFlex'),
                         'posandroid': str(ROOT.parent/'posandroid'), 'prestashop': str(ROOT.parent/'prestashop preparacion')},
              'image_folders': {'productos': str(home/'Downloads'/'productos')}, 'workflows': {}}
    file = PRIVATE/'settings.json'
    if file.exists():
        if file.stat().st_size > 65536:
            raise ToolError('CONFIG_ERROR', 'Configuración local demasiado grande')
        try:
            custom = json.loads(file.read_text(encoding='utf-8-sig'))
            for section in result:
                values = custom.get(section, {})
                if not isinstance(values, dict) or not all(isinstance(v,str) for v in values.values()):
                    raise ValueError()
                result[section].update(values)
        except (ValueError, TypeError):
            raise ToolError('CONFIG_ERROR', 'Revisa .cache/daily-tools/settings.json')
    return result


def configured(section, alias):
    value = settings()[section].get(alias)
    if not value:
        raise ToolError('CONFIG_MISSING', 'Configura el alias en .cache/daily-tools/settings.json')
    path = Path(os.path.expandvars(value)).expanduser().resolve()
    if not path.exists():
        raise ToolError('PATH_NOT_FOUND', 'La ruta configurada para ese alias no existe en esta PC')
    return path


def run(args, timeout=15, required=True):
    if not shutil.which(str(args[0])):
        raise ToolError('DEPENDENCY_MISSING', 'No está disponible '+str(args[0]))
    try:
        p = subprocess.run(args, capture_output=True, timeout=timeout, shell=False)
    except subprocess.TimeoutExpired:
        raise ToolError('COMMAND_TIMEOUT', 'La consulta excedió el tiempo permitido')
    if len(p.stdout)+len(p.stderr) > 1024*1024:
        raise ToolError('OUTPUT_LIMIT', 'La consulta interna devolvió demasiados datos')
    if p.returncode and required:
        raise ToolError('COMMAND_FAILED', 'No se pudo consultar '+Path(args[0]).stem)
    return p.returncode, p.stdout.decode('utf-8-sig', errors='replace')


def powershell(source):
    encoded = base64.b64encode(source.encode('utf-16le')).decode('ascii')
    _, text = run(['powershell.exe','-NoLogo','-NoProfile','-NonInteractive','-EncodedCommand',encoded], 20)
    return json.loads(text)


def git_status(alias):
    folder = configured('projects',alias)
    code, text = run(['git','-C',str(folder),'status','--porcelain=v1','--untracked-files=normal'],required=False)
    if code:
        raise ToolError('GIT_NOT_AVAILABLE', 'No hay un repositorio Git accesible para ese proyecto')
    entries = text.splitlines()
    code, branch = run(['git','-C',str(folder),'symbolic-ref','--short','-q','HEAD'],required=False)
    if code:
        _, branch = run(['git','-C',str(folder),'rev-parse','--short','HEAD'])
    staged = sum(line[:1] not in (' ','?') for line in entries)
    modified = sum(len(line)>1 and line[1] not in (' ','?') for line in entries)
    untracked = sum(line.startswith('??') for line in entries)
    return {'project':alias,'branch':branch.strip(),'staged':staged,'modified':modified,'untracked_entries':untracked}, None


def vpn_status():
    data = powershell("""$ErrorActionPreference='Stop'; $ips=@(Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.IPAddress -like '10.12.14.*' -or $_.IPAddress -like '192.168.9.*'} | Select-Object IPAddress,InterfaceAlias); $services=@(Get-Service -Name 'WireGuardTunnel*' -ErrorAction SilentlyContinue); @{addresses=$ips;tunnels_running=@($services|Where-Object Status -eq 'Running').Count;tunnels_total=$services.Count}|ConvertTo-Json -Compress -Depth 4""")
    addresses = data['addresses']
    return {'vpn_ips':[x['IPAddress'] for x in addresses][:6], 'tunnels_running':data['tunnels_running'],
            'tunnels_total':data['tunnels_total']}, {'interfaces':addresses}


def adb_devices():
    _, text = run(['adb','devices','-l'])
    devices=[]
    for line in text.splitlines():
        words=line.split()
        if len(words)<2 or words[0] in ('List','*'):continue
        model=next((x[6:] for x in words[2:] if x.startswith('model:')),None)
        devices.append({'serial':words[0],'state':words[1],'model':model})
    return {'connected':sum(x['state']=='device' for x in devices), 'unauthorized':sum(x['state']=='unauthorized' for x in devices),
            'offline':sum(x['state']=='offline' for x in devices), 'models':[x['model'] for x in devices if x['model']][:4]}, {'devices':devices}


def services_status():
    data=powershell("""$ErrorActionPreference='Stop'; $wanted=@(22,8000,18000,5678,3306,5432,8080); $ports=@(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue|Where-Object {$_.LocalPort -in $wanted}|Select-Object -ExpandProperty LocalPort -Unique|Sort-Object); @{listening_ports=$ports;docker_running=@(Get-Process -Name 'com.docker.backend' -ErrorAction SilentlyContinue).Count -gt 0;wireguard_running=@(Get-Service -Name 'WireGuardTunnel*' -ErrorAction SilentlyContinue|Where-Object Status -eq 'Running').Count}|ConvertTo-Json -Compress""")
    return data,None


def image_audit(alias):
    try:from PIL import Image
    except ImportError:raise ToolError('DEPENDENCY_MISSING','Se requiere Pillow en el Python de esta PC')
    folder=configured('image_folders',alias)
    if not folder.is_dir():raise ToolError('INVALID_FOLDER','El alias debe apuntar a una carpeta')
    extensions={'.jpg','.jpeg','.png','.webp','.gif','.bmp','.tif','.tiff','.avif'}
    files=sorted((p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in extensions),key=lambda p:p.name)
    checked=[];formats=collections.Counter();invalid=0;small=0;skipped=0
    for file in files[:500]:
        if file.stat().st_size>25*1024*1024:
            skipped+=1;checked.append({'name':file.name,'error':'FILE_TOO_LARGE'});continue
        try:
            with warnings.catch_warnings():
                warnings.simplefilter('error',Image.DecompressionBombWarning)
                with Image.open(file) as image:
                    width,height=image.size;fmt=image.format;image.verify()
            formats[fmt]+=1;small+=int(min(width,height)<600)
            checked.append({'name':file.name,'width':width,'height':height,'format':fmt})
        except Exception:
            invalid+=1;checked.append({'name':file.name,'error':'UNREADABLE_IMAGE'})
    return {'folder':alias,'files':len(files),'checked':len(checked),'unreadable':invalid,'below_600px':small,'skipped':skipped,
            'formats':dict(formats),'limited':len(files)>500}, {'files':checked}


def validate_workflow(workflow):
    nodes=workflow.get('nodes');connections=workflow.get('connections',{})
    if not isinstance(nodes,list) or not isinstance(connections,dict):
        raise ToolError('INVALID_WORKFLOW','El archivo no tiene estructura de workflow n8n')
    if not all(isinstance(x,dict) and isinstance(x.get('name'),str) for x in nodes):
        raise ToolError('INVALID_WORKFLOW','Hay nodos sin nombre válido')
    names=[x['name'] for x in nodes];known=set(names);duplicates=len(names)-len(known);dangling=0
    try:
        for source, channels in connections.items():
            dangling+=int(source not in known)
            for outputs in channels.values():
                for links in outputs:
                    for target in links:dangling+=int(target['node'] not in known)
    except (AttributeError,TypeError,KeyError):
        raise ToolError('INVALID_WORKFLOW','Estructura de conexiones n8n inválida')
    return {'nodes':len(nodes),'disabled':sum(bool(x.get('disabled')) for x in nodes),'duplicate_names':duplicates,'dangling_links':dangling}


def n8n_audit(alias):
    file=configured('workflows',alias)
    if not file.is_file() or file.stat().st_size>4*1024*1024:raise ToolError('FILE_LIMIT','Workflow inexistente o mayor de 4 MiB')
    try:data=json.loads(file.read_text(encoding='utf-8-sig'))
    except ValueError:raise ToolError('INVALID_JSON','El archivo del workflow no contiene JSON válido')
    result=validate_workflow(data)
    result['workflow']=alias
    if result['duplicate_names'] or result['dangling_links']:raise ToolError('WORKFLOW_LINKS','El workflow tiene nombres duplicados o conexiones a nodos inexistentes')
    return result,None


def apk_inventory(alias):
    folder=configured('projects',alias)
    output=folder/'app'/'build'/'outputs'/'apk'
    files=list(output.rglob('*.apk')) if output.is_dir() else []
    if len(files)>200:raise ToolError('FILE_LIMIT','Demasiados APK para esta consulta')
    files.sort(key=lambda p:p.stat().st_mtime,reverse=True)
    if not files:raise ToolError('APK_NOT_FOUND','No hay APK compilados para ese proyecto')
    file=files[0];info=file.stat()
    return {'project':alias,'apk_count':len(files),'latest':str(file.relative_to(folder)),'size_mb':round(info.st_size/1048576,1),
            'modified_utc':dt.datetime.fromtimestamp(info.st_mtime,dt.timezone.utc).isoformat(timespec='seconds')}, {'apks':[str(x.relative_to(folder)) for x in files]}


def dispatch(action,subject=''):
    if action not in ACTIONS or subject not in ACTIONS[action]:
        raise ToolError('INVALID_ARGUMENT','Acción o alias fuera de la lista permitida')
    functions={'vpn':vpn_status,'git':lambda:git_status(subject),'adb':adb_devices,'servicios':services_status,
               'imagenes':lambda:image_audit(subject),'n8n':lambda:n8n_audit(subject),'apks':lambda:apk_inventory(subject)}
    return functions[action]()


def main():
    try:
        if len(sys.argv) not in (2,3):raise ToolError('INVALID_ARGUMENT','Indica acción y un alias permitido')
        summary,details=dispatch(sys.argv[1],sys.argv[2] if len(sys.argv)==3 else '')
        result={'status':'success',**summary}
        if details is not None:
            reports=PRIVATE/'reports';reports.mkdir(parents=True,exist_ok=True)
            identifier=dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+uuid.uuid4().hex[:6]
            (reports/(identifier+'.json')).write_text(json.dumps(details,ensure_ascii=True,indent=2),encoding='utf-8')
            result['report_id']=identifier
        encoded=json.dumps(result,ensure_ascii=True,separators=(',',':'))
        if len(encoded)>400:raise ToolError('SUMMARY_LIMIT','El resumen excede 400 caracteres; reduce los datos configurados')
        print(encoded)
        return 0
    except ToolError as exc:
        print(json.dumps({'status':'error','code':exc.code,'message':str(exc)},ensure_ascii=True,separators=(',',':')),file=sys.stderr)
        return 1
    except Exception:
        print('{"status":"error","code":"INTERNAL_ERROR","message":"No se pudo completar la consulta local"}',file=sys.stderr)
        return 1

if __name__=='__main__':sys.exit(main())
