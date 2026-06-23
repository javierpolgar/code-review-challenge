# Code Review — Servicio de calidad de anuncios

---

## Legibilidad, arquitectura y diseño

### Legibilidad

El problema principal que veo al leer este código es que no se entiende qué hace sin dedicarle un buen rato. `calculateScore` hace siete cosas distintas en el mismo método, las constantes se llaman `FORTY` o `TEN` sin explicar para qué sirven, y `isComplete()` es una línea tan larga que hay que leerla varias veces para ver si cuadra con el requisito. Si alguien del equipo que no ha tocado el código tiene que entender cómo se puntúa un anuncio, necesita el README. Eso es una señal de que algo no está bien.

Sobre los comentarios: los que aparecen en el código los he añadido yo durante este code review para señalar los problemas. Pero precisamente eso refleja el problema, un código bien escrito no debería necesitar comentarios para explicarse. Si hay que comentar qué hace una linea, es que la línea está mal o está haciendo demasiado. Y si en algún momento hubiera que añadir alguno, lo suyo sería en inglés, que es el estándar del sector y lo que garantiza que cualquier persona del equipo pueda leerlo.

### Arquitectura

El proyecto tiene la estructura de capas típica (dominio, aplicación, infraestructura) pero en la práctica no la respeta. La lógica de negocio está en la capa de aplicación, el dominio es básicamente un POJO con getters y setters, y la capa de aplicación depende de DTOs de infraestructura. Es lo contrario de lo que plantea DDD.

Lo que yo haría diferente:

**Dominio rico.** `Ad` debería ser un agregado que extiende `AggregateRoot<AdId>`, con constructor privado y factory methods. Sin setters públicos. La lógica de scoring estaría dentro del propio `Ad` en un método `recalculateScore()`, no delegada a `AdsServiceImpl`.

**IDs tipados.** En vez de `Integer id` en `Ad` y `Picture`, usaría `AdId` y `PictureId` como Value Objects. Así el compilador detecta si mezclas IDs de entidades distintas, en vez de explotar en runtime.

**Casos de uso.** En vez de un `AdsService` con tres métodos mezclados, un caso de uso por operación: `CalculateScoresUseCase`, `FindPublicAdsUseCase`, `FindIrrelevantAdsUseCase`. Cada uno con un `execute()`, más fácil de testear y de entender.

**Domain Events.** Cuando un anuncio se vuelve irrelevante, el agregado registraría un `AdBecameIrrelevantEvent`. La capa de aplicación lo publicaría al cerrar la transacción. Así el dominio no sabe nada de infraestructura.

**JPA en vez de InMemoryPersistence.** Esto solo eliminaría de raíz varios de los bugs de aqui abajo (el NPE del score null, el orElse(null) de las fotos, el threading...). Los datos de test irían en un `data.sql`, no en el constructor de la clase de persistencia.

**Manejo de errores.** Excepciones de dominio con nombre (`AdNotFoundException`) y un `@ControllerAdvice` que las convierta en respuestas HTTP. Ahora mismo cualquier excepción devuelve un 500 genérico.

La estructura de paquetes que yo plantearía sería algo así:

```
com.idealista
├── domain
│   ├── ad.model          — Ad, Picture, AdId, PictureId, Typology, Quality, Score
│   ├── ad.port.in        — CalculateScoresUseCase, FindPublicAdsUseCase, FindIrrelevantAdsUseCase
│   ├── ad.port.out       — AdRepository
│   └── shared.exception  — DomainException, AdNotFoundException, InvalidScoreException
├── application
│   └── ad                — CalculateScoresService, FindPublicAdsService, FindIrrelevantAdsService
└── infrastructure
    ├── persistence
    │   └── ad            — AdEntity, PictureEntity, AdJpaRepository, AdRepositoryAdapter
    └── web
        ├── ad            — AdController, dto/*
        ├── config        — SecurityConfig
        └── exception     — GlobalExceptionHandler, ApiError
```

`Ad` sería un `AggregateRoot<AdId>` con `recalculateScore()` dentro. `Score`, `AdId` y `PictureId` serían Value Objects. Cada caso de uso tendría su propio service con un único `execute()`. `InMemoryPersistence` desaparece y `AdRepositoryAdapter` implementa `AdRepository` usando JPA.

---

## Bugs funcionales críticos

### Bug 1 — La bonificación por completitud reemplaza el score en vez de sumarse
**Localización:** `AdsServiceImpl#calculateScore` (línea 136)

El requisito dice que completar un anuncio "proporciona otros 40 puntos". El código hace `score = Constants.FORTY`, o sea que reemplaza todo lo acumulado hasta ese momento por 40. Un piso completo con dos fotos HD debería puntuar bastante más de 40, pero siempre sale exactamente 40.

**Fix:** `score += Constants.FORTY;`

---

### Bug 2 — Los anuncios públicos se ordenan al revés
**Localización:** `AdsServiceImpl#findPublicAds` (línea 25)

El requisito dice "de mejor a peor" y el código los ordena de peor a mejor.

**Fix:** `ads.sort(Comparator.comparing(Ad::getScore).reversed());`

---

### Bug 3 — NullPointerException si se llama a findPublicAds sin haber calculado scores antes
**Localización:** `InMemoryPersistence#findRelevantAds` (línea 70) y `#findIrrelevantAds` (línea 79)

Los anuncios se inicializan con `score = null`. El filtro hace `x.getScore() >= 40`, y como `getScore()` devuelve `Integer` (objeto), Java intenta hacer unboxing de null a int y explota con NPE.

**Fix:** `.filter(x -> x.getScore() != null && x.getScore() >= Constants.FORTY)`

---

### Bug 4 — Las palabras clave no detectan mayúsculas ni puntuación pegada
**Localización:** `AdsServiceImpl#calculateScore` (líneas 125-129)

Dos problemas:

1. **Case-sensitive:** `"Nuevo"` no hace match con `"nuevo"`. En los propios datos del dataset el Ad 2 tiene `"Nuevo"` y el Ad 4 tiene `"Ático"`, y ninguno suma esos 5 puntos.

2. **Puntuación pegada:** el split es por espacio, así que `"reformado,"` y `"reformado"` son cosas distintas. `List.contains()` usa `equals`, no busca subcadenas.

**Fix:** `Arrays.asList(description.toLowerCase().trim().split("\\s+"))`

---

### Bug 5 — `irrelevantSince` se pisa en cada recalculo
**Localización:** `AdsServiceImpl#calculateScore` (línea 152)

El requisito pide saber desde cuándo es irrelevante un anuncio. Pero cada vez que se llama a `calculateScores()`, si el anuncio sigue siendo irrelevante, se le machaca la fecha con `new Date()`. Se pierde la fecha original.

**Fix:** Solo setear si todavía no tiene fecha: `if (ad.getIrrelevantSince() == null) { ad.setIrrelevantSince(new Date()); }`

---

## Bugs de API

### Bug 6 — Endpoint que modifica datos expuesto como GET, y devuelve 202 en vez de 204
**Localización:** `AdsController#calculateScore` (líneas 31-35)

Un GET no debería tener efectos secundarios. Este recalcula y persiste puntuaciones, debería ser `@PostMapping`.

Ademas devuelve `202 Accepted`, que es para operaciones asíncronas. Esto es síncrono, así que lo correcto sería `204 No Content`.

---

## Bugs en tests

### Bug 7 — Los dos anuncios de prueba tienen el mismo id y el test no verifica nada de negocio
**Localización:** `AdsServiceImplTest#calculateScoresTest`

Dos cosas:

1. `relevantAd()` e `irrelevantAd()` tienen ambos `id = 1`. Si llegaran a `InMemoryPersistence`, el segundo `save` borraría al primero por el `removeIf`.

2. El test solo verifica que `save` se llama dos veces. Pasaría aunque `calculateScore` devolviera siempre 0. Habría que usar `ArgumentCaptor` para capturar los objetos guardados y verificar que el score es el esperado.

---

## Diseño y arquitectura

### Bug 8 — `orElse(null)` puede meter nulls en la lista de fotos
**Localización:** `InMemoryPersistence#mapToDomain(Integer)` (línea 106)

Si un `AdVO` referencia un `pictureId` que no existe en la lista de pictures, el método devuelve null y ese null entra en la lista de fotos del anuncio. Cuando `calculateScore` itera las fotos y llama a `picture.getQuality()`, NPE.

Este bug es dificil de testear con la arquitectura actual porque habría que manipular estado interno privado. Esa dificultad ya es un problema en sí misma.

**Fix:** `.orElseThrow(() -> new IllegalStateException("Picture not found: " + pictureId))`

---

### Bug 9 — Las constantes no dicen nada
**Localización:** `Constants.java`

`ZERO`, `TEN`, `FORTY`... son números con nombre en inglés, sin contexto de negocio. Encima `FORTY` se usa para dos cosas distintas: el bonus de completitud y el umbral de irrelevancia.

**Fix:** `POINTS_HD_PHOTO`, `POINTS_COMPLETE`, `IRRELEVANT_SCORE_THRESHOLD`, `MIN_WORDS_FLAT_MEDIUM`, etc.

---

### Bug 10 — Inyección por campo con `@Autowired`
**Localización:** `AdsServiceImpl` (línea 17) y `AdsController` (línea 17)

Oculta las dependencias, impide que los campos sean `final` y complica el testing sin Spring.

**Fix:** Inyección por constructor. Con Lombok basta con `@RequiredArgsConstructor` y declarar los campos `final`.

---

### Bug 11 — La lógica de scoring está en la capa de aplicación
**Localización:** `AdsServiceImpl#calculateScore`

Las reglas de cuánto vale una foto, qué palabras suman puntos o qué hace a un anuncio completo son lógica de dominio pura. No deberían estar en `AdsServiceImpl`. El servicio debería limitarse a orquestar, no a implementar las reglas.

---

### Bug 12 — Código de mapeo duplicado
**Localización:** `AdsServiceImpl#findPublicAds` y `#findQualityAds`

El mapeo de `Ad` a DTO es prácticamente idéntico en los dos métodos. Si se añade un campo hay que tocarlo en dos sitios.

**Fix:** Métodos privados `toPublicAd(Ad)` y `toQualityAd(Ad)`, o directamente MapStruct.

---

### Bug 13 — `Typology.valueOf(String)` sin manejo de errores
**Localización:** `InMemoryPersistence#mapToDomain(AdVO)` (línea 88)

Si el String no coincide con ningún valor del enum, lanza `IllegalArgumentException` sin ningún contexto útil. No sabes qué anuncio ha fallado ni qué valor tenía.

Fix inmediato: capturar y relanzar con contexto. Fix de raíz: usar los enums directamente en `AdVO` y `PictureVO`.

---

### Bug 14 — `InMemoryPersistence` es el origen de varios bugs
**Localización:** `InMemoryPersistence.java`

Los bugs 3, 8, 13 y 21 son consecuencia de hacer la persistencia a mano con un `ArrayList`. Con H2 + JPA desaparecen solos: el threading lo gestiona el pool de conexiones, el mapeo manual lo reemplaza Spring Data, y los datos de test van en `data.sql`.

---

### Bug 15 — `java.util.Date` está obsoleto
**Localización:** `Ad#irrelevantSince` (línea 17) y `AdVO#irrelevantSince`

`Date` es mutable, tiene una API confusa y no es thread-safe. Desde Java 8 existe `Instant` que soluciona todo esto.

---

### Bug 16 — `getPictures()` devuelve la lista interna
**Localización:** `Ad#getPictures` (línea 88)

Cualquiera puede hacer `ad.getPictures().clear()` desde fuera y romper el estado del anuncio sin que nadie se entere.

**Fix:** `return Collections.unmodifiableList(pictures);`

Lo mismo con `setId()`: el id de una entidad de dominio no debería poder cambiar una vez creado.

---

### Bug 17 — `split(" ")` no maneja bien los espacios
**Localización:** `AdsServiceImpl#calculateScore` (línea 107)

Con espacios dobles se generan tokens vacíos que cuentan como palabras. `"luminoso  nuevo".split(" ")` da 3 elementos en vez de 2. Tambien se ejecuta aunque la descripción esté vacía, devolviendo una lista con un string vacío en vez de una lista vacía.

**Fix:** `description.trim().split("\\s+")` dentro del bloque `!description.isEmpty()`.

---

### Bug 18 — `Optional` usado como null-check
**Localización:** `AdsServiceImpl#calculateScore` (línea 97)

`Optional.ofNullable` + `isPresent()` + `get()` es exactamente lo mismo que `!= null` pero más verboso. `Optional` es para tipos de retorno, no para esto.

---

### Bug 19 — `isComplete()` es ilegible
**Localización:** `Ad#isComplete` (línea 55)

Una sola expresión booleana con tres ramas largas y condiciones repetidas. Es muy difícil verificar que cuadra con el requisito y muy fácil meter un bug al modificarla.

**Fix:** Mover la lógica al enum `Typology` para que cada tipología sepa sus propias reglas, o al menos extraer `hasPhotos()` y `hasDescription()` como métodos privados.

---

### Bug 20 — `calculateScore` mezcla la variable local con el estado del objeto
**Localización:** `AdsServiceImpl#calculateScore` (líneas 139-154)

Después de `ad.setScore(score)`, el código usa `ad.getScore()` para las comparaciones siguientes en vez de seguir usando la variable local `score`. Es inconsistente y confuso.

**Fix:** Mantener `score` como variable local hasta el final y escribir en el objeto una sola vez. El clamping se simplifica con `Math.min` y `Math.max`.

---

### Bug 21 — `save(Ad)` guarda las fotos aunque no hayan cambiado
**Localización:** `InMemoryPersistence#save(Ad)` (línea 55)

Cada vez que se guarda un anuncio se re-persisten todas sus fotos como efecto secundario, aunque `calculateScores()` solo modifica el score y la fecha. Es trabajo innecesario y un comportamiento que no se espera al llamar a `save`.

---

### Bug 22 — La capa de aplicación depende de DTOs de infraestructura
**Localización:** `AdsServiceImpl` (líneas 4-5)

`AdsServiceImpl` importa `PublicAd` y `QualityAd` de `infrastructure.api`. La dirección correcta es `Infrastructure → Application → Domain`, no al revés. El mapeo a DTOs de API debería hacerlo el controller, no el servicio de aplicación.