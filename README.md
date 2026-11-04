# Copiloto: asistente de voz para Android Auto

App para Android Auto que se maneja hablando, sin tocar la pantalla. Sirve para:

- **Gasolina más barata**: usa los precios oficiales del Ministerio, que se actualizan cada 30 minutos. Puede buscar cerca de ti o *de camino* en la ruta que tengas activa, y te lleva a la gasolinera que elijas.
- **Dónde comer**: busca restaurantes, bares y comida rápida en OpenStreetMap, filtrando por tipo de cocina.
- **Rutas alternativas**: te lee las variantes que hay para llegar a un destino (tiempo, km, carreteras y si tienen peajes) y te lleva por la que elijas.
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
| Preferencias | "mi coche es diésel", "uso gasolina 98" |
| Terminar | "gracias", "nada", "cancelar" |

Cuando eliges un sitio, la app te lo dice en voz alta y pasa la navegación a tu navegador habitual (Google Maps o Waze).

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
app/   Android Auto (Car App Library 1.7)
  car/CopilotoSession    bucle hablar → escuchar → entender → mostrar → navegar
  car/Screens            pantalla principal, mapa con resultados y lista de rutas
  voice/VoiceAssistant   micrófono del coche (CarAudioRecord) + reconocimiento de voz en español + TTS
  data/AndroidServices   GPS, Geocoder y llamadas HTTP
```

- **Voz**: en Android 13 o superior, y con un Android Auto reciente, se escucha por el micrófono del coche y se transcribe con el reconocedor de Google. Si no se puede, se usa el micrófono por defecto. Las respuestas se leen bajando el volumen de la música, no cortándola.
- **Rutas alternativas**: Android Auto solo deja que otra app pase un destino al navegador, no una ruta completa. Para que vayas por la alternativa que eliges, Copiloto te manda primero a un punto propio de esa ruta. Al llegar, di "continuar a mi destino".

## Limitaciones conocidas

- Google no permite que una app cambie Android Auto ni Google Assistant. Esta app se añade a lo que ya tienes, no lo sustituye.
- En Play Store, Android Auto solo acepta ciertas categorías de apps. Gasolineras sí entra (puntos de interés), pero restaurantes no. Instalada a mano como se explica arriba, funciona todo.
- La ruta para buscar *de camino* es la que calculó Copiloto. Copiloto no ve la ruta que esté siguiendo Google Maps.
- Los servidores públicos de OSRM, Overpass y Nominatim tienen límites de uso pensados para uso personal.

## Desarrollo

```bash
./gradlew :core:test           # tests de órdenes de voz, diálogo y lectura de datos
./gradlew :app:assembleDebug   # APK (necesita el Android SDK)
```

Para probar sin coche, usa el **Desktop Head Unit** (DHU) del Android SDK.
