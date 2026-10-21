# Terminal de infraestructura

APK Kotlin/Compose con inferencia LiteRT-LM local, catálogo Go/SQLite y ejecución SSH de scripts Bash/PowerShell. **Terminal** importa la configuración cifrada y ejecuta intenciones. **Motor** conserva el servidor LLM/Ollama del proyecto original.

## Catálogo Go

Requiere Go 1.22+. SQLite usa un driver Go puro, sin CGO.

```powershell
cd toolstore
go mod download
$env:TOOLSTORE_TOKEN = 'un-token-largo-y-aleatorio'
# La dirección predeterminada es 127.0.0.1:8000.
# Para la tablet, especifica la IP LAN/VPN del servidor:
$env:TOOLSTORE_ADDR = '192.168.1.10:8000'
go run .
```

Abre esa dirección en el navegador, introduce el token y registra herramientas. El dashboard está embebido y funciona sin CDN. `store.db` se crea automáticamente; `TOOLSTORE_DB` permite cambiar la ruta. Para transportar credenciales HTTP utiliza una red privada/VPN o termina HTTPS delante del servicio.

Carga los ejemplos desde otra consola PowerShell, situada en `toolstore`:

```powershell
$headers = @{Authorization = 'Bearer un-token-largo-y-aleatorio'}
Get-Content ../examples/tools.json -Raw | ConvertFrom-Json | ForEach-Object {
    Invoke-RestMethod http://192.168.1.10:8000/api/tools -Method Post `
        -Headers $headers -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes(($_ | ConvertTo-Json -Depth 12)))
}
```

| Método | Ruta | Uso |
| --- | --- | --- |
| GET | `/healthz` | Salud SQLite y tipo de índice |
| GET | `/api/tools` | Listar |
| POST | `/api/tools` | Registrar |
| PUT | `/api/tools/{id}` | Editar conservando el ID |
| DELETE | `/api/tools/{id}` | Eliminar; 404 si no existe |
| POST | `/tools/search` | `{ "query": "estado Fedora", "top_k": 3 }` |

El alta admite `nombre`/`descripcion` y `name`/`description`. La búsqueda devuelve `name`, `description`, `transport`, `parameters`. Los nombres son únicos y K admite 1–3. El índice usa vectores dispersos TF-IDF con similitud coseno y normalización de acentos. Es recuperación léxica local; no usa embeddings neuronales ni recupera sinónimos sin coincidencias de palabras. Incluye el vocabulario de las intenciones en las descripciones.

Consulta [el administrador de acciones](ADMIN.md) para las plantillas, los parámetros visuales, la edición del catálogo y las opciones de acceso al panel.

## Nodos SSH

Activa OpenSSH y crea un usuario dedicado, por ejemplo `agent`, con permisos para sus scripts. Autoriza la clave pública Ed25519 de la tablet en `authorized_keys`.

Linux/Fedora/macOS, desde la raíz del repositorio:

```bash
sudo install -d /opt/agent-tools
sudo install -m 755 remote-scripts/posix/*.sh /opt/agent-tools/
```

Los scripts POSIX necesitan Python 3 para serializar JSON. El descargador necesita `yt-dlp` y `ffmpeg` en PATH. No hay IA en los nodos. La descarga tiene un límite de 110 segundos; usa un script que inicie un trabajo y otro que consulte su estado para descargas más largas.

Windows: copia `remote-scripts/windows/system_status.ps1` a `C:\AgentTools\system_status.ps1`. La cuenta SSH debe poder ejecutarlo según la política PowerShell del nodo. Android usa `powershell.exe -EncodedCommand`, independiente del shell predeterminado de OpenSSH.

Los scripts emiten un objeto JSON por stdout al terminar correctamente. Ante fallos emiten JSON por stderr y devuelven un código distinto de cero. Los parámetros son posicionales. Las plantillas admiten rutas, argumentos literales y placeholders completos como `"{url}"`; Android escapa cada argumento para el shell. Encapsula pipes, redirecciones y lógica de shell dentro del script, no en la plantilla.

## Tablet Android

1. Instala `app/build/outputs/apk/debug/app-debug.apk` en Android 8+ ARM64.
2. Copia un modelo **`.litertlm` con llamadas a herramientas** al directorio externo privado de la APK. No se incluyen pesos. Ruta de ejemplo: `/storage/emulated/0/Android/data/hn.fredi.inferencelocal/files/model.litertlm`.
3. Copia `examples/agent-config.example.json` y configura URL, token, ruta del modelo y usuarios/puertos/shells. Sustituye la clave privada y las claves públicas de host por valores reales. La clave privada se importa sin passphrase; su copia dentro de la APK se cifra con AES-GCM y Android Keystore.
4. Obtén las claves SSH de cada host y verifica los fingerprints por un canal de confianza antes de incorporarlas a `known_hosts`. Para puertos distintos de 22 usa `[host]:puerto`.
5. En **Terminal → Importar configuración JSON**, selecciona el archivo. Se guarda cifrado en `noBackupFilesDir`. Elimina la copia de importación en texto plano después.
6. Escribe **“Consulta el estado de Fedora”**. Los hosts del catálogo deben coincidir exactamente con la lista autorizada en tu configuración.

`backend` acepta `NPU`, `GPU` y `CPU`. NPU requiere un modelo compatible con el chip y las bibliotecas Qualcomm correspondientes. Ante un fallo NPU, selecciona GPU/CPU explícitamente: no hay fallback silencioso. Detén el servidor de la pestaña Motor antes de usar la terminal para evitar cargar dos modelos.

En **Motor → Engine terminal** puedes supervisar y configurar el modelo que usa la terminal. Ajusta ruta/modelo, backend, contexto (512–1984), temperatura, Top K, Top P y RAM mínima; guarda y pulsa **Cargar modelo**. La carga manual lo mantiene disponible entre intenciones hasta pulsar **Liberar modelo**. Sin carga manual, cada intención libera su modelo al terminar. La configuración se guarda en el almacenamiento privado y se utiliza realmente al crear el engine. Los cambios se bloquean mientras el modelo está cargado o hay una tarea activa. **Motor → Servidor API** conserva el monitor anterior para Ollama/OpenAI. Los dos servicios comparten una reserva de acelerador y no permiten cargar modelos simultáneamente.

El monitor de la terminal muestra estado, modelo y backend activos, contexto exacto, tiempo de carga, tokens de entrada/salida, evaluaciones, velocidad y TTFT del benchmark nativo, RAM JVM, RAM disponible y PSS del proceso. Los datos de la última inferencia permanecen visibles al liberar el modelo; no son estimaciones de tokens a partir de caracteres.

## Ejecución y límites

La terminal recupera hasta tres herramientas, crea una conversación nueva por evaluación y usa `OpenApiTool` con decodificación restringida y ejecución automática desactivada. Revalida nombre, tipos string, enum, campos requeridos, argumentos desconocidos y placeholders antes de SSH. Solo permite hosts autorizados y exige claves de host conocidas. La decisión final usa internamente `finish_transaction`.

Los resultados y códigos SSH se incorporan a la siguiente evaluación como datos. No hay historial persistente entre intenciones; solo el resultado visible permanece en memoria hasta la siguiente instrucción.

- Contexto nativo: 1984 tokens por defecto, configurable entre 512 y 1984. El límite previo por bytes evita catálogos enormes, pero no sustituye al tokenizador; el runtime puede rechazar un prompt que exceda el contexto.
- Cuatro evaluaciones y hasta tres ejecuciones; el último paso se reserva para responder.
- Se bloquean ejecuciones idénticas repetidas en la misma transacción. Si el modelo vuelve a proponer una ya realizada, el siguiente paso permite solo responder usando los resultados existentes.
- SSH: conexión 5 s, ejecución 120 s, salida combinada hasta 32 KiB.
- Transacción: 300 s. El contexto conserva hasta 400 caracteres por resultado, marcando truncamientos.
- Una transacción activa en la UI. El servicio foreground continúa al cambiar de pantalla y libera modelo y conexiones al terminar.
- Cancelar cierra el canal local; una operación remota ya iniciada podría continuar. Consulta su estado antes de repetirla.

La decodificación restringida depende del modelo y del runtime. Si no se obtiene exactamente una llamada válida, se detiene la transacción sin ejecutar texto libre. Los esquemas admiten parámetros string con `enum`, `default` y `required`; no se implementa toda la especificación JSON Schema.

## Compilación y pruebas

Para usar el Android como servidor desde otro equipo, consulta [Acceso remoto y cliente Android](REMOTE_ACCESS.md). La pestaña **Red** publica la interfaz web y permite obtener el APK cliente sin modelo.

La dirección del catálogo se cambia en **Terminal → Configurar Tool Store**, con accesos rápidos para LAN/VPN y reformulación opcional de búsquedas vacías. Consulta [Tool Store en la red](TOOLSTORE_NETWORK.md).

Requiere JDK 17+, SDK Android 36 y acceso a las dependencias Gradle.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
cd toolstore
go test ./...
go vet ./...
go build -o toolstore.exe .
```

En Windows, copia `toolstore/toolstore.env.example` como `toolstore/toolstore.env`, configura el token y ejecuta `toolstore/install-service.ps1 -Action Install` desde una consola elevada para registrar el catálogo como servicio automático. La base SQLite y los secretos permanecen fuera de Git.

Las pruebas cubren catálogo, autenticación, búsqueda/K, JSON adicional, placeholders, escape POSIX/PowerShell, feedback de ejecución, errores remotos y prevención de duplicados. La validación NPU/SSH en hardware necesita tu modelo, tablet y claves reales. El código alternativo de terminal vía HTTP local se conserva en `orchestrator/` como compatibilidad, sin exponerlo en la navegación principal.
