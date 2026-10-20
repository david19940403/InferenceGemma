# Tools por SSH: entradas, plantillas y salidas

Una tool es un script instalado en un nodo SSH. El catálogo guarda su nombre, descripción, host, ruta y esquema de entradas. Android recupera la descripción, genera argumentos validados y abre SSH usando su configuración privada.

## Conexión

El registro usa `target_host`, por ejemplo `pc-windows.vpn`. Android debe tener exactamente ese identificador en `hosts`:

```json
{"host":"pc-windows.vpn","user":"agent","port":22,"shell":"powershell"}
```

Para Linux/macOS utiliza `shell: "posix"`. El usuario, puerto y shell no forman parte de `target_host`; no escribas `ssh://agent@host:22`. La identidad del servidor se comprueba mediante `known_hosts`. La ruta del script existe en el nodo remoto, con permisos para esa cuenta. No se necesitan modelos en el nodo SSH.

El Tool Store puede estar en otro PC: cambiar su URL no cambia el host de las tools, sus claves ni el puerto SSH. `127.0.0.1` siempre es loopback del equipo que conecta. En el fixture de desarrollo se redirigen puertos con ADB; en una instalación real configura hosts LAN/VPN alcanzables desde Android.

## Plantillas admitidas

Ejemplos del campo `command_template`:

```text
C:\AgentTools\test-ping.ps1 "{ip}"
C:\AgentTools\system_status.ps1
/opt/agent-tools/system_status.sh
/opt/agent-tools/download_video.sh "{url}" "{formato}"
/opt/agent-tools/consultar.sh --host "{host}"
```

Los placeholders ocupan argumentos completos: `"{ip}"` es válido; `--host={ip}`, `prefijo{ip}` o una expresión PowerShell no lo son. El agente se encarga de citar los valores como datos para POSIX/PowerShell. No introduzcas pipes, `;`, redirecciones, variables o comandos encadenados; esa lógica va dentro de un script revisado. En la implementación actual los literales de las plantillas, incluidas las rutas, no admiten espacios aunque se rodeen de comillas: usa rutas como `C:\AgentTools\` o `/opt/agent-tools/`.

En JSON se escapan las barras de Windows y las comillas:

```json
{"command_template":"C:\\AgentTools\\test-ping.ps1 \"{ip}\""}
```

## Entradas y esquema

```json
{
  "type":"object",
  "properties":{
    "url":{"type":"string","description":"URL completa del video"},
    "formato":{"type":"string","enum":["video_best","audio_mp3"],"default":"video_best"}
  },
  "required":["url"]
}
```

La llamada generada contiene solamente argumentos declarados:

```json
{"url":"https://example.com/video","formato":"video_best"}
```

Actualmente se admiten parámetros **string**, `required`, `enum` y `default` string. No se implementa todo JSON Schema: no uses objetos anidados, listas o tipos numéricos esperando que el agente los valide. Un número puede viajar como string, por ejemplo `"count":"4"`; el script debe convertirlo y comprobar su rango. El agente rechaza argumentos desconocidos, strings mayores de 2048 caracteres y valores con NUL o saltos de línea. Los campos opcionales sin valor ni default se pasan como string vacío. `required` exige presencia: valida también que el valor no esté vacío si tu operación lo requiere.

Sin entradas:

```json
{"type":"object","properties":{},"required":[]}
```

## Contrato de salida

La salida consumida debe ser **un único objeto JSON UTF-8**, sin banners, prefijos, múltiples documentos o bloques Markdown.

| Resultado del proceso | Flujo que lee el worker | Contenido |
|---|---|---|
| Exit `0` | stdout | Objeto JSON con resultado de éxito. |
| Exit distinto de `0` | stderr | Objeto JSON con error o resultado parcial. |

El código de salida manda; `{"status":"error"}` con exit 0 no marca el proceso como fallido. Si sales con error y solo escribiste JSON en stdout, el worker intentará leer stderr y rechazará una salida vacía o no JSON. Los nombres de los campos son una convención, pero el objeto JSON es obligatorio.

Salida sugerida para una consulta:

```json
{"status":"success","hostname":"pc-windows","free_memory_bytes":4294967296,"disk_free_bytes":107374182400}
```

Salida sugerida para ping:

```json
{"status":"success","destination":"192.0.2.10","successful":4,"failed":0,"packet_loss_pct":0,"rtt_avg_ms":2}
```

Error sugerido, en stderr y con exit 1:

```json
{"status":"error","code":"HOST_UNREACHABLE","message":"El destino no respondió","retryable":true}
```

Resultado parcial, también con exit distinto de 0 para que no se declare éxito completo:

```json
{"status":"partial","destination":"192.0.2.10","successful":2,"failed":2,"packet_loss_pct":50}
```

Trabajo largo, después de confirmar que fue aceptado:

```json
{"status":"accepted","job_id":"job-001","state":"queued","message":"Trabajo registrado; consultar con la tool de estado"}
```

Un trabajo aceptado puede salir con exit 0: significa que se aceptó, no que ya terminó. Esa cola y una tool de consulta deben implementarse en el nodo; la app no crea una cola de trabajos remotos automáticamente.

### Tamaño, unidades y registro

- La salida SSH total, stdout más stderr, no debe exceder **32 KiB**.
- Para la siguiente evaluación solo se conserva hasta **400 caracteres del objeto JSON serializado** por ejecución; el worker indica si lo truncó. No es un límite de 400 bytes ni evita el límite de tokens del modelo.
- Pon primero `status`, código/error y los datos que deciden el siguiente paso. Usa claves de unidades claras: `_bytes`, `_ms`, `_pct`.
- Evita historiales grandes; devuelve un resumen. `test-ping.ps1` deja el historial detallado para `-IncludeHistory`.
- Los logs de diagnóstico extensos van a un archivo del nodo. No mezcles trazas o mensajes de progreso con el flujo JSON consumido.
- No incluyas claves, tokens o contraseñas en los resultados. Para detalles, devuelve una referencia o ruta que pueda consultar otra tool autorizada; no se abre ni se descarga automáticamente.

## Ejemplo POSIX funcional: espacio de disco

```bash
#!/usr/bin/env bash
set -euo pipefail
python3 - <<'PY'
import json, shutil, sys
try:
    usage = shutil.disk_usage('/')
    print(json.dumps({'status':'success','disk_free_bytes':usage.free,
                      'disk_total_bytes':usage.total}, separators=(',', ':')))
except Exception as exc:
    print(json.dumps({'status':'error','message':str(exc)}, separators=(',', ':')),
          file=sys.stderr)
    sys.exit(1)
PY
```

Guárdalo con finales LF y permiso de ejecución en el nodo. Los scripts POSIX del repositorio usan Python 3 para serializar valores sin escapes JSON manuales.

## Ejemplo PowerShell funcional: memoria

```powershell
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
try {
    $os = Get-CimInstance Win32_OperatingSystem
    [ordered]@{
        status = 'success'
        free_memory_bytes = [long]$os.FreePhysicalMemory * 1024
    } | ConvertTo-Json -Compress
    exit 0
} catch {
    [Console]::Error.WriteLine((@{
        status = 'error'
        message = $_.Exception.Message
    } | ConvertTo-Json -Compress))
    exit 1
}
```

Comprueba la compatibilidad con Windows PowerShell 5.1, porque el agente usa `powershell.exe`. `Test-Connection` difiere respecto a PowerShell 7; el [ping incluido](windows/test-ping.ps1) utiliza .NET Ping para ambas versiones.

## Límites de ejecución

SSH conecta en hasta 5 s; el comando tiene un límite de 120 s y la transacción completa, 300 s. El worker permite hasta tres ejecuciones y cuatro evaluaciones; puede usar hasta dos evaluaciones adicionales para reformular una búsqueda vacía. Las ejecuciones idénticas se bloquean. Cancelar cierra el canal local, pero una operación remota iniciada puede continuar: consulta su estado antes de repetirla.

[Ejemplos de catálogo](../examples/tools.json) · [Configuración de conexiones](../app/README.md) · [API del catálogo](../toolstore/README.md) · [Ejemplo de ping](../docs/PING.md)
