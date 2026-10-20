# App Android servidor: conexiones y configuración

La APK `app` ejecuta LiteRT-LM y las transacciones SSH. `client` es otra APK ligera que abre el panel del Android servidor; no utiliza modelos ni las claves SSH del servidor.

## Archivo importado en Terminal

Parte de [agent-config.example.json](../examples/agent-config.example.json). Es JSON UTF-8, de hasta 128 KiB. Ejemplo ilustrativo, con credenciales ficticias:

```json
{
  "tool_store_url": "http://10.12.14.1:8000",
  "token": "REEMPLAZAR_CON_TOKEN_DEL_TOOL_STORE",
  "model_path": "/storage/emulated/0/Android/data/hn.fredi.inferencelocal/files/gemma.litertlm",
  "backend": "GPU",
  "private_key": "<PEM>",
  "known_hosts": "pc-windows.vpn ssh-ed25519 REEMPLAZAR_CON_CLAVE_PUBLICA_DEL_SERVIDOR\n[linux.vpn]:2222 ssh-ed25519 REEMPLAZAR_CON_CLAVE_PUBLICA_DEL_SERVIDOR\n",
  "hosts": [
    {"host":"pc-windows.vpn","user":"agent","port":22,"shell":"powershell"},
    {"host":"linux.vpn","user":"agent","port":2222,"shell":"posix"}
  ]
}
```

| Campo | Formato / función |
|---|---|
| `tool_store_url` | URL base HTTP(S), sin rutas, CIDR, query, fragmento ni credenciales en la URL. |
| `token` | Mismo token que `TOOLSTORE_TOKEN` en el PC; se envía como bearer al catálogo. |
| `model_path` | Ruta absoluta de un modelo `.litertlm` legible por la app; no es una URL. |
| `backend` | `GPU`, `CPU` o `NPU`, según el modelo y el dispositivo. |
| `private_key` | Contenido completo de la clave privada Ed25519 del cliente SSH, con saltos `\n` en JSON; no es una ruta. No hay un campo de passphrase para claves cifradas. |
| `known_hosts` | Una o más líneas OpenSSH con las claves públicas de los servidores SSH, verificadas previamente. |
| `hosts` | Lista de destinos autorizados. `host` no incluye usuario, esquema, ruta ni puerto; estos van en campos separados. |

La clave pública correspondiente a `private_key` se instala en `authorized_keys` de la cuenta `user` del nodo. **No es la clave del servidor**: esa clave pública va en `known_hosts`. Para SSH en puerto 22 se usa `host ssh-ed25519 BASE64`; para otro puerto, `[host]:puerto ssh-ed25519 BASE64`. Comprueba la identidad del nodo antes de importar su clave: la app aplica `StrictHostKeyChecking=yes`.

`target_host` de cada tool debe coincidir exactamente con `hosts[].host`; que dos nombres resuelvan a la misma IP no los convierte en el mismo identificador autorizado. Los campos usuario/puerto del catálogo son metadatos: la conexión real se decide por `hosts` en Android.

La app cifra la configuración mediante Android Keystore en `noBackupFilesDir`. El JSON de importación contiene secretos: mantenlo fuera de Git y elimínalo de una carpeta compartida después de importarlo. Para cambiar el archivo de configuración completo se requiere liberar el modelo y finalizar la tarea activa.

## Dirección guardada, borrador y prueba

En **Terminal → Configurar Tool Store** puedes guardar otra URL sin reimportar las claves ni liberar un modelo inactivo. Ejemplos:

- `http://10.12.14.1:8000`
- `http://192.168.9.1:8000`
- `https://catalogo.vpn`

La app usa las rutas de Android; no fija el tráfico a Wi-Fi. Las IP anteriores son ejemplos y no incluyen `/24`. Los botones conservan el puerto actual.

**Dirección guardada** es el catálogo que se usará en la siguiente intención. El campo editable puede contener un borrador diferente. **Probar conexión** comprueba exactamente ese borrador y muestra la URL probada; **no lo guarda**. Pulsa **Guardar** para aplicarlo. El cambio también se refleja en el panel web. Al importar un JSON, su `tool_store_url` reemplaza esta preferencia.

`127.0.0.1` en Android apunta al propio Android. En el fixture de desarrollo funciona porque hay un `adb reverse`; fuera de esa prueba introduce la IP real del PC. El catálogo y los hosts SSH son destinos independientes: cambiar uno no cambia los otros.

## Ajustes del engine

Se editan desde **Motor → Engine terminal** o desde el panel web, con el modelo liberado. Formato del endpoint `PUT /remote/settings`:

```json
{
  "modelPath":"/storage/emulated/0/Android/data/hn.fredi.inferencelocal/files/gemma.litertlm",
  "backend":"GPU",
  "contextTokens":1984,
  "temperature":0.0,
  "topK":1,
  "topP":1.0,
  "minAvailableRamMb":512
}
```

Contexto: 512–1984 tokens; temperatura: 0–2; top K: 1–64; top P: mayor de 0 y hasta 1; RAM mínima: 256–8192 MiB. Estos nombres camelCase corresponden a los ajustes del engine, mientras que el archivo de importación usa `model_path` y `tool_store_url`.

## Panel y cliente remoto

Activa **Red** en el servidor y abre `http://IP-DEL-ANDROID:11435` desde otro equipo. La clave que muestra Red autentica el panel; **no es el token del catálogo**. El APK cliente pide esta URL del Android, no la URL del PC Tool Store. La web y el cliente comparten la configuración del servidor, sin guardar las claves SSH en el equipo cliente.

[API, autenticación y descarga del cliente](../docs/REMOTE_ACCESS.md) · [Búsqueda LAN/VPN](../docs/TOOLSTORE_NETWORK.md) · [Formato de tools y salidas](../remote-scripts/README.md)


Consulta de varios catálogos por PC, tokens propios y tolerancia a desconexiones: [Tool Stores federados](../docs/FEDERATED_TOOLSTORES.md).
