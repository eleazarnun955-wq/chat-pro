# CHAT PRO
**Comparte tus mezclas terminadas**

Aplicación Android independiente de CHAT DJ'S VIP.

## Android
- Aplicación independiente: `com.djsebas.chatpro.v2` (no sustituye instalaciones anteriores).
- Versión configurada: `1.4.0` (versionCode `5`).
- APK debug: GitHub Actions → **Build CHAT PRO APK** → artifact **CHAT-PRO-debug**.
- Los archivos recibidos desde conversaciones se pueden descargar con autenticación a `Descargas/CHAT PRO`.

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
- Socket.IO autenticado para mensajes en tiempo real (los sockets sin sesión quedan bloqueados por defecto).
- Conteo de mensajes sin leer y confirmación de lectura al abrir una conversación.
- Se mantiene temporalmente la compatibilidad de la sala anterior para no romper la APK ya instalada.

Rutas nuevas: `POST /api/register`, `POST /api/login`, `POST /api/logout`, `GET /api/me`, `PATCH /api/me`, `GET /api/users/search`, `GET/POST /api/conversations`, `GET /api/conversations/:id/messages` y `POST /api/conversations/:id/members`. Los endpoints de cuenta requieren `Authorization: Bearer <token>`.

**Estado honesto:** la interfaz Android multiusuario ya incluye registro/inicio de sesión, búsqueda de usuarios, chats privados, grupos, estados y envío de adjuntos. La descarga autenticada de adjuntos, los conteos de mensajes sin leer y las confirmaciones de lectura se añadieron en esta etapa. El backend aún persiste en JSON y todavía no consume las tablas Supabase existentes; falta configurar una credencial privada de servidor en Render y migrar la persistencia. También faltan notificaciones push, salas públicas/privadas completas, reacciones, confirmaciones de lectura, presencia y pruebas reales con dos teléfonos. No considerar CHAT PRO terminado hasta validar esos puntos.
