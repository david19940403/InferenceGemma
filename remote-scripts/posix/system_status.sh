#!/usr/bin/env bash
set -euo pipefail
if ! command -v python3 >/dev/null 2>&1; then
  printf '%s\n' '{"status":"error","message":"python3 is required"}' >&2
  exit 1
fi
exec python3 - <<'PY'
import json, os, platform, shutil, sys
try:
    disk = shutil.disk_usage(os.path.expanduser('~'))
    print(json.dumps({'status':'success','hostname':platform.node(),'os':platform.system(),
        'release':platform.release(),'load_average':list(os.getloadavg()),
        'disk_total_bytes':disk.total,'disk_free_bytes':disk.free}, ensure_ascii=False))
except Exception as exc:
    print(json.dumps({'status':'error','message':str(exc)}), file=sys.stderr)
    sys.exit(1)
PY
