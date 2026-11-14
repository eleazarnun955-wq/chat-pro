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


## Nueva base multiusuario (CHAT PRO 2.0)
El backend incorpora una primera capa de cuentas y conversaciones:
- Registro e inicio de sesión con contraseñas derivadas mediante scrypt y sesiones por token.
- Perfil básico y búsqueda de usuarios.
- Conversaciones privadas y grupos con membresía y autorización.
- Historial separado por conversación, con hasta 500 mensajes por conversación.
- Socket.IO autenticado para mensajes en tiempo real.
- Se mantiene temporalmente la compatibilidad de la sala anterior para no romper la APK ya instalada.

Rutas nuevas: `POST /api/register`, `POST /api/login`, `POST /api/logout`, `GET /api/me`, `PATCH /api/me`, `GET /api/users/search`, `GET/POST /api/conversations`, `GET /api/conversations/:id/messages` y `POST /api/conversations/:id/members`. Los endpoints de cuenta requieren `Authorization: Bearer <token>`.

**Estado honesto:** esta es la base de servidor; todavía falta terminar y compilar la interfaz Android multiusuario, añadir notificaciones push, reforzar controles de archivos y desplegar/verificar la nueva API en el servidor. No se debe considerar CHAT PRO 2.0 terminado hasta probar dos cuentas reales en dos teléfonos.
