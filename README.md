# CHAT PRO
**Comparte tus mezclas terminadas**

Proyecto Android independiente de CHAT DJ'S VIP.

## Android
- Paquete: `com.djsebas.chatpro`
- APK debug: GitHub Actions → workflow **Build CHAT PRO APK** → artifact **CHAT-PRO-debug**.

## Backend inicial
En `server/` hay una API HTTP y Socket.IO:
- `GET /health`
- `POST /api/uploads` con multipart field `file`
- `GET /api/uploads/:filename`
- Socket.IO: `room:join`, `room:history`, `message:send`, `message:new`, eventos de presencia.

Ejecutar localmente:
```bash
cd server
npm install
npm start
```

Variables:
- `PORT` (Render asigna este valor)
- `UPLOAD_DIR` (directorio de archivos)
- `MAX_UPLOAD_BYTES` (por defecto 512 MiB)
- `CORS_ORIGIN` (orígenes separados por coma)

## Estado y límites actuales
Esta es una base de desarrollo, no una plataforma lista para producción. El historial de chat vive en memoria y se pierde al reiniciar; los archivos se guardan en el disco local y requieren almacenamiento persistente para producción. Todavía faltan cuentas/autenticación, base de datos, grupos privados con permisos, notificaciones push, controles de abuso y conexión del cliente Android al servidor. La app Android valida la duración de las pistas seleccionadas hasta 50 minutos; el backend aún necesita validación multimedia real y políticas de almacenamiento antes de abrirse al público.
