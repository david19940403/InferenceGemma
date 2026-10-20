# Administrador de acciones

El panel integrado en Tool Store permite:

- Crear acciones desde cero o con plantillas para Windows, Linux/macOS y descarga de videos.
- Editar acciones conservando su ID y duplicarlas antes de guardar una variante.
- Definir parámetros de texto con descripción, valores permitidos, valor predeterminado y obligatoriedad. También admite edición JSON.
- Buscar, filtrar por host y ordenar el catálogo.
- Ver los hosts referenciados y las acciones asociadas. Esto no comprueba la disponibilidad SSH de esos equipos.
- Probar qué herramientas recuperaría una instrucción, sin ejecutar scripts.
- Exportar el catálogo a JSON y eliminar acciones con confirmación.

La interfaz funciona sin dependencias de CDN y se adapta a escritorio y móvil. El alta y la edición validan el esquema y los placeholders. Las claves SSH se administran en Android, no en el panel.

## Acceso

Configura `TOOLSTORE_TOKEN` en el servidor e introdúcelo en **Configurar acceso**. La opción para recordar el token utiliza almacenamiento de sesión de la pestaña, no almacenamiento permanente. El botón **Desconectar** elimina el token de la pestaña y cierra cualquier sesión del administrador.

Para que el operador local pueda abrir el panel sin copiar el token de la API, el servidor admite la variable opcional `TOOLSTORE_ADMIN_CODE`. Debe contener un valor aleatorio secreto. La URL `/auth/bootstrap?code=VALOR` lo consume una sola vez, únicamente desde loopback, crea una cookie HttpOnly/SameSite=Strict válida durante ocho horas y redirige a `/`. El token de la API no aparece en esa URL. Reiniciar el servidor invalida las sesiones. Deja la variable sin definir para deshabilitar este mecanismo.

Las solicitudes API con un encabezado Authorization explícito siempre validan ese encabezado, aunque el navegador tenga una sesión de administrador válida. La APK conserva la autenticación Bearer existente.

## API de edición

`PUT /api/tools/{id}` recibe los mismos campos que el alta, devuelve la acción actualizada y conserva su ID. Devuelve 400 para datos inválidos, 404 si la acción no existe y 409 si el nombre ya está en uso. La actualización es una sola operación SQLite.

Los recursos estáticos son `/assets/styles.css` y `/assets/app.js`. Se sirven embebidos con un tipo de contenido explícito y revalidación de caché.

## Pruebas

Desde `toolstore`: `go test ./...` y `go vet ./...`. Incluyen edición, autenticación, sesiones locales de un solo uso, rechazo de acceso remoto al enlace, logout, origen y recursos estáticos.

La prueba de navegador usa Chrome y Playwright:

```powershell
npm install --prefix .cache/admin-tests playwright --no-audit --no-fund
node integration/admin-smoke.cjs
```

Necesita el catálogo local de prueba en el puerto 18000 y su token en `.cache/device-test/token`. Crea y elimina únicamente sus propias acciones. Verifica acceso, persistencia por pestaña, creación, edición, duplicación, validación JSON, escape de contenido, filtros, hosts, búsqueda, exportación, eliminación y diseños de escritorio y móvil. Las capturas se guardan en `.cache/admin-tests/screens`.
