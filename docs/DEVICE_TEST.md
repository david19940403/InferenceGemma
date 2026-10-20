# Prueba en dispositivo · 20 de octubre de 2026

Dispositivo: Samsung Galaxy S24 Ultra (SM-S928U), conectado por el servicio ADB `adb-R5CX105DXPE-B0P55p._adb-tls-connect._tcp.`.

Resultado: **prueba de extremo a extremo completada** con el modelo existente `gemma-4-E2B-it.litertlm` y backend **GPU**. NPU no se validó en esta prueba.

Flujo comprobado:

1. Instalación del APK y arranque de `MainActivity`, sin errores AndroidRuntime.
2. Importación del JSON desde el selector de documentos Android y almacenamiento cifrado en `no_backup/infrastructure.enc`.
3. Recuperación de `estado_windows_prueba` desde Tool Store por HTTP autenticado.
4. Selección de herramienta mediante inferencia local y decodificación restringida.
5. Verificación de clave de host y autenticación SSH Ed25519.
6. Ejecución real del script `remote-scripts/windows/system_status.ps1` en el PC, con exit 0.
7. Segunda evaluación del modelo y respuesta visible con el estado de Windows, memoria y disco.
8. Cierre de la transacción y liberación del modelo. PSS de la app después de terminar: aproximadamente 267 MiB.

Se corrigió durante la prueba la negociación Ed25519 de JSch en Android usando el proveedor ligero de Bouncy Castle. Compilación y pruebas Kotlin se repitieron después del cambio y pasaron.

## Entorno aislado utilizado

- Catálogo: `127.0.0.1:18000`, base separada `.cache/device-test/store.db`.
- Fixture SSH: `127.0.0.1:22222`, implementación en `integration/sshnode`.
- Conectividad desde Android: túneles `adb reverse` para esos dos puertos.
- Claves y token: generados aleatoriamente para esta prueba, fuera de Git.
- La fixture valida una sola orden exacta y ejecuta únicamente el script fijo de consulta de estado; no ejecuta texto de shell recibido.

El modelo se copió desde Descargas al directorio privado externo de la APK. El archivo original se conservó. La copia de importación con las claves temporales se eliminó de Descargas tras importarla. El catálogo y la fixture permanecen activos en esta sesión para repetir la consulta; requieren este PC y los túneles ADB. Esta configuración es de prueba, no una conexión a los nodos de producción.

## Repetir la preparación

Compila la fixture desde `integration/sshnode` con `go build -o ../../.cache/device-test/sshnode.exe .` y ejecútala desde la raíz del proyecto:

```powershell
.\.cache\device-test\sshnode.exe -dir .cache/device-test -script remote-scripts/windows/system_status.ps1
```

Genera un nuevo `smoke-config.json` y un archivo `token` en el directorio privado indicado. Reiniciar la fixture rota las claves: vuelve a importar el JSON en Android y usa el nuevo token en Tool Store. La fixture solo escucha en loopback. El script de consulta y la orden registrada deben coincidir exactamente con la ruta absoluta del repositorio.

Las capturas y evidencias locales están en `.cache/device-test/`, ignorado por Git. La captura final es `result.png`.
