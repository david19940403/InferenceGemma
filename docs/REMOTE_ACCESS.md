# Acceso remoto desde PC y Android

El Android con el modelo funciona como servidor. Otro equipo utiliza la página web o el APK `Inference Client`; no necesita LiteRT, modelos ni claves SSH. La terminal, las conexiones y la inferencia permanecen en el servidor.

## Conectar

1. En la app del servidor abre **Red → Iniciar acceso remoto**. No hace falta cargar antes el modelo.
2. Copia una de las direcciones mostradas, por ejemplo `http://192.168.0.118:11435`. El puerto se puede cambiar con el servicio detenido.
3. Desde una PC o un Android en esa red abre la dirección e introduce la **clave de acceso** que aparece en Red.
4. Para usar la app cliente en otro Android, descarga **Cliente Android** desde la página e instala el APK. Abre Inference Client, introduce la dirección del servidor y después su clave.

La dirección cambia si cambia la IP del teléfono. El servicio escucha en todas las interfaces IPv4, incluidas las de una VPN. No configura el router ni publica el dispositivo en Internet. HTTP debe usarse en una red de confianza o sobre una VPN. Todos los clientes autorizados comparten permisos para ejecutar las acciones configuradas, leer resultados y controlar el engine.

El servidor permanece como servicio foreground mientras está habilitado, incluso al salir de su pantalla. Android puede limitar la conectividad con ahorro de energía; para uso continuo conviene mantener el dispositivo alimentado y permitir actividad en segundo plano. Detener **acceso remoto** cierra el puerto; **Liberar** descarga el modelo; **Cancelar tarea** solicita cancelar la operación. Son controles independientes.

## Funciones

- Enviar instrucciones a la terminal existente; no se expone un endpoint de comandos shell libres.
- Consultar progreso, resultado, backend, estado, TPS, TTFT y RAM.
- Cargar, liberar y cancelar operaciones del engine de la terminal.
- Cambiar modelo, backend, contexto, muestreo y RAM mínima con el modelo liberado.
- Configurar la dirección del Tool Store, probar conexión y activar la reformulación de búsquedas vacías; consulta [LAN/VPN y búsquedas](TOOLSTORE_NETWORK.md).
- Mantener una única operación activa entre los clientes y la terminal local. El servidor API original conserva la exclusión del modelo nativo.

El cliente usa una cookie HttpOnly, SameSite Strict, con duración de ocho horas. La clave no se guarda en localStorage ni se incluye en enlaces. La configuración de red se cifra con Android Keystore. Al detener/reiniciar el servicio se invalidan las sesiones; generar otra clave requiere detenerlo primero. No se importan ni se devuelven claves SSH a través de esta interfaz.

## API

### Sesion recordada y monitor en Android

El cliente guarda la sesion de WebView en disco y la recupera al reabrir la app.
Mientras la sesion siga vigente (ocho horas), no vuelve a solicitar la clave.
Revocar, purgar o reiniciar el acceso remoto invalida las sesiones y requiere
autenticarse de nuevo. El cliente no guarda la clave maestra para eludir una revocacion.

La pestaña Red del Android servidor muestra el monitor de conexiones: IP,
actividad reciente, ultima actividad y cliente. Permite revocar una sesion o
purgar todas, con confirmacion. Se actualiza cada segundo.

Las rutas `/remote/status`, `/remote/settings`, `/remote/intents`, `/remote/engine` y `/remote/cancel` exigen `Authorization: Bearer <clave>` o una sesión iniciada con `POST /remote/session`. Los navegadores deben utilizar el mismo origen; no se habilita CORS.

| Ruta | Uso |
|---|---|
| `GET /` | Interfaz web |
| `GET /remote/health` | Disponibilidad sin datos privados |
| `GET /remote/connections` | Monitor de sesiones activas; nunca devuelve tokens |
| `DELETE /remote/connections/{id}` | Revocar una sesion especifica |
| `POST /remote/connections/purge` | Revocar todas las sesiones activas |
| `GET /client.apk` | APK cliente incluido en la compilación debug del servidor |
| `POST /remote/session` | Iniciar sesión usando la clave en Authorization |
| `POST /remote/logout` | Cerrar la sesión del navegador |
| `GET /remote/status` | Engine, terminal, operación actual y memoria |
| `GET /remote/settings` | Configuración y modelos instalados |
| `PUT /remote/settings` | Guardar EngineSettings validado |
| `POST /remote/intents` | `{"query":"Consulta el estado de Windows","request_id":"identificador-unico"}` |
| `POST /remote/engine` | `{"action":"load","request_id":"identificador-unico"}`; acción `load` o `unload` |
| `POST /remote/cancel` | Solicitar cancelación |

Se responde `202` al aceptar una operación y `409` si otra está ocupando el engine. Reenviar el identificador de la **operación actual** devuelve esa misma operación, sin ejecutarla de nuevo. Solo se conserva la operación actual en memoria: esta protección no es un historial persistente y no cubre reinicios o identificadores antiguos tras aceptar otra operación. El cliente no reintenta automáticamente solicitudes de ejecución ante una desconexión. Consulta el estado antes de repetir una intención, especialmente si produce efectos.

## Compilar

```powershell
.\gradlew.bat :app:assembleDebug :client:assembleDebug :app:testDebugUnitTest
```

- Servidor: `app/build/outputs/apk/debug/app-debug.apk`.
- Cliente independiente: `client/build/outputs/apk/debug/client-debug.apk` (aproximadamente 830 KiB).

El cliente no depende de LiteRT, Ktor ni SSH. El APK debug del servidor incluye automáticamente el cliente para descargarlo desde la red. En release los APK se distribuyen por separado; no se incorpora un cliente firmado con una clave debug.

La prueba `integration/remote-smoke.cjs` usa `REMOTE_BASE`, una clave en `.cache/remote-test/access-token`, Playwright instalado en `.cache/admin-tests/node_modules` y Chrome. Ejecuta una intención real de consulta del estado de Windows: requiere el catálogo y el host SSH de pruebas configurados. También comprueba autenticación, origen, exclusión, validación, integridad del APK y las vistas móvil/escritorio.
