# CHAT PRO
**Mensajería multiusuario para Android**

## Android
- Paquete Android: `com.djsebas.chatpro.v172` (instalación independiente; no reemplaza 1.7.0).
- Versión: `1.7.2` (versionCode `22`).
- Puede instalarse junto a las versiones anteriores de CHAT PRO.
- APK: GitHub Actions → **Build CHAT PRO APK** → artifact **CHAT-PRO-debug**.

## Funciones conectadas
- Registro e inicio de sesión exclusivamente con correo electrónico y contraseña (sin teléfono ni SMS).
- Vinculación segura de correo a cuentas anteriores mediante confirmación de contraseña.
- Sesiones con token y perfiles.
- Búsqueda de usuarios y conversaciones privadas.
- Grupos privados con autorización por membresía.
- Salas públicas y privadas: creación, exploración y unión/invitación de integrantes.
- Mensajes en tiempo real e historial en Supabase.
- Conteo de no leídos, confirmaciones de entrega y lectura.
- Reacciones por mensaje (👍 ❤️ 😂 🔥 👏 😮 😢 🎉).
- Presencia de conexión y última actividad.
- Estados de 24 horas y visualizaciones.
- Fotos, videos, audio y documentos en Supabase Storage privado.
- Vista previa de imágenes dentro del chat y apertura ampliada.
- Reproducción integrada de videos, música y audios.
- Descarga autenticada de todos los archivos a `Descargas/CHAT PRO`.
- Reproducción en línea con soporte de solicitudes HTTP Range para audio y video.
- Acceso a archivos de mensajes limitado a integrantes de la conversación; los estados vigentes conservan su visibilidad para usuarios registrados.
- Eliminación de estados propios.
- Eliminación real de mensajes para mí y para todos; borrado global con marcador interno compatible con la regla de contenido de PostgreSQL.
- Opción Limpiar historial con confirmación: oculta los mensajes solo en la cuenta que la ejecuta, sin borrar el historial de los demás participantes.
- Cambio de contraseña y cierre de otras sesiones.
- Descargas autenticadas a `Descargas/CHAT PRO`.

## Backend
- Render: `https://chat-pro-server-qhjw.onrender.com`
- Supabase: PostgreSQL y Storage.
- La clave privada de Supabase debe permanecer únicamente en las variables secretas del servidor.
- RLS está habilitado en las tablas internas; el backend usa una credencial de servidor, nunca una clave secreta en Android.

## Pendientes de validación
- Prueba integral con dos o más teléfonos y cuentas: subida, vista previa, reproducción, descarga y permisos de archivos en salas públicas y privadas.
- Notificaciones push del sistema (requieren configurar Firebase Cloud Messaging y credenciales).
- Asistente de IA generativa (requiere elegir y configurar un proveedor/API y su clave).
- Llamadas de voz/video y cifrado de extremo a extremo.
- Prueba de recuperación de cuenta y moderación avanzada.
- Revisar reglas de acceso de salas y archivos antes de abrir el registro al público.
- Prueba integral de creación de sala privada, invitaciones, reproducción de audio y cambio de contraseña en dos o más teléfonos.

No considerar estas funciones verificadas en teléfonos reales hasta completar pruebas multiusuario.
