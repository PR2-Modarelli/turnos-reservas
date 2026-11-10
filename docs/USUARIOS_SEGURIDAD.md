# Usuarios y autenticación

## Registro: POST /api/register

Público, `Content-Type: application/json`. Campos compatibles con el modelo JHipster:

| Campo | Validación |
| --- | --- |
| login | Obligatorio, 3–50 caracteres; letras ASCII, números, _, ., @, -; se normaliza a minúsculas |
| password | Obligatoria, 4–100 caracteres; nunca se recorta ni se convierte a minúsculas |
| firstName, lastName | Opcionales, hasta 50 caracteres cada uno |
| email | Obligatorio, formato email, 5–254 caracteres; se guarda en minúsculas y debe ser único |
| imageUrl | Opcional, hasta 256 caracteres |
| langKey | Opcional, 2–10 caracteres; por defecto es |

Ejemplo exclusivamente ficticio:

```json
{
  "login": "usuario.prueba",
  "password": "una-clave-de-prueba",
  "firstName": "Usuario",
  "lastName": "Prueba",
  "email": "usuario.prueba@example.com",
  "langKey": "es"
}
```

Responde 201 con `Location: /api/account` y la cuenta creada, sin contraseña ni hash. El servidor asigna `id`, `publicId` UUID, auditoría, `activated: true` y `authorities: ["ROLE_USER"]`. No se verifica correo: puede iniciar sesión inmediatamente. Campos extra como `id`, `activated` o `authorities` se rechazan con 400. No hay registro público de administradores.

Login y email tienen restricciones UNIQUE en PostgreSQL, además de comprobación previa. Dos registros concurrentes no pueden crear dos cuentas iguales. La contraseña se guarda con PBKDF2-HMAC-SHA256, sal aleatoria de 16 bytes, 310000 iteraciones y clave de 256 bits (configuración Spring Security v5.8). Se admiten contraseñas Unicode largas sin truncamiento por bytes. Los DTO con credenciales tienen `toString` redactado y las respuestas de validación no repiten valores recibidos.

## Login: POST /api/authenticate

Público. Request:

```json
{"username":"usuario.prueba","password":"una-clave-de-prueba","rememberMe":false}
```

`username`: obligatorio, máximo 254 caracteres; admite login sin distinguir mayúsculas. `password`: 4–100 caracteres. `rememberMe` es opcional y false por defecto.

Respuesta 200 `{"id_token":"<JWT-propio>"}`, también en cabecera `Authorization: Bearer <JWT-propio>`. No se guarda el token en cookie; el cliente lo envía en el header de cada consulta protegida. Los usuarios inactivos, inexistentes y las contraseñas incorrectas reciben el mismo 401. Se calcula el hash incluso si el usuario no existe.

## Cuenta propia: GET /api/account

Requiere JWT de usuario propio. Devuelve solo la cuenta identificada por el claim `uid`, contrastando también el login de `sub` y que siga activa. No acepta identificadores ajenos en ruta, query ni body para elegir otra cuenta. Un token de desarrollo sin `uid` no permite obtener una cuenta persistida. No existe listado público de usuarios ni actualización de perfiles en este módulo.

La cuenta incluye `id`, `publicId`, `login`, `firstName`, `lastName`, `email`, `imageUrl`, `langKey`, `activated`, `authorities` y `createdDate`. Nunca incluye `password_hash` ni el token técnico de cátedra.

## JWT e integración con catálogo

HS512 con clave Base64 de al menos 64 bytes externalizada en `TURNOS_JWT_SECRET`. Es la misma clave que `CATALOGO_JWT_SECRET`, porque turnos propaga el JWT del usuario al catálogo. No es el JWT técnico de cátedra. El catálogo verifica por su cuenta firma y vigencia sin acceder a la base de turnos.

Claims: `sub` = login normalizado; `uid` = UUID público estable; `auth` = ROLE_USER; `iat`, `exp`. Vigencia de 1 hora; `rememberMe: true` da 24 horas. El JWT no contiene password, hash, email ni credenciales técnicas. Turnos exige firma HS512, expiración presente y válida, e identidad `sub` no vacía. El token técnico no tiene permisos de usuario en turnos.

`publicId` es la referencia estable enviada como `externalPatientId` al solicitar un hold; su proceso se asocia en PostgreSQL al ID del usuario autenticado. No se acepta ese identificador libremente del cliente. La creación y consulta de procesos propios se describen en [bloqueos](BLOQUEOS.md); la [confirmación inicial REST](CONFIRMACION.md) utiliza la misma identidad y los nombres de la cuenta. El [paso de teléfono por Kafka y el resultado final](FLUJO_KAFKA.md) también están implementados con acceso por propietario. La cancelación sigue pendiente.

Las APIs son stateless, sin sesión ni autenticación mediante cookies; CSRF está desactivado para esos endpoints JWT. No se habilita CORS permisivo; Android nativo no depende de CORS. No se implementó recuperación de contraseña, refresh token ni revocación inmediata general: un JWT firmado conserva su vigencia hasta `exp` en endpoints que no consultan el usuario. La consulta de cuenta y el login sí verifican activación en la base. El despliegue deberá proteger el transporte con HTTPS; el HTTP de localhost es para desarrollo.

## Persistencia exclusiva

Instancia PostgreSQL de turnos, puerto local 5434, usuario/base `turnos`, volumen persistente propio y contraseña local obligatoria. El catálogo sigue en su instancia y base separadas. Liquibase registra la migración `202611060001`.

`app_user` contiene ID interno, UUID público, login, hash, nombres, email, imagen, idioma, activación y fecha de creación. No contiene categorías, profesionales u horarios. El rol público se fija en el backend y no existe una columna editable por el cliente para escalar permisos.

## Errores

| HTTP | code | Caso |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Validación, JSON inválido o campos no admitidos |
| 400 | USERNAME_ALREADY_EXISTS | Login ya registrado |
| 400 | EMAIL_ALREADY_EXISTS | Email ya registrado |
| 400 | ACCOUNT_ALREADY_EXISTS | Conflicto UNIQUE durante registros simultáneos |
| 401 | INVALID_CREDENTIALS | Login incorrecto, cuenta inactiva o cuenta no válida para el token |
| 401 | sin garantía de code | Token ausente, firma/expiración/identidad inválida |
| 403 | sin garantía de code | Rol no admitido |
| 503 | PERSISTENCIA_NO_DISPONIBLE | Fallo de acceso a PostgreSQL |

Los errores propios usan `application/problem+json`. No se exponen mensajes SQL, hash ni contraseñas. El registro y login son operaciones locales: no registran usuarios en cátedra ni cambian la cuenta técnica del proyecto.

## Pruebas

`./mvnw test` usa PostgreSQL real en Testcontainers. Comprueba hash salado, normalización, unicidad concurrente, validaciones, rechazo de campos privilegiados, login, vencimientos, usuarios inactivos, lectura exclusiva de la cuenta y el recorrido HTTP hasta disponibilidad. No exige credenciales reales ni conexión a la red de cátedra.
