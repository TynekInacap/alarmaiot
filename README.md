# Alarma IoT – Aplicación Android (TI3042 · Unidad 2)

Aplicación móvil en **Kotlin / Android Studio** que continúa el proyecto de la Unidad 1
(*Alarma antirrobo por proximidad* con Arduino UNO, HC-SR04, 3 LEDs y buzzer).

La misma APK se instala en **dos teléfonos Android** que se comunican de forma inalámbrica
mediante **MQTT sobre Wi-Fi**:

| Teléfono | Rol | Qué hace |
|---|---|---|
| 📟 **Central** | Simula el dispositivo IoT de la puerta (reemplaza al Arduino) | Lee el sensor de proximidad del teléfono o una distancia simulada cada 200 ms, enciende los LEDs (verde/amarillo/rojo), hace sonar la sirena y ejecuta la máquina de estados DESARMADA → ARMADA → ALERTA |
| 📱 **Panel** | Teléfono del dueño de casa | Arma / desarma / consulta (comandos `A`, `D`, `E`, igual que el Monitor Serie), ve la distancia en tiempo real, recibe la notificación de intrusión y llama al **133** |

## Funcionalidades

- **Login** con usuario y contraseña (SQLite). La primera vez se crea la cuenta de administrador.
- **Roles**: Administrador (todo), Operador (arma/desarma), Observador (solo monitorea).
- **Comunicación MQTT** con broker público sobre **TLS** (`ssl://broker.hivemq.com:8883`).
- **Monitoreo en tiempo real**: estado, gráfico de distancia, eventos recientes.
- **Notificación de intrusión** de alta prioridad con acceso directo al 133.
- **Historial** en SQLite: alertas, comandos, cambios de estado, eventos de seguridad y lecturas del sensor, con filtros y estadísticas.
- **Gestión de usuarios** (crear, cambiar rol, restablecer contraseña, desbloquear, eliminar).
- **Recuperación de contraseña** con un código de recuperación de un solo uso (sin servidor ni correo).
- **PIN de la alarma**: Armar y Desarmar a distancia exigen el PIN, como el teclado de una alarma real.

## Seguridad (ISO/IEC 27400)

| Medida | Implementación |
|---|---|
| Política de contraseñas | Mínimo 10 caracteres con mayúscula, minúscula, número y símbolo; sin espacios ni el nombre de usuario. |
| Cifrado de credenciales | Contraseñas y códigos de recuperación con **PBKDF2-HMAC-SHA256** (120.000 iteraciones + sal aleatoria). Nunca en texto plano. |
| Recuperación segura | Código de recuperación de 12 caracteres (~59 bits) que se muestra una sola vez y se guarda solo como hash; al usarlo se emite uno nuevo. Comparte el bloqueo por intentos con el login. |
| Protección de datos sensibles | Código de vinculación, PIN y contraseña del broker cifrados con **AES-256-GCM** usando una clave del **Android Keystore**. |
| Conexión segura | MQTT sobre **TLS** con verificación de certificado y de nombre de host. Tráfico sin cifrar prohibido (`network_security_config`). Solo se aceptan `ssl://` o `wss://`. |
| Cifrado extremo a extremo | Cada mensaje MQTT se cifra con **AES-GCM** con una clave derivada del código de vinculación (PBKDF2, 210.000 iteraciones): el broker público no puede leer ni falsificar mensajes. |
| Comandos firmados con PIN | Armar/Desarmar llevan una firma **HMAC-SHA256** derivada del PIN; la Central la verifica. Sin el PIN no se puede controlar la alarma, aunque se tenga el código de vinculación o se manipule el Panel. 5 PIN incorrectos bloquean el control remoto 5 min. |
| Anti-repetición | Marca de tiempo + nonce único en comandos, eventos y telemetría; los estados más antiguos que el último conocido se descartan. |
| Estado "en línea" verificado | La Central solo se muestra en línea si llegan mensajes autenticados y recientes (telemetría cada 1 s); el aviso sin cifrar solo puede indicar caída. |
| Control de acceso | Roles con mínimo privilegio en el Panel; la Central exige además el PIN. |
| Anti fuerza bruta | Bloqueo de 5 min tras 5 intentos fallidos (login y recuperación); mensajes genéricos; límite de 10 comandos / 10 s. |
| Sesiones | Solo en memoria; expiran tras 5 min de inactividad (Central y Panel se bloquean con contraseña sin cortar el monitoreo). |
| Auditoría | Inicios de sesión, intentos fallidos, cambios de configuración/usuarios y mensajes rechazados quedan registrados. |
| Mínimo privilegio en permisos | El 133 se abre con `ACTION_DIAL` (no requiere `CALL_PHONE`). Sin respaldo en la nube (`allowBackup=false`). |
| Validación de entradas | Consultas SQL parametrizadas, validación de usuario, broker e ID de casa. |
| Build release | No depurable y con capturas de pantalla bloqueadas (`FLAG_SECURE`). |

**Limitaciones conocidas:** el Panel recibe alertas mientras la app está abierta (sin servicio en
primer plano) y la base SQLite no está cifrada (las contraseñas sí van con hash). Son los
siguientes pasos para las Unidades 3 y 4.

## Arquitectura

```
app/src/main/java/cl/alarmaiot/
├── AlarmaApp.kt              Punto de entrada (instancias únicas)
├── alarm/AlarmStateMachine   Máquina de estados (traducción del firmware Arduino)
├── data/                     SQLite: usuarios, eventos, lecturas, configuración, sesión
├── mqtt/MqttManager          Conexión MQTT/TLS, tópicos, cifrado, anti-repetición
├── security/                 PBKDF2, AES-GCM, Android Keystore, anti-repetición
└── ui/                       Pantallas (Login, Inicio, Central, Panel, Historial, Usuarios, Configuración)
```

Tópicos MQTT (`alarmaiot/v1/{idCasa}/…`): `cmd`, `state` (retenido), `event`, `telemetry`, `status` (Last Will).

## Cómo ejecutarla

1. Abrir la carpeta en **Android Studio** y ejecutar en dos teléfonos (o dos emuladores).
2. En cada teléfono: crear el administrador (guardar el código de recuperación) → **Configuración** → mismo **ID de casa** y mismo **código de vinculación**.
3. En el teléfono Central, definir además el **PIN de la alarma** (6 a 8 dígitos).
4. Teléfono 1 → **Modo Central**. Teléfono 2 → **Modo Panel**.
5. En el Panel tocar **Armar** e ingresar el PIN; en la Central acercar la mano al sensor de proximidad (o bajar el deslizador a menos de 20 cm).
6. El Panel recibe la alerta, la notificación y puede llamar al 133. **Desarmar** (con PIN) vuelve al estado inicial.

Requisitos: Android 8.0 (API 26) o superior, conexión a Internet (Wi-Fi o datos).

## Pruebas

```bash
./gradlew testDebugUnitTest
```

26 pruebas unitarias: máquina de estados (mismo comportamiento que el firmware), hash y
política de contraseñas, códigos de recuperación, PIN y firma de comandos, cifrado extremo a
extremo, rechazo de mensajes alterados o con código incorrecto, anti-repetición, limitador de
comandos y validación del broker. Con `MQTT_IT=1` se agrega una prueba contra el broker real.
