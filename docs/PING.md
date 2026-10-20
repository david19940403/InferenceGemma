# Acción ping para Windows

La plantilla `C:\desarrollo\InferenceGemma\remote-scripts\windows\test-ping.ps1 "{ip}"` es válida. `{ip}` es un argumento completo y corresponde al parámetro posicional `Destination` del script. La ruta debe existir en el equipo que recibe SSH, no en Android.

El script inicial usaba `Test-Connection -Size ... -Timeout ...`. Windows PowerShell 5.1 no admite esos parámetros: ofrece `BufferSize` y no tiene `Timeout`. PowerShell 7 también tiene diferencias de nombres, unidades y propiedades. El `catch` interno ocultaba la excepción y el resumen devolvía `status=success` y exit 0 incluso con cero respuestas.

Se cambió a `System.Net.NetworkInformation.Ping.Send`, compatible con ambas versiones. Valida límites de intentos, tamaño, timeout e intervalo. El resumen devuelve éxito solo si todos los intentos responden; una pérdida parcial o total devuelve un exit distinto de cero y JSON en stderr, como espera el agente. El resumen habitual cabe en los 400 caracteres de feedback. Para diagnosticar cada intento usa `-IncludeHistory` al ejecutar el script directamente.

```powershell
powershell.exe -NoProfile -File .\remote-scripts\windows\test-ping.ps1 -Destination 127.0.0.1
powershell.exe -NoProfile -File .\remote-scripts\windows\test-ping.ps1 -Destination 127.0.0.1 -IncludeHistory
pwsh.exe -File .\integration\ping-smoke.ps1
```

El catálogo usa un parámetro string obligatorio `ip`. El script recibe la IP como dato, sin evaluarla como código. El Android decide usuario, puerto, shell y clave SSH desde `hosts` de la configuración cifrada; los campos usuario/puerto del catálogo no reemplazan esa autorización. En esta prueba se alinearon a `agent:22222`.

También se reforzó la evaluación del agente: después de recibir resultados se le pide responder sin repetir la llamada. Si propone una ejecución idéntica, esta se bloquea y la siguiente evaluación ofrece únicamente `finish_transaction`, conservando el resultado. La última de las cuatro evaluaciones también se reserva exclusivamente para responder.

El fixture de desarrollo inicialmente autorizaba únicamente `system_status.ps1`. Ahora permite también el script fijo `test-ping.ps1` con exactamente una IP IPv4 o IPv6 válida. Continúa escuchando solo en loopback y rechaza comandos arbitrarios, argumentos adicionales y nombres de ejecutables no autorizados. Conserva la identidad del cliente, el token y una clave de host persistida en `.cache/`; al actualizar desde la primera versión hubo que reimportar la configuración para confiar en la nueva identidad del host.

Este fixture y las redirecciones ADB son solo el entorno de pruebas. Para usarlo sin ADB, configura el host SSH real de Windows, su puerto, usuario y clave conocida en Android, y cambia el destino de la acción a ese host autorizado.
