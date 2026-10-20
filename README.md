# Terminal de infraestructura en lenguaje natural

Android con LiteRT-LM, catálogo Go/SQLite y ejecución de scripts por SSH. El modelo corre en el Android servidor; una PC o la app cliente pueden usar su panel web sin instalar modelos.

## Qué dirección configurar

| Conexión | Formato del cliente | Ejemplo |
|---|---|---|
| Android → Tool Store del PC | `http(s)://IP-o-DNS:puerto` | `http://10.12.14.1:8000` |
| Android → nodo SSH | `host`, `user`, `port`, `shell` por separado | `pc-windows.vpn`, `agent`, `22`, `powershell` |
| PC/app cliente → Android servidor | `http://IP-DEL-ANDROID:11435` | `http://192.168.9.20:11435` |
| Escucha del Tool Store en el PC | variable `TOOLSTORE_ADDR`, sin esquema | `0.0.0.0:8000` |

Las IP son ejemplos: usa la del equipo que ejecuta cada servicio. Los accesos rápidos `10.12.14.1` y `192.168.9.1` conservan el puerto, pero no descubren dónde está el PC. No introduzcas `/24` en una URL. `127.0.0.1` significa **el propio dispositivo que hace la conexión**; solo alcanza el PC desde Android si configuraste una redirección ADB para pruebas. `0.0.0.0` sirve para escuchar, no como dirección de cliente.

## Configuración y uso

1. Ejecuta el Tool Store con una dirección accesible y un token: [README del catálogo](toolstore/README.md).
2. Instala los scripts en los nodos SSH y autoriza la clave pública del Android: [contrato de tools y salidas](remote-scripts/README.md).
3. Importa en Android la configuración de modelo, catálogo y hosts: [README de la app](app/README.md), [JSON de ejemplo](examples/agent-config.example.json).
4. Cambia el catálogo en **Terminal → Configurar Tool Store**. **Probar conexión** comprueba el borrador; **Guardar** lo aplica. El panel muestra la dirección guardada y los cambios pendientes. Importar otro JSON también aplica su `tool_store_url`.
5. Para acceder desde otro equipo, activa **Red → Iniciar acceso remoto** y utiliza la clave del panel: [acceso remoto y cliente Android](docs/REMOTE_ACCESS.md).

El token del Tool Store, la clave SSH y la clave de acceso remoto cumplen funciones distintas. Las credenciales reales se importan localmente y no se versionan.

## Formato de una tool

```json
{
  "name": "ping_windows",
  "description": "Comprueba conectividad mediante ping y devuelve latencia y pérdida de paquetes",
  "target_host": "pc-windows.vpn",
  "command_template": "C:\\AgentTools\\test-ping.ps1 \"{ip}\"",
  "parameters": {
    "type": "object",
    "properties": {"ip": {"type": "string", "description": "Dirección IP de destino"}},
    "required": ["ip"]
  }
}
```

El host debe coincidir exactamente con uno autorizado en Android. Los parámetros soportados son strings, con `required`, `enum` y `default`; los placeholders ocupan argumentos completos. El script se encuentra en el nodo SSH. Consulta [el formato completo, ejemplos POSIX/PowerShell y las restricciones](remote-scripts/README.md).

## Salida sugerida

Éxito: exit `0`, un objeto JSON en stdout:

```json
{"status":"success","destination":"192.0.2.10","successful":4,"packet_loss_pct":0,"rtt_avg_ms":2}
```

Error: exit distinto de `0`, un objeto JSON en stderr:

```json
{"status":"error","code":"HOST_UNREACHABLE","message":"El destino no respondió","retryable":true}
```

`status`, `code` y `retryable` son una convención recomendada; el worker exige un objeto JSON y usa el código de salida para elegir el flujo. Evita banners o trazas mezclados con el JSON. Prioriza resultados breves: al modelo llegan hasta **400 caracteres por resultado**; la salida SSH combinada tiene un límite de **32 KiB**. Los detalles extensos pueden guardarse en el nodo y devolverse como referencia. Más ejemplos: [README de scripts](remote-scripts/README.md).

## Compilación y documentación

Hay un paquete de [siete tools diarias para esta PC](docs/DAILY_PC_TOOLS.md): VPN, Git, ADB, servicios, imágenes de productos, validación n8n y APK recientes. El [catálogo importable](examples/daily-tools.windows.json) usa parámetros enumerados y configuración de rutas privada.

```powershell
.\gradlew.bat :app:assembleDebug :client:assembleDebug :app:testDebugUnitTest
```

- [Instalación y pruebas](docs/SETUP.md)
- [Conexión LAN/VPN y reformulación de búsquedas](docs/TOOLSTORE_NETWORK.md)
- [Administrador de acciones](docs/ADMIN.md)
- [Ejemplo ping Windows](docs/PING.md)
- [Pruebas en dispositivo](docs/DEVICE_TEST.md)
- [Revisión de exposición en GitHub](docs/SECURITY_REVIEW.md)

La reformulación se usa solo si la búsqueda no recupera herramientas. Sus palabras no cambian la instrucción ni el contexto de ejecución. El worker admite hasta tres ejecuciones y cuatro evaluaciones, y bloquea llamadas idénticas para evitar efectos duplicados.


Consulta de varios catálogos por PC, tokens propios y tolerancia a desconexiones: [Tool Stores federados](docs/FEDERATED_TOOLSTORES.md).
