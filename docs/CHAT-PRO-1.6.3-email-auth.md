# CHAT PRO 1.6.3 — acceso por correo

- El registro solicita nombre visible, correo electrónico y contraseña.
- El inicio de sesión utiliza correo electrónico y contraseña; no solicita teléfono ni SMS.
- Las cuentas existentes pueden vincular un correo desde Perfil, confirmando la contraseña actual.
- Los correos se guardan en la tabla privada de contactos y tienen una restricción única sin distinguir mayúsculas.
- El identificador interno de usuario se genera automáticamente para conservar las funciones de búsqueda y conversaciones.

La cuenta de correo no se verifica mediante código o enlace en esta versión. La recuperación de contraseña por correo requiere configurar un proveedor de envío de correo y se mantiene pendiente.