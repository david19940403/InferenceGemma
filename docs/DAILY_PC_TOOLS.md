# Tools de uso diario para esta PC

Acciones basadas en el uso de WireGuard, desarrollo Android con ScanFlex/posandroid, Git, n8n/TakePOS e imágenes de productos para PrestaShop. Consultan estado o archivos locales: no despliegan, instalan APK, modifican productos, cambian VPN ni ejecutan workflows.

## Acciones registradas

| Tool | Parámetros string | Ejemplo de intención |
|---|---|---|
| `diagnostico_vpn_pc` | Ninguno | «¿Qué VPN tiene esta PC y qué IP usa en 10.12.14 y 192.168.9?» |
| `estado_git_proyecto` | `proyecto`: `inferencegemma`, `scanflex`, `posandroid`, `prestashop` | «Revisa la rama y los cambios Git pendientes de ScanFlex». |
| `dispositivos_android_adb` | Ninguno | «¿Qué Android o terminales POS están conectados por ADB?» |
| `servicios_desarrollo_pc` | Ninguno | «Revisa los servicios de desarrollo y puertos locales de esta PC». |
| `auditar_imagenes_productos` | `carpeta`: `productos` | «Revisa los formatos y dimensiones de las imágenes de productos para PrestaShop». |
| `validar_workflow_n8n` | `workflow`: `aperturas_takepos` | «Valida el JSON y las conexiones del workflow de aperturas TakePOS». |
| `localizar_apk_proyecto` | `proyecto`: `inferencegemma`, `scanflex`, `posandroid` | «¿Cuál es el APK más reciente compilado de ScanFlex?» |

Las IP de VPN se consultan al ejecutar: no son resultados fijos. La consulta Git devuelve rama y cantidades de cambios, sin diffs, contenidos ni URLs remotas. ADB solo enumera dispositivos. Los puertos consultados son 22, 8000, 18000, 5678, 3306, 5432 y 8080; un puerto abierto no demuestra que la aplicación esté sana. Docker se informa como proceso local, sin inspeccionar entornos de contenedores.

Las imágenes se comprueban en una carpeta configurada, sin recursión, hasta 500 archivos y 25 MiB por imagen; no se modifican ni se suben. Se cuentan las inferiores a 600 px en alguna dimensión como referencia, no como productos inválidos. El workflow se valida como JSON local de hasta 4 MiB: cuenta nodos, duplicados y conexiones a nodos inexistentes. No consulta la base de datos, no verifica horarios de producción y no extrae ni devuelve credenciales.

Al preparar estas tools, el proyecto de PrestaShop no tenía un repositorio Git accesible y posandroid no tenía APK compilados. Esas consultas devuelven errores explícitos hasta que existan los recursos; no se crean ni se compilan automáticamente.

## Implementación y configuración local

El entrypoint es [daily-tools.ps1](../remote-scripts/windows/daily-tools.ps1), que ejecuta [daily_tools.py](../remote-scripts/windows/daily_tools.py). Requiere Windows PowerShell 5.1, Python 3, Git y ADB en PATH; la auditoría de imágenes requiere Pillow. Estos componentes estaban disponibles en esta PC. La herramienta selecciona el primer Python del PATH.

Los parámetros son alias enumerados, no rutas libres ni fragmentos de shell. Las rutas personales se guardan en `.cache/daily-tools/settings.json`, excluido de Git. Se configuraron localmente la carpeta de imágenes utilizada en el trabajo de productos y el JSON de aperturas TakePOS. No se copiaron credenciales ni contenido de otros chats al código.

Los proyectos predeterminados se resuelven desde el repositorio, su carpeta padre y `%USERPROFILE%`. Puedes sobrescribirlos en la configuración privada:

```json
{
  "projects": {
    "scanflex": "%USERPROFILE%\\AndroidStudioProjects\\ScanFlex",
    "prestashop": "C:\\Proyectos\\prestashop"
  },
  "image_folders": {"productos": "%USERPROFILE%\\Downloads\\productos"},
  "workflows": {"aperturas_takepos": "%USERPROFILE%\\Documents\\n8n\\aperturas.json"}
}
```

Se puede partir de [daily-tools.settings.example.json](../examples/daily-tools.settings.example.json). Si copias los dos scripts fuera del repositorio, su carpeta se convierte en la raíz de configuración; `INFERENCE_TOOLS_ROOT` permite indicar otra raíz. Configura entonces los alias de los proyectos. El nombre y ubicación de ambos scripts se mantienen juntos; el llamador remoto no puede cambiar el archivo Python a ejecutar.

## Salidas y detalles

Cada consulta devuelve un objeto JSON compacto de hasta 400 caracteres para el agente. Errores: objeto JSON en stderr y exit 1. Las consultas con más detalle crean un informe privado en `.cache/daily-tools/reports/REPORT_ID.json`, y devuelven `report_id`. Esos informes no se publican ni se leen automáticamente desde Android.

Los informes de imágenes guardan nombres y dimensiones; los de ADB contienen identificadores de los dispositivos. El JSON completo de n8n y sus credenciales no se incluyen en los informes. La configuración y los informes se conservan en la PC y están excluidos de Git.

## Ejecución directa y registro

Desde la raíz del repositorio:

```powershell
powershell.exe -NoProfile -File remote-scripts/windows/daily-tools.ps1 vpn
powershell.exe -NoProfile -File remote-scripts/windows/daily-tools.ps1 git scanflex
powershell.exe -NoProfile -File remote-scripts/windows/daily-tools.ps1 imagenes productos
powershell.exe -NoProfile -File remote-scripts/windows/daily-tools.ps1 n8n aperturas_takepos
```

El catálogo de ejemplo es [daily-tools.windows.json](../examples/daily-tools.windows.json). Para registrar en otro entorno, utiliza la IP/host SSH real de esta PC y la ruta de su script. Con `TOOLSTORE_TOKEN` ya configurado en la consola:

```powershell
python integration/register-daily-tools.py --catalog-url http://10.12.14.1:8000 --host pc-windows.vpn --script-path C:\AgentTools\daily-tools.ps1 --ssh-user agent --ssh-port 22
```

También admite `--token-file` con un archivo privado. No sigue redirecciones HTTP. Conserva los registros cuyo nombre ya exista; `--update-existing` permite actualizarlos explícitamente. El registro no edita los archivos privados del Android: `--host` debe coincidir con un destino autorizado allí.

En este entorno se registraron siete acciones nuevas, conservando las dos existentes. Usan el host `127.0.0.1`, usuario `agent`, puerto 22222 y la redirección ADB del fixture de esta PC. El fixture conserva sus claves y acepta exclusivamente los scripts, acciones y alias enumerados. Esto no convierte loopback en una conexión LAN: para funcionar sin ADB debes usar un servidor SSH real de la PC y configurar su host y clave conocida en Android.

## Verificación

- Cinco pruebas Python: rechazo de acciones/rutas no permitidas, estructura n8n, protección de datos sensibles, resumen Git y auditoría de imágenes sin modificar entradas.
- Pruebas Go de la política de comandos y una prueba SSH real con la clave del host fijada, para las siete acciones y un argumento rechazado.
- Ejecución de las siete acciones con Windows PowerShell 5.1 y resúmenes dentro de 400 caracteres.
- Una intención real desde Android recuperó la acción de VPN, la ejecutó por SSH y respondió con las dos IP vigentes de esta PC.

```powershell
python -m unittest discover -s integration -p test_daily_tools.py -v
```

La prueba Go de hardware se habilita con `DAILY_SSH_TEST_CONFIG` apuntando al JSON privado del fixture; sin esa variable se omite. Las evidencias locales están en `.cache/daily-tools/`.
