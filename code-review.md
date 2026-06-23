# Code Review — Servicio de calidad de anuncios

---

## Bugs funcionales críticos

### Bug 1 — La bonificación por completitud reemplaza el score en vez de sumarse
**Localización:** `AdsServiceImpl#calculateScore` (línea 136)

El requisito dice que la completitud "proporciona **otros** 40 puntos". El código hace `score = Constants.FORTY`, reemplazando toda la puntuación acumulada por 40 en lugar de sumarle 40. Un anuncio completo con varias fotos HD debería puntuar más de 40, pero siempre recibe exactamente 40.

**Fix:** `score += Constants.FORTY;`

---

### Bug 2 — Los anuncios públicos se ordenan de peor a mejor en vez de mejor a peor
**Localización:** `AdsServiceImpl#findPublicAds` (línea 25)

El requisito dice "ordenados de mejor a peor". La ordenación actual es ascendente (el peor primero).

**Fix:** `ads.sort(Comparator.comparing(Ad::getScore).reversed());`

---

### Bug 3 — NullPointerException al filtrar por score antes de calcular puntuaciones
**Localización:** `InMemoryPersistence#findRelevantAds` (línea 70) y `InMemoryPersistence#findIrrelevantAds` (línea 79)

`getScore()` devuelve `Integer` (objeto). Todos los anuncios se inicializan con `score = null`. Si se llama a `findPublicAds()` o `findQualityAds()` sin haber llamado antes a `calculateScores()`, el unboxing automático de `null` a `int` lanza `NullPointerException`.

**Fix:** Añadir null-check antes de comparar: `.filter(x -> x.getScore() != null && x.getScore() >= Constants.FORTY)`

---

### Bug 4 — Las palabras clave no se detectan con mayúsculas ni con puntuación pegada
**Localización:** `AdsServiceImpl#calculateScore` (líneas 125-129)

Dos problemas combinados:

1. **Case-sensitive:** `"Nuevo"` no hace match con `"nuevo"`, ni `"Ático"` con `"ático"`. En los propios datos del dataset, el Ad 2 tiene `"Nuevo"` y el Ad 4 tiene `"Ático"` — ninguno suma los 5 puntos por esas palabras.

2. **Puntuación pegada:** el split por `" "` hace que `"reformado,"` o `"reformado."` sean tokens distintos de `"reformado"`. `List.contains()` usa `equals`, no comprueba si un elemento contiene la subcadena.

**Fix:** `Arrays.asList(description.toLowerCase().trim().split("\\s+"))`

---

### Bug 5 — `irrelevantSince` se sobreescribe en cada recalculo
**Localización:** `AdsServiceImpl#calculateScore` (línea 152)

El requisito dice "quiero poder ver los anuncios irrelevantes y **desde que fecha lo son**". Pero cada vez que se llama a `calculateScores()`, si el anuncio sigue siendo irrelevante se le machaca la fecha original con `new Date()`. Se pierde el dato de cuándo fue irrelevante por primera vez.

**Fix:** Solo asignar la fecha si aún no tiene una: `if (ad.getIrrelevantSince() == null) { ad.setIrrelevantSince(new Date()); }`

---

## Bugs de API / HTTP

### Bug 6 — Endpoint que modifica estado expuesto como GET, y código de respuesta incorrecto
**Localización:** `AdsController#calculateScore` (líneas 31-35)

Según REST, `GET` debe ser idempotente y sin efectos secundarios. Este endpoint recalcula y persiste las puntuaciones de todos los anuncios, lo cual es una mutación de estado. Debería ser `@PostMapping`.

Además devuelve `202 Accepted`, que implica procesamiento asíncrono. La operación es síncrona, por lo que lo correcto sería `204 No Content` o `200 OK`.

---

## Bugs en tests

### Bug 7 — Tests unitarios con IDs duplicados y sin assertions de negocio
**Localización:** `AdsServiceImplTest#calculateScoresTest`

Dos problemas:

1. Los métodos `relevantAd()` e `irrelevantAd()` construyen anuncios con `id = 1` ambos. En `InMemoryPersistence.save()` se hace `removeIf(x -> x.getId().equals(ad.getId()))`, por lo que el segundo `save` machacaría al primero.

2. El test solo verifica que se llama a `save` dos veces, sin comprobar ninguna lógica de negocio. Pasaría aunque `calculateScore` devolviera siempre 0. Lo correcto es usar `ArgumentCaptor` para capturar el objeto guardado y hacer assertions sobre su score e `irrelevantSince`.

---

## Diseño y arquitectura

### Bug 8 — `orElse(null)` puede introducir nulls en la lista de fotos
**Localización:** `InMemoryPersistence#mapToDomain(Integer)` (línea 106)

Si un `AdVO` referencia un `pictureId` que no existe en la lista de pictures, el método devuelve `null` y ese null se añade a `ad.getPictures()`. Más tarde, en `calculateScore`, al iterar las fotos, `picture.getQuality()` lanzaría `NullPointerException`.

Este bug es difícil de testear con la arquitectura actual porque requiere manipular estado interno privado de `InMemoryPersistence`. Esa dificultad es en sí misma un hallazgo: si no se puede reproducir fácilmente en un test, el diseño tiene un problema.

**Fix:** `.orElseThrow(() -> new IllegalStateException("Picture not found: " + pictureId))`

---

### Bug 9 — `Constants` con nombres sin significado de negocio
**Localización:** `Constants.java`

Las constantes solo traducen números a inglés (`ZERO`, `TEN`, `FORTY`...) sin aportar contexto de negocio. Además, `FORTY` se usa para dos conceptos distintos: la bonificación de completitud y el umbral de irrelevancia.

**Fix:** Nombrar las constantes según su propósito: `POINTS_HD_PHOTO`, `POINTS_COMPLETE`, `IRRELEVANT_SCORE_THRESHOLD`, `MIN_WORDS_FLAT_MEDIUM`, etc.

---

### Bug 10 — Inyección de dependencias por campo con `@Autowired`
**Localización:** `AdsServiceImpl` (línea 17) y `AdsController` (línea 17)

La inyección por campo oculta las dependencias, impide que los campos sean `final` y dificulta el testing sin Spring. La alternativa recomendada es inyección por constructor. Con Lombok se reduce a añadir `@RequiredArgsConstructor` y declarar los campos como `final`.

---

### Bug 11 — La lógica de scoring está en la capa de aplicación en vez del dominio
**Localización:** `AdsServiceImpl#calculateScore`

El método implementa reglas de negocio puras (cuánto vale una foto HD, qué palabras suman puntos, qué hace a un anuncio completo). Esto debería vivir en el dominio, no en un servicio de aplicación. `AdsServiceImpl` debería limitarse a orquestar: obtener los anuncios, pedirles que calculen su score y persistirlos.

---

### Bug 12 — Código de mapeo duplicado entre `findPublicAds` y `findQualityAds`
**Localización:** `AdsServiceImpl#findPublicAds` y `AdsServiceImpl#findQualityAds`

El mapeo de `Ad` a DTO se repite casi idéntico en los dos métodos. Si se añade un campo nuevo a `Ad` hay que actualizarlo en dos sitios. **Fix:** extraer métodos privados `toPublicAd(Ad)` y `toQualityAd(Ad)`, o usar una librería de mapeo como MapStruct.

---

### Bug 13 — `AdVO` y `PictureVO` usan `String` para tipología y calidad sin manejo de errores
**Localización:** `InMemoryPersistence#mapToDomain(AdVO)` (línea 88)

`Typology.valueOf(String)` y `Quality.valueOf(String)` lanzan `IllegalArgumentException` si el valor es inválido, sin ningún contexto útil (sin el id del anuncio, sin el valor que falló).

Fix inmediato: capturar y relanzar con mensaje claro. Fix de raíz: `AdVO` y `PictureVO` deberían usar directamente los enums `Typology` y `Quality`.

---

### Bug 14 — `InMemoryPersistence` es el problema de raíz de varios bugs anteriores
**Localización:** `InMemoryPersistence.java`

Los bugs 3, 8, 13 y 21 son consecuencia directa de implementar la persistencia a mano con `ArrayList`. La solución de raíz es usar **H2 + JPA**: el thread safety lo gestiona el pool de conexiones, el mapeo manual desaparece con `@Entity` y Spring Data, y los datos de test van en `data.sql` o en un `@BeforeEach`, no en el constructor de la clase de persistencia.

---

### Bug 15 — Uso de `java.util.Date` obsoleto
**Localización:** `Ad#irrelevantSince` (línea 17) y `AdVO#irrelevantSince`

`java.util.Date` está efectivamente obsoleto desde Java 8: es mutable, tiene una API confusa (el año empieza en 1900, los meses en 0) y no es thread-safe. **Fix:** sustituir por `Instant` de `java.time`, que también elimina el problema de mutabilidad del Bug 16.

---

### Bug 16 — `getPictures()` expone la lista interna del dominio
**Localización:** `Ad#getPictures` (línea 88)

Cualquier código externo puede modificar el estado interno de `Ad` sin pasar por ninguna lógica de dominio: `ad.getPictures().clear()` o `ad.getPictures().add(null)`. Esto rompe la encapsulación del dominio.

**Fix:** `return Collections.unmodifiableList(pictures);`

El mismo problema aplica a `setId()`: el identificador de un objeto de dominio no debería poder cambiarse una vez creado.

---

### Bug 17 — `split(" ")` no maneja espacios múltiples ni otros tipos de espacio
**Localización:** `AdsServiceImpl#calculateScore` (línea 107)

Con espacios múltiples se generan tokens vacíos que inflan el conteo de palabras: `"luminoso  nuevo".split(" ")` produce 3 elementos en vez de 2. Además, el split se ejecuta incluso cuando `description` es `""`, produciendo una lista con un elemento vacío en vez de una lista vacía.

**Fix:** `Arrays.asList(description.trim().split("\\s+"))` dentro del bloque `!description.isEmpty()`.

---

### Bug 18 — Mal uso de `Optional` como simple null-check
**Localización:** `AdsServiceImpl#calculateScore` (línea 97)

Usar `Optional.ofNullable` seguido de `isPresent()` + `get()` es exactamente equivalente a un `!= null`, pero más verboso y confuso. `Optional` está diseñado para ser usado como tipo de retorno, no como wrapper para null-checks internos.

---

### Bug 19 — `isComplete()` es una expresión booleana ilegible y difícil de mantener
**Localización:** `Ad#isComplete` (línea 55)

La lógica de completitud es una expresión de tres ramas largas en una sola línea, con condiciones duplicadas (`!pictures.isEmpty()` aparece tres veces, el check de descripción dos veces). Es muy difícil verificar que coincide con la especificación y muy fácil introducir un error al modificarla.

**Fix:** Mover la lógica al enum `Typology` mediante polimorfismo, de forma que cada tipología conozca sus propias reglas de completitud. O al menos extraer métodos privados con nombres expresivos como `hasPhotos()` y `hasDescription()`.

---

### Bug 20 — `calculateScore` mezcla variable local y estado del objeto
**Localización:** `AdsServiceImpl#calculateScore` (líneas 139-154)

Después de `ad.setScore(score)`, la variable local `score` se abandona y todos los checks posteriores usan `ad.getScore()`. Mezclar los dos hace el código más difícil de seguir y propenso a errores si se reordena el código.

**Fix:** Mantener `score` como variable local hasta el final, usar `Math.min`/`Math.max` para el clamping y escribir en el objeto una sola vez.

---

### Bug 21 — `save(Ad)` re-persiste todas las fotos aunque no hayan cambiado
**Localización:** `InMemoryPersistence#save(Ad)` (línea 55)

Cada vez que `calculateScores()` guarda un anuncio, también re-persiste todas sus fotos, aunque `calculateScores()` solo modifica `score` e `irrelevantSince`. Esto crea un efecto secundario inesperado: quien llama a `save(Ad)` no espera que se persistan también las fotos como consecuencia.

---

### Bug 22 — La capa de aplicación depende de DTOs de la capa de infraestructura
**Localización:** `AdsServiceImpl` (líneas 4-5)

`AdsServiceImpl` importa `PublicAd` y `QualityAd` de `infrastructure.api`, invirtiendo la dirección de dependencias correcta (`Infrastructure → Application → Domain`). La capa de aplicación debería devolver objetos de dominio y ser el `AdsController` quien haga el mapeo a los DTOs de la API.
