# Tool Store: catálogo HTTP en el PC

Servicio Go/SQLite con administrador web y búsqueda TF-IDF. El catálogo describe acciones; **no ejecuta SSH ni aloja el modelo LiteRT**. El Android ejecuta las acciones recuperadas contra sus hosts autorizados.

## Configuración y servicio de Windows

Copia `toolstore.env.example` como `toolstore.env` y define ahí la dirección, el token, el código del administrador y la base SQLite. El archivo `toolstore.env` es local y está excluido de Git; el proceso lo busca junto al ejecutable.

```powershell
Copy-Item toolstore.env.example toolstore.env
.\toolstore.exe
```

Para instalarlo como servicio real de Windows, abre PowerShell como administrador y ejecuta:

```powershell
.\install-service.ps1 -Action Install
```

El servicio usa `--service`, arranca automáticamente con Windows y conserva la base en `TOOLSTORE_DB`. Para revisar o detenerlo: `.\install-service.ps1 -Action Status`, `Stop` o `Uninstall`.

## Escucha y autenticación

```powershell
$env:TOOLSTORE_ADDR = '0.0.0.0:8000'
$env:TOOLSTORE_TOKEN = 'REEMPLAZAR_CON_UN_TOKEN_PRIVADO'
$env:TOOLSTORE_DB = 'C:\AgentData\store.db'
go run .
```

Ejecuta desde esta carpeta. La carpeta de la base de datos debe existir. `TOOLSTORE_ADDR` usa `IP:puerto`, sin `http://`; por defecto es `127.0.0.1:8000`, accesible solo en el PC. `0.0.0.0:8000` escucha en todas las interfaces IPv4: LAN y VPN. En Android se configura la IP real del PC, por ejemplo `http://10.12.14.1:8000` o `http://192.168.9.1:8000`, nunca `0.0.0.0` ni una dirección con `/24`.

Configura un token para uso en red y copia el mismo valor al archivo privado de Android. Las rutas de catálogo requieren `Authorization: Bearer TOKEN` cuando está configurado. El administrador también admite su propia sesión HttpOnly. Usa HTTP sobre LAN confiable/VPN o un proxy HTTPS. No expongas una base de datos ni un JSON de credenciales como archivos web.

## Formato de registro

`POST /api/tools` crea una acción. `PUT /api/tools/{id}` utiliza la misma estructura para editarla. Se aceptan `name`/`description` o los alias `nombre`/`descripcion`.

```json
{
  "name":"ping_windows",
  "description":"Comprueba si una IP responde a ping desde Windows; mide latencia y pérdida de paquetes",
  "target_host":"pc-windows.vpn",
  "command_template":"C:\\AgentTools\\test-ping.ps1 \"{ip}\"",
  "parameters":{
    "type":"object",
    "properties":{"ip":{"type":"string","description":"Dirección IPv4 o IPv6"}},
    "required":["ip"]
  }
}
```

`target_host` es el destino SSH, no la dirección del Tool Store. La ruta del script existe en ese destino. Usuario, puerto, clave y shell se toman del host autorizado en la app; no se cambian editando la URL del catálogo.

## Formato de búsqueda

Solicitud `POST /tools/search`:

```json
{"query":"comprueba conectividad mediante ping","top_k":3}
```

Respuesta:

```json
{
  "tools":[{
    "name":"ping_windows",
    "description":"Comprueba conectividad mediante ping",
    "transport":{
      "type":"ssh",
      "target_host":"pc-windows.vpn",
      "command_template":"C:\\AgentTools\\test-ping.ps1 \"{ip}\""
    },
    "parameters":{
      "type":"object",
      "properties":{"ip":{"type":"string"}},
      "required":["ip"]
    }
  }]
}
```

Sin coincidencias se devuelve `{"tools":[]}` con HTTP 200. K admite 1–3. La búsqueda devuelve descripciones, transportes y esquemas, no un resultado de ejecución. El formato puede incluir metadatos adicionales `user`/`port`, que el agente nativo no usa para reemplazar los hosts importados.

El índice compara palabras de nombre, descripción y host, con normalización de acentos. Describe la función con palabras que usarías al pedirla: «ping, conectividad, latencia», «estado, memoria, disco», etc. Las herramientas del catálogo no necesitan cargar modelos; la reformulación opcional de búsquedas vacías ocurre en Android y no altera la consulta usada al ejecutar.

| Método | Ruta | Función |
|---|---|---|
| GET | `/healthz` | Salud e índice; no comprueba SSH. |
| GET/POST | `/api/tools` | Listar / crear. |
| PUT/DELETE | `/api/tools/{id}` | Editar / eliminar. |
| POST | `/tools/search` | Recuperar hasta tres acciones. |

[Esquemas de parámetros, plantillas y salidas](../remote-scripts/README.md) · [Administrador](../docs/ADMIN.md) · [Configuración Android](../app/README.md)


Consulta de varios catálogos por PC, tokens propios y tolerancia a desconexiones: [Tool Stores federados](../docs/FEDERATED_TOOLSTORES.md).
