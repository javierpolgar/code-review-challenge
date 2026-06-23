package com.idealista.application;

import com.idealista.domain.Ad;
import com.idealista.domain.AdRepository;
import com.idealista.infrastructure.api.PublicAd;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdsServiceIntegrationTest {

    @Autowired
    private AdsService adsService;

    @Autowired
    private AdRepository adRepository;

    // --- Bug #1: El escore se reemplaza y no se suma ---

    @Test
    void calculateScores_garageWithOneSDPhotoShouldScore50() {
        adsService.calculateScores();

        Ad ad = findAdById(6);

        // Bug: el código hace score = 40 en vez de score += 40, por lo que devuelve 40 en vez de 50 que tendria que sumar de la foto
        assertThat(ad.getScore()).isEqualTo(50);
    }


    // --- Bug #2: los anuncios públicos deben ordenarse de mejor a peor (descendente) ---

    @Test
    void findPublicAds_shouldBeSortedFromHighestToLowestScore() {
        adsService.calculateScores();

        List<PublicAd> publicAds = adsService.findPublicAds();

        Map<Integer, Integer> scoreById = adRepository.findAllAds().stream()
                .collect(Collectors.toMap(Ad::getId, Ad::getScore));

        List<Integer> scores = publicAds.stream()
                .map(a -> scoreById.get(a.getId()))
                .collect(Collectors.toList());

        // Bug: el sort es ascendente (Comparator.comparing sin .reversed())
        for (int i = 0; i < scores.size() - 1; i++) {
            assertThat(scores.get(i))
                    .as("Score en posición %d (%d) debería ser >= score en posición %d (%d)",
                            i, scores.get(i), i + 1, scores.get(i + 1))
                    .isGreaterThanOrEqualTo(scores.get(i + 1));
        }
    }

    // --- Bug #3: NullPointerException cuando score es null ---

    @Test
    void findPublicAds_shouldNotThrowWhenScoresAreNull() {
        assertDoesNotThrow(() -> adsService.findPublicAds());
    }

    @Test
    void findQualityAds_shouldNotThrowWhenScoresAreNull() {
        assertDoesNotThrow(() -> adsService.findQualityAds());
    }

    // --- Bug #4: palabras clave no se detectan con mayúsculas ni con puntuación pegada ---

    @Test
    void calculateScores_keywordWithUppercaseInitialShouldStillScore() {
        adsService.calculateScores();

        Ad ad = findAdById(2);

        // Ad 2: FLAT, descripción: "Nuevo ático céntrico recién reformado. No deje pasar la oportunidad y adquiera este ático de lujo"
        // +20 foto HD
        // +5  descripción
        // +5  "Nuevo"     → "nuevo"     (bug: no detecta por mayúscula)
        // +5  "céntrico"
        // +5  "reformado."→ "reformado" (bug: no detecta por el punto pegado)
        // +5  "ático"
        // +40 completo
        // = 85
        assertThat(ad.getScore()).isEqualTo(85);
    }


    // --- Bug #5: irrelevantSince se machaca en cada recalculo ---

    @Test
    void calculateScores_irrelevantSinceShouldNotBeOverwrittenOnSubsequentCalculations() throws InterruptedException {
        adsService.calculateScores();

        // Ad 1: CHALET sin fotos → score 0 → irrelevante
        Date originalDate = findAdById(1).getIrrelevantSince();
        assertThat(originalDate).isNotNull();

        Thread.sleep(10); // forzamos que new Date() daría un valor distinto

        adsService.calculateScores();

        // Bug: el código siempre hace setIrrelevantSince(new Date()), machacando la fecha original
        assertThat(findAdById(1).getIrrelevantSince()).isEqualTo(originalDate);
    }

    private Ad findAdById(int id) {
        return adRepository.findAllAds().stream()
                .filter(a -> a.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Ad con id=" + id + " no encontrado"));
    }
}