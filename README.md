# CHAT PRO 2.2.0
**Mensajería multiusuario para Android**

## Identidad Android
- Versión: `2.2.0` (versionCode `31`).
- applicationId independiente: `com.djsebas.chatpro.v220`.
- Se instala en paralelo con CHAT PRO 2.1.0 (`com.djsebas.chatpro.v210`); no actualiza ni reemplaza esa instalación.
- APK: GitHub Actions → **Build CHAT PRO APK** → artifact **CHAT-PRO-2.2.0-debug** o **CHAT-PRO-2.2.0-release** si están configurados los secretos de firma.

## Funciones implementadas en esta rama
- Registro e inicio de sesión por correo electrónico.
- Chats privados, grupos y salas; mensajes en tiempo real.
- Envío de imágenes, videos, audio y documentos; notas de voz.
- Estados de 24 horas, reacciones, favoritos y mensajes fijados.
- Búsqueda, reenvío, bloqueo y reporte de usuarios.
- Gestión de integrantes de grupos: propietario, administrador, moderador y miembro; invitar, cambiar roles, retirar integrantes y salir del grupo (el propietario debe transferir propiedad antes de salir).
- Historial con paginación por cursor; la primera carga muestra los mensajes más recientes.
- Confirmaciones de lectura individuales por integrante, almacenadas en `message_receipts`.
- PIN local con PBKDF2-HMAC-SHA256, sal aleatoria y espera tras cinco intentos fallidos; migración de PIN antiguo al desbloquear.
- Token de sesión cifrado con AES-GCM usando Android Keystore; `allowBackup=false`.
- Llamadas de voz y videollamadas WebRTC en chats privados individuales.
- Configuración ICE consultada al servidor; admite STUN y credenciales TURN temporales.

## Notificaciones y llamadas en segundo plano
- Las notificaciones locales actuales funcionan mientras la app mantiene la conexión Socket.IO.
- Firebase Messaging ya está integrado en Android y Firebase Admin en el servidor. El registro del token del dispositivo y la entrega push para mensajes/llamadas están implementados; para activarlos hay que registrar en Firebase la app Android con paquete `com.djsebas.chatpro.v220`, añadir `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_PROJECT_ID` y `FIREBASE_SENDER_ID` como secretos de GitHub Actions, y configurar `FIREBASE_SERVICE_ACCOUNT_JSON` en Render.
- Las llamadas entrantes siguen requiriendo que el dispositivo receptor esté conectado; FCM para llamadas entrantes está pendiente.

## Configurar TURN (opcional, recomendado para redes restrictivas)
En el servicio Render del backend, configurar:
- `TURN_URLS`: lista separada por comas, por ejemplo `turn:turn.tudominio:3478?transport=udp,turns:turn.tudominio:5349?transport=tcp`.
- `TURN_SHARED_SECRET`: secreto compartido del servidor TURN/coturn con autenticación REST habilitada.

El backend genera credenciales temporales de una hora por usuario. Sin estas variables, la app usa servidores STUN públicos y algunas redes móviles/NAT pueden impedir la conexión.

## Migraciones de Supabase
Aplicar en orden las migraciones SQL del proyecto, incluida:
- `supabase/migrations/20261119032502_chat_pro_2_2_0_foundation.sql`
- `supabase/migrations/20261119032806_chat_pro_2_2_0_user_reports_index.sql`

Esta migración crea tablas de tokens push y recibos individuales, y habilita roles de grupo. No desplegar el backend 2.2.0 antes de aplicar la migración correspondiente.

## Backend
- Render configurado en la app: `https://chat-pro-server-2-2-0.onrender.com`.
- Supabase: PostgreSQL y Storage.
- Las claves privadas de Supabase permanecen únicamente en el servidor.
- Si faltan `SUPABASE_URL` o `SUPABASE_SECRET_KEY`, el proceso HTTP permanece activo y `/health` informa `ready: false`; las rutas de datos responden HTTP 503 hasta configurar ambas variables. No se usan credenciales de prueba.

## Validación pendiente antes de distribuir
- Compilación Android y pruebas de instalación en dos teléfonos; confirmar que 2.1.0 y 2.2.0 coexisten.
- Configuración pendiente en Render: `SUPABASE_URL` y `SUPABASE_SECRET_KEY`. Hasta entonces el servidor informa estado no listo y las funciones conectadas permanecen deshabilitadas.
- Aplicar migración y desplegar esta rama del backend en Render.
- Pruebas reales de permisos, chats, grupos, medios, PIN/biometría y llamadas por Wi-Fi y datos móviles.
- Configurar las credenciales Firebase indicadas y probar FCM en un teléfono con la app cerrada/forzada; la entrega push no puede activarse hasta que existan esas credenciales.
- Configurar y probar un TURN real para redes restrictivas.
- Firma release: configurar `CHAT_PRO_KEYSTORE_BASE64`, `CHAT_PRO_STORE_PASSWORD`, `CHAT_PRO_KEY_ALIAS` y `CHAT_PRO_KEY_PASSWORD` en GitHub Actions. Sin ellos se genera APK debug instalable.
