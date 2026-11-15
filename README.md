# CHAT PRO
**Mensajería multiusuario para Android**

## Android
- Paquete independiente: `com.djsebas.chatpro.v3`.
- Versión: `1.6.0` (versionCode `10`).
- La actualización conserva el mismo applicationId que la instalación 1.4.0.
- APK: GitHub Actions → **Build CHAT PRO APK** → artifact **CHAT-PRO-debug**.

## Registro y seguridad de cuentas
- El registro nuevo exige verificar correo electrónico mediante OTP o celular mediante SMS, a elección del usuario.
- El correo y teléfono verificados se guardan en columnas privadas con índices únicos; nunca se incluyen en el perfil público.
- El servidor rechaza el registro antiguo sin verificación y comprueba la sesión de Supabase antes de crear la cuenta.
- La recuperación mediante OTP está disponible para cuentas creadas con el nuevo flujo y vinculadas a un contacto verificado.
- Para activar correo, configura la plantilla de Auth para mostrar `{{ .Token }}` como código de seis dígitos y revisa SMTP.
- Para activar celular, habilita Phone Auth y configura un proveedor SMS compatible en Supabase. Sin esa configuración, el envío de SMS no funcionará.

## Funciones conectadas
- Registro, inicio/cierre de sesión, sesiones con token y perfiles.
- Búsqueda de usuarios y conversaciones privadas.
- Grupos privados con autorización por membresía.
- Salas públicas y privadas: creación, exploración y unión/invitación de integrantes.
- Mensajes en tiempo real e historial en Supabase.
- Conteo de no leídos, confirmaciones de entrega y lectura.
- Reacciones por mensaje (👍 ❤️ 😂 🔥 👏 😮 😢 🎉).
- Presencia de conexión y última actividad.
- Estados de 24 horas y visualizaciones.
- Fotos, videos, audio y documentos en Supabase Storage privado.
- Reproducción de audio desde conversaciones.
- Descarga de medios y estados compartidos.
- Eliminación de estados propios.
- Cambio de contraseña y cierre de otras sesiones.
- Descargas autenticadas a `Descargas/CHAT PRO`.

## Backend
- Render: `https://chat-pro-server-qhjw.onrender.com`
- Supabase: PostgreSQL y Storage.
- La clave privada de Supabase debe permanecer únicamente en las variables secretas del servidor.
- RLS está habilitado en las tablas internas; el backend usa una credencial de servidor, nunca una clave secreta en Android.

## Pendientes de validación
- Prueba integral con dos o más teléfonos y cuentas.
- Notificaciones push del sistema (requieren configurar Firebase Cloud Messaging y credenciales).
- Asistente de IA generativa (requiere elegir y configurar un proveedor/API y su clave).
- Llamadas de voz/video y cifrado de extremo a extremo.
- Prueba de recuperación de cuenta y moderación avanzada.
- Revisar reglas de acceso de salas y archivos antes de abrir el registro al público.
- Prueba integral de creación de sala privada, invitaciones, reproducción de audio y cambio de contraseña en dos o más teléfonos.

No considerar estas funciones verificadas en teléfonos reales hasta completar pruebas multiusuario.
