package com.idealista.infrastructure.api;

import java.util.List;

import com.idealista.application.AdsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController

public class AdsController {

    //Bug 10: Inyección de dependencias on Autowired
    //Fix: Usar lombok con el @RequiredArgsConstructor
    @Autowired
    private AdsService adsService;

    @GetMapping("/ads/quality")
    public ResponseEntity<List<QualityAd>> qualityListing() {
        return ResponseEntity.ok(adsService.findQualityAds());
    }

    @GetMapping("/ads/public")
    public ResponseEntity<List<PublicAd>> publicListing() {
        return ResponseEntity.ok(adsService.findPublicAds());
    }

    //Bug 6: Está modificando el estado del score y utiliza un Get
    @GetMapping("/ads/score")
    public ResponseEntity<Void> calculateScore() {
        adsService.calculateScores();
        //Además devuelve un 202, Accepted, cuando se suele utilizar para operaciones asincronas, lo suyo ess un 204 o un 200
        return ResponseEntity.accepted().build();
    }
}
