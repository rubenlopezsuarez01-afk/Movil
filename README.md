# Copiloto: asistente de voz para Android Auto

App para Android Auto que se maneja hablando, sin tocar la pantalla. Sirve para:

- **Gasolina más barata**: usa los precios oficiales del Ministerio, que se actualizan cada 30 minutos. Puede buscar cerca de ti o *de camino* en la ruta que tengas activa, y te lleva a la gasolinera que elijas.
- **Dónde comer**: busca restaurantes, bares y comida rápida en OpenStreetMap, filtrando por tipo de cocina.
- **Rutas alternativas**: te lee las variantes que hay para llegar a un destino (tiempo, km, carreteras y si tienen peajes) y te lleva por la que elijas.
- **Correo, notificaciones y fútbol**: te lee los correos nuevos y los avisos de cualquier app (Flashscore, banco…), y te dice los partidos del día y cómo van.
- **Paradas sin perder el destino**: si paras a repostar o a comer, al volver a abrir la app te pregunta *"¿Seguimos hacia Valencia?"*.

Ninguna de estas fuentes pide clave de API ni cuesta dinero.

## Cómo se usa (solo con la voz)

1. Di **"Ok Google, abre Copiloto"**. La app saluda y se pone a escuchar sola.
2. Habla con normalidad. Por ejemplo:

| Qué quieres | Qué decir |
|---|---|
| Repostar | "gasolinera más barata", "diésel más barato a menos de 20 km", "gasolina 98 de camino", "la más cercana" |
| Ir directamente | "llévame a la gasolinera más barata" |
| Comer | "dónde comer", "tengo hambre", "una pizzería cerca", "tapas de camino", "comida rápida" |
| Rutas | "rutas a Valencia", "cómo llego a Madrid sin peajes", "llévame a casa", "otras rutas" |
| Elegir | "la primera", "llévame a la segunda", "la más rápida", "la más corta", "la que no tenga peajes", "la última" |
| Más información | "dame detalles de la tercera", "más opciones", "repite" |
| Después de una parada | "continuar a mi destino" (o responder "sí" cuando te lo pregunte) |
| Correo | "¿tengo correos nuevos?", "lee mis correos", "abre el correo" → "sigue" |
| Notificaciones de cualquier app | "lee mis notificaciones", "¿qué dice Flashscore?", "notificaciones del banco" |
| Fútbol | "¿qué partidos hay hoy?", "¿cómo va el Madrid?", "¿juega el Betis mañana?", "partidos de Champions", "¿juega mi equipo hoy?" |
| Abrir apps | "abre GasofApp" (te busca la gasolina más barata), "abre Flashscore" (partidos de hoy), "abre el correo" |
| Preferencias | "mi coche es diésel", "uso gasolina 98", "mi equipo es el Betis" |
| Terminar | "gracias", "nada", "cancelar" |

Cuando eliges un sitio, la app te lo dice en voz alta y pasa la navegación a tu navegador habitual (Google Maps o Waze).

## "Abre GasofApp / el correo / Flashscore"

Android Auto **no deja mostrar en la pantalla del coche apps del móvil que no estén hechas para coche**, como GasofApp, Gmail o Flashscore. Ninguna app puede saltarse eso. Así que Copiloto hace por voz lo que harías en ellas:

- **GasofApp**: los precios salen de la misma fuente oficial (Ministerio), así que "abre GasofApp" te busca directamente la gasolinera más barata.
- **Correo (Gmail, Outlook…)**: te lee remitente y asunto de los correos que han llegado como notificación en las últimas 24 h. De cuatro en cuatro: di "sigue" para oír más.
- **Flashscore**: te dice los partidos del día, los resultados en directo y el próximo partido de un equipo (LaLiga, Segunda, Copa, Champions, Europa y Conference League, Premier, Serie A, Bundesliga, Ligue 1 y NBA). Si Flashscore te avisa de goles de tus equipos, "¿qué dice Flashscore?" te lee esos avisos.
- **Cualquier otra app** ("¿qué dice el banco?", "notificaciones de Amazon"): te lee sus notificaciones recientes.

Para el correo y las notificaciones hay que dar permiso una vez en el móvil: abre Copiloto → **Permitir leer notificaciones** → activa Copiloto. Las notificaciones solo se guardan en memoria y no salen del móvil.

Las apps que sí son de Android Auto (Spotify, WhatsApp, Telegram, Waze…) se abren pidiéndoselo a Google: "Ok Google, abre Spotify".

## Botón de voz del volante (Kia XCeed 2022)

El botón de voz del volante (el del icono de una cara hablando) suele tener dos funciones en los Kia cuando Android Auto está conectado. Pruébalo en el tuyo, porque depende de la pantalla y del software:

- **Pulsación corta** → reconocimiento de voz propio de Kia (en las versiones con navegador).
- **Pulsación larga** (mantener hasta oír el pitido) → **Asistente de Google / Gemini** del móvil.

Ni Kia ni Android Auto permiten asignar ese botón a otra app. Pero se consigue el mismo efecto con una sola frase:

1. **Mantén pulsado** el botón de voz hasta el pitido.
2. Di **"Abre Copiloto"**.
3. Copiloto se abre, te saluda y ya te está escuchando. Desde ahí todo es conversación: tras cada respuesta vuelve a escuchar solo.

Si Copiloto ya está en pantalla, repetir "mantener botón → abre Copiloto" lo vuelve a poner a escuchar. También puedes decir "Ok Google, abre Copiloto" sin tocar nada, si tienes activado "Hey Google" en el móvil (Ajustes de Google → Asistente/Gemini → Hey Google / Voice Match, con la opción "Mientras conduces" activada).

Consejos para el XCeed:
- Si una pulsación corta abre el sistema de Kia y no quieres usarlo, acostúmbrate a la pulsación larga. Una pulsación demasiado corta con Android Auto activo puede no llegar al Asistente.
- En los ajustes de la pantalla del coche (apartado de conexión de dispositivos o proyección del móvil), comprueba que Android Auto está activado para tu móvil.
- Si el Asistente no te entiende bien con el ruido del coche, en el móvil: Ajustes de Android Auto → **"Hey Google"** con Voice Match activado, y el idioma del Asistente en **Español (España)**.

## Instalación

1. Descarga el APK desde la pestaña **Actions** del repositorio (artefacto `copiloto-debug-apk`). También puedes compilarlo tú con `./gradlew :app:assembleDebug`.
2. Instálalo en el móvil, ábrelo y pulsa **Conceder permisos** (ubicación y micrófono). En esa pantalla puedes guardar tu combustible, el radio de búsqueda, los litros del depósito y las direcciones de casa y del trabajo.
3. Activa las apps de origen desconocido en Android Auto, porque la app no viene de Play Store:
   - Ajustes de Android Auto → toca **Versión** 10 veces para activar el modo desarrollador.
   - Menú ⋮ → **Ajustes para desarrolladores** → marca **Fuentes desconocidas**.
4. En el coche, añade Copiloto al menú de apps si no aparece (Ajustes de Android Auto → Personalizar menú).

## Cómo funciona

```
core/  Kotlin puro, con tests
  dialog/CommandParser   transcripción en español → orden ("la segunda", "sin peajes"…)
  dialog/Conversation    contexto del diálogo: resultados, destino, rutas, preguntas pendientes
  fuel/ food/ route/     lectura de datos del Ministerio, Overpass (OSM) y OSRM, y ordenación
  apps/ sports/          notificaciones por app y partidos (marcador público de ESPN)
app/   Android Auto (Car App Library 1.7)
  car/CopilotoSession    bucle hablar → escuchar → entender → mostrar → navegar
  car/Screens            pantalla principal, mapa con resultados y lista de rutas
  voice/VoiceAssistant   micrófono del coche (CarAudioRecord) + reconocimiento de voz en español + TTS
  data/AndroidServices   GPS, Geocoder y llamadas HTTP
  notifications/         NotificationReader: guarda en memoria las notificaciones recientes
```

- **Voz**: en Android 13 o superior, y con un Android Auto reciente, se escucha por el micrófono del coche y se transcribe con el reconocedor de Google. Si no se puede, se usa el micrófono por defecto. Las respuestas se leen bajando el volumen de la música, no cortándola.
- **Rutas alternativas**: Android Auto solo deja que otra app pase un destino al navegador, no una ruta completa. Para que vayas por la alternativa que eliges, Copiloto te manda primero a un punto propio de esa ruta. Al llegar, di "continuar a mi destino".

## Limitaciones conocidas

- Google no permite que una app cambie Android Auto ni Google Assistant. Esta app se añade a lo que ya tienes, no lo sustituye.
- En Play Store, Android Auto solo acepta ciertas categorías de apps. Gasolineras sí entra (puntos de interés), pero restaurantes no. Instalada a mano como se explica arriba, funciona todo.
- La ruta para buscar *de camino* es la que calculó Copiloto. Copiloto no ve la ruta que esté siguiendo Google Maps.
- Del correo solo se lee lo que aparece en la notificación (remitente, asunto y el principio del mensaje). Si silencias las notificaciones de Gmail, Copiloto no las verá.
- Los partidos salen del marcador público de ESPN, que no es un servicio oficial y podría cambiar.
- Los servidores públicos de OSRM, Overpass y Nominatim tienen límites de uso pensados para uso personal.

## Desarrollo

```bash
./gradlew :core:test           # tests de órdenes de voz, diálogo y lectura de datos
./gradlew :app:assembleDebug   # APK (necesita el Android SDK)
```

Para probar sin coche, usa el **Desktop Head Unit** (DHU) del Android SDK.
