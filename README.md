# CHAT PRO
**Mensajería multiusuario para Android**

## Android
- Versión: `1.8.1` (versionCode `24`).
- Paquete independiente: `com.djsebas.chatpro.v181`; se instala junto a CHAT PRO 1.7.2 y versiones anteriores, sin reemplazarlas.
- Esta versión conserva llamadas de voz y videollamadas, e incorpora mensajes de voz, bloqueo de app con huella/PIN y acciones sobre participantes. Las notificaciones push requieren configurar credenciales Firebase en el servidor.
- APK: GitHub Actions → **Build CHAT PRO APK** → artifact **CHAT-PRO-debug**.

## Funciones conectadas
- Registro e inicio de sesión exclusivamente con correo electrónico y contraseña.
- Cuentas, perfiles, sesiones y búsqueda de usuarios.
- Conversaciones privadas uno a uno, grupos privados y salas públicas/privadas.
- Mensajes en tiempo real, historial, no leídos y confirmaciones de entrega y lectura.
- Reacciones, presencia y estados de 24 horas.
- Fotos, videos, audio y documentos mediante almacenamiento privado.
- Vista previa y reproducción integrada de archivos multimedia.
- Descargas autenticadas a `Descargas/CHAT PRO`.
- Eliminación de mensajes para mí o para todos y limpieza individual del historial.
- Llamadas de voz y videollamadas WebRTC en chats privados individuales.
- Controles durante la llamada: aceptar/rechazar, silenciar micrófono, altavoz, cámara, cambiar cámara y colgar.

## Llamadas: alcance y red
- La señalización usa Socket.IO autenticado y valida que ambos usuarios pertenezcan al mismo chat privado individual.
- Audio y video se transmiten con WebRTC directamente entre dispositivos cuando la red lo permite.
- Se configura STUN público para ayudar a establecer la conexión directa. No se incluye un servidor TURN de retransmisión: ciertas redes, NAT o firewalls pueden impedir conectar; en ese caso habrá que añadir un TURN (puede tener límites gratuitos y cobrar por uso).
- La llamada entrante requiere que CHAT PRO esté abierto y conectado a internet en el dispositivo receptor. Las notificaciones de llamada en segundo plano requieren configurar Firebase Cloud Messaging.
- No se cobra por minuto dentro de CHAT PRO; cada persona consume sus propios datos móviles o Wi-Fi. El costo de TURN, si se necesita, depende del tráfico.

## Backend
- Render: `https://chat-pro-server-qhjw.onrender.com`
- Supabase: PostgreSQL y Storage.
- Las claves privadas de Supabase permanecen únicamente en el servidor.

## Validación pendiente antes de distribuir
- Compilación Android y pruebas con dos teléfonos/cuentas reales.
- Probar llamadas de voz/video en Wi-Fi y datos móviles, permisos, rechazo, no respuesta, desconexión y reconexión.
- Validar conexiones en redes restrictivas y decidir si hace falta un servidor TURN.
- Notificaciones de llamadas cuando la app está en segundo plano (FCM).
