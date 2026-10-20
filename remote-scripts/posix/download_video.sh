#!/usr/bin/env bash
set -euo pipefail
if ! command -v python3 >/dev/null 2>&1; then
  printf '%s\n' '{"status":"error","message":"python3 is required"}' >&2
  exit 1
fi
exec python3 - "$@" <<'PY'
import json, os, pathlib, subprocess, sys
from urllib.parse import urlparse
try:
    url = sys.argv[1] if len(sys.argv)>1 else ''
    mode = sys.argv[2] if len(sys.argv)>2 and sys.argv[2] else 'video_best'
    if urlparse(url).scheme not in ('https','http') or not urlparse(url).hostname:
        raise ValueError('Se requiere una URL HTTP(S) completa')
    if mode not in ('video_best','audio_mp3'):
        raise ValueError('Formato inválido')
    directory = pathlib.Path.home()/'Downloads'/'AgentDownloads'
    directory.mkdir(parents=True,exist_ok=True)
    flags = ['-x','--audio-format','mp3','--audio-quality','0'] if mode=='audio_mp3' else ['-f','bestvideo+bestaudio/best','--merge-output-format','mkv']
    result = subprocess.run(['yt-dlp',*flags,'--no-progress','--no-playlist','--quiet','--print','after_move:filepath',
        '-P',str(directory),'-o','%(title)s.%(ext)s','--',url],capture_output=True,text=True,timeout=110)
    if result.returncode:
        raise RuntimeError(result.stderr[-1200:].strip() or 'yt-dlp falló')
    files = result.stdout.strip().splitlines()
    if not files: raise RuntimeError('yt-dlp no devolvió la ruta de salida')
    print(json.dumps({'status':'success','file':files[-1],'location':str(directory)},ensure_ascii=False))
except Exception as exc:
    print(json.dumps({'status':'error','message':str(exc)},ensure_ascii=False),file=sys.stderr)
    sys.exit(1)
PY
