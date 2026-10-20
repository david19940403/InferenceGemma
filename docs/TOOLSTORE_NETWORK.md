# Tool Store en LAN y VPN

En Android abre **Terminal → Configurar Tool Store**. También está disponible en el panel web, dentro de **Tool Store en la red**, y por tanto en la app cliente.

Introduce la dirección del PC que ejecuta el catálogo, incluyendo esquema y puerto. Los accesos rápidos preparan `10.12.14.1` y `192.168.9.1`, conservando el puerto de la dirección actual. Después pulsa **Guardar**. **Probar conexión** verifica una consulta autenticada al catálogo, sin cargar el modelo ni ejecutar scripts; puede probar una dirección antes de guardarla.

`10.12.14.1/24` y `192.168.9.1/24` describen interfaces/redes. En el campo se introduce una dirección de servidor, por ejemplo `http://10.12.14.1:8000`, sin `/24`. Si el PC usa otra IP de ese rango, cambia el último número. El acceso rápido `.1` solo será correcto cuando el Tool Store esté realmente en ese equipo.

No se fija una interfaz Wi-Fi ni se restringen subredes en la app: se utilizan DNS y las rutas que Android tenga para la LAN o VPN activa. Cambiar la dirección no configura la VPN, no escanea la red y no elige servidores desconocidos automáticamente. La nueva dirección se usa al comenzar la siguiente intención. No requiere liberar el modelo, pero se bloquean los cambios mientras haya una operación activa o pendiente.

El catálogo principal usa el token de la configuración cifrada importada. Para otro catálogo con una clave diferente, pulsa **Agregar PC / catálogo**, desactiva **Usar token importado** e introduce su token propio. Puedes mantener ambos activos. Importar un JSON actualiza la dirección principal y conserva los catálogos adicionales. Los ajustes y tokens propios quedan cifrados mediante AndroidKeyStore en `noBackupFilesDir`; las claves SSH permanecen en ConfigVault. Consulta [Tool Stores federados](FEDERATED_TOOLSTORES.md) para el formato completo.

## Por qué puede aparecer 127.0.0.1

La dirección visible procede de la configuración guardada, no se fuerza a loopback. En el entorno de desarrollo se guardó `http://127.0.0.1:18000` para usar `adb reverse`. Probar otro destino no sustituye ese valor: hay que pulsar **Guardar**. Importar un JSON antiguo que contenga loopback también vuelve a aplicarlo.

La interfaz muestra ahora **Dirección guardada**, el borrador y los cambios pendientes por separado. El resultado de una prueba incluye la URL que se comprobó y recuerda que probar no guarda. El panel obtiene la dirección actual en cada actualización de estado; un cambio desde Android u otro navegador se refleja sin recargar. Si estás editando un borrador, se conserva y se señala que todavía no está aplicado.

No confundas esa dirección con `target_host` en **Hosts del catálogo**: ese campo indica dónde se ejecuta el script por SSH. Cambiar la dirección del Tool Store no modifica los destinos SSH registrados en cada acción. Consulta los formatos completos en los READMEs de [Android](../app/README.md), [Tool Store](../toolstore/README.md) y [scripts](../remote-scripts/README.md).

## Configuración del PC

El Tool Store escucha en loopback de forma predeterminada. Para acceder desde otras interfaces del PC, usa una dirección de escucha apropiada y el mismo token configurado en Android:

```powershell
$env:TOOLSTORE_ADDR = '0.0.0.0:8000'
$env:TOOLSTORE_TOKEN = 'REEMPLAZAR_CON_EL_TOKEN_CONFIGURADO_EN_ANDROID'
.\toolstore.exe
```

`0.0.0.0` permite escuchar en las interfaces IPv4 del PC, incluidas LAN y VPN. Es una dirección de escucha: en Android se utiliza la IP real del PC. El firewall y las rutas de la VPN deben permitir el puerto elegido. HTTP es adecuado para una red de confianza o una VPN; usa HTTPS si tienes un proxy TLS. El cliente no sigue redirecciones al enviar el token.

## Búsquedas alternativas con LiteRT

La opción **Reformular la búsqueda si no encuentra herramientas** está activada por defecto y se puede desactivar.

1. Se consulta el catálogo con la instrucción original.
2. Solo si la respuesta válida contiene cero herramientas, el modelo local propone términos técnicos o sinónimos en español/inglés.
3. Se hacen como máximo dos reformulaciones y dos consultas adicionales, rechazando propuestas repetidas, vacías o mayores de 512 bytes.
4. El worker recibe únicamente las herramientas recuperadas y la instrucción original. Las palabras alternativas no se agregan al prompt, historial, argumentos ni resultados de ejecución.

Cada reformulación usa una conversación nueva sin herramientas remotas ejecutables. El motor conserva los pesos, pero cierra esa conversación antes de crear las de ejecución. No se descarga otro modelo ni se mantiene un segundo engine. El límite de cuatro evaluaciones del worker sigue aplicando a la ejecución; la búsqueda puede añadir hasta dos evaluaciones previas. El límite global de tiempo de la transacción sigue siendo de 300 segundos.

Los catálogos disponibles se consultan en paralelo. Un fallo de conexión o un token rechazado afecta solo a ese catálogo; si todos fallan, no se interpreta como una búsqueda vacía. Los fallos del modelo se propagan y una respuesta del modelo inválida se descarta. Si tampoco se encuentran herramientas tras los intentos válidos, no se ejecuta ninguna acción. Este mecanismo mejora la recuperación por palabras clave del Tool Store actual; no sustituye su índice TF-IDF por embeddings ni garantiza encontrar una acción que no exista.

## API del panel remoto

Las tres rutas requieren la autenticación habitual del panel y no devuelven el token:

| Ruta | Función |
|---|---|
| `GET /remote/toolstore` | Devuelve `url` y `rewriteSearch` |
| `PUT /remote/toolstore` | Guarda `{"url":"http://10.12.14.1:8000","rewriteSearch":true}` |
| `POST /remote/toolstore/test` | Prueba esa misma estructura sin guardar cambios |

## Verificación

En el Samsung SM-S928U se guardó una dirección del PC desde el diálogo nativo y se probó el catálogo por Wi-Fi, sin redirección ADB para ese acceso. Una instancia temporal del Tool Store usó la misma base de datos y token, sin modificar las acciones del catálogo.

La consulta `Averigua conectividad hacia ::1` devolvía cero herramientas directamente. Con la opción desactivada no hubo evaluaciones del modelo. Al activarla, LiteRT recuperó la acción de ping mediante reformulación y el worker respondió con el resultado real, manteniendo la consulta original. La prueba también guardó ambas direcciones rápidas con el modelo cargado, sin realizar conexiones a ellas: no se afirma haber validado un Tool Store real en esas dos IP.

Pasaron 17 pruebas unitarias Android, incluidas pruebas de aislamiento de contexto, límites, errores, cancelación y direcciones. Playwright verificó los accesos rápidos, guardado, prueba de conexión y diseño móvil/escritorio. `integration/toolstore-network-smoke.cjs` repite la prueba con `REMOTE_BASE` y `NETWORK_CATALOG_URL`; requiere el fixture SSH y las credenciales locales de prueba. Al terminar se restauró la dirección original y se cerró el servidor temporal. Las evidencias están en `.cache/toolstore-network-test/` y no se publican.


Consulta de varios catálogos por PC, tokens propios y tolerancia a desconexiones: [Tool Stores federados](FEDERATED_TOOLSTORES.md).
