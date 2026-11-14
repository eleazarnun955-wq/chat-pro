# CHAT PRO
**Comparte tus mezclas terminadas**

Aplicación Android independiente de CHAT DJ'S VIP.

## Android
- Paquete: `com.djsebas.chatpro`
- Versión: 1.1.0
- APK debug: GitHub Actions → **Build CHAT PRO APK** → artifact **CHAT-PRO-debug**.

## Funciones conectadas en esta etapa
- Conversación en tiempo real mediante Socket.IO.
- Salas por identificador y nombre de usuario.
- Historial de mensajes persistente en archivo JSON (si el servidor dispone de disco persistente).
- Envío de fotografías, videos, audio MP3/WAV/M4A/FLAC y documentos.
- Enlaces de medios dentro del chat para abrir/reproducir con una aplicación compatible del teléfono.
- Límite de subida configurable (por defecto 512 MiB).

## Servidor
Desde `server/`:
```bash
npm install
npm start
```

Variables:
- `PORT`: puerto HTTP (Render lo asigna).
- `UPLOAD_DIR`: directorio de archivos subidos.
- `DATA_DIR`: directorio para historial persistente.
- `MAX_UPLOAD_BYTES`: máximo por archivo, por defecto 512 MiB.
- `CORS_ORIGIN`: orígenes permitidos separados por coma.

Endpoints:
- `GET /health`
- `POST /api/uploads` (multipart field `file`)
- `GET /api/uploads/:filename`
- Socket.IO: `room:join`, `room:history`, `message:send`, `message:new`.

## Configuración de la app
Al abrir CHAT PRO, pulsa **Servidor** y pega la URL HTTPS del backend desplegado. En **Sala** puedes cambiar el nombre visible y la sala. Ambos valores quedan guardados en el teléfono.

## Pendientes antes de uso público
Esta versión es una base funcional de chat y medios, no una plataforma pública terminada: aún faltan registro/inicio de sesión con verificación, permisos de salas privadas, base de datos gestionada, notificaciones push, moderación, cifrado de extremo a extremo y reproducción multimedia embebida dentro de la app. Para conservar archivos e historial en Render, configura un disco persistente y define `UPLOAD_DIR` y `DATA_DIR` dentro del punto de montaje.
