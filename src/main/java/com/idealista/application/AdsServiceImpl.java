package com.idealista.application;

import com.idealista.domain.*;
import com.idealista.infrastructure.api.PublicAd;
import com.idealista.infrastructure.api.QualityAd;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class AdsServiceImpl implements AdsService {

    //Bug 10: Inyección de dependencias on Autowired
    //Fix: Usar lombok con el @RequiredArgsConstructor
    @Autowired
    private AdRepository adRepository;

    @Override
    public List<PublicAd> findPublicAds() {
        List<Ad> ads = adRepository.findRelevantAds();
        //Bug 2: Esta ordenación es ascendente, el peor deberia ir primero1
        //Fix: ads.sort(Comparator.comparing(Ad::getScore).reversed());
        ads.sort(Comparator.comparing(Ad::getScore));

        List<PublicAd> result = new ArrayList<>();
        for (Ad ad: ads) {
            PublicAd publicAd = new PublicAd();
            //Bug 12: Codigo duplicado en la linea 50
            //FIx: Utilizar la librería de mapeo MapStruct
            publicAd.setDescription(ad.getDescription());
            publicAd.setGardenSize(ad.getGardenSize());
            publicAd.setHouseSize(ad.getHouseSize());
            publicAd.setId(ad.getId());
            publicAd.setPictureUrls(ad.getPictures().stream().map(Picture::getUrl).collect(Collectors.toList()));
            publicAd.setTypology(ad.getTypology().name());

            result.add(publicAd);
        }
        return result;
    }

    @Override
    public List<QualityAd> findQualityAds() {
        List<Ad> ads = adRepository.findIrrelevantAds();

        List<QualityAd> result = new ArrayList<>();
        for (Ad ad: ads) {
            QualityAd qualityAd = new QualityAd();
            qualityAd.setDescription(ad.getDescription());
            qualityAd.setGardenSize(ad.getGardenSize());
            qualityAd.setHouseSize(ad.getHouseSize());
            qualityAd.setId(ad.getId());
            qualityAd.setPictureUrls(ad.getPictures().stream().map(Picture::getUrl).collect(Collectors.toList()));
            qualityAd.setTypology(ad.getTypology().name());
            qualityAd.setScore(ad.getScore());
            qualityAd.setIrrelevantSince(ad.getIrrelevantSince());

            result.add(qualityAd);
        }

        return result;
    }

    //Bug 11: Logica del scoring en la capa de aplicación en vez de en el dominio
    @Override
    public void calculateScores() {
        adRepository
                .findAllAds()
                .forEach(this::calculateScore);
    }

    private void calculateScore(Ad ad) {
        int score = Constants.ZERO;

        //Calcular puntuación por fotos
        if (ad.getPictures().isEmpty()) {
            score -= Constants.TEN; //Si no hay fotos restamos 10 puntos
        } else {
            for (Picture picture: ad.getPictures()) {
                if(Quality.HD.equals(picture.getQuality())) {
                    score += Constants.TWENTY; //Cada foto en alta definición aporta 20 puntos
                } else {
                    score += Constants.TEN; //Cada foto normal aporta 10 puntos
                }
            }
        }

        //Calcular puntuación por descripción
        //Bug 18: Mal uso del Optional
        //Fix:  Optional.ofNullable(ad.getDescription())
        //.filter(d -> !d.isEmpty())
        //.ifPresent(description -> {
        //score += Constants.FIVE;
        //resto de la logica
        Optional<String> optDesc = Optional.ofNullable(ad.getDescription());

        if (optDesc.isPresent()) {
            String description = optDesc.get();

            if (!description.isEmpty()) {
                score += Constants.FIVE;
            }
            //Bug 17: el split no maneja espacios multiples ni otros tipos de espacios
            //Fix: combinar trim() con \\s+ para cubrir cualquier tipo de espacio en blanco
            List<String> wds = Arrays.asList(description.split(" ")); //número de palabras
            if (Typology.FLAT.equals(ad.getTypology())) {
                if (wds.size() >= Constants.TWENTY && wds.size() <= Constants.FORTY_NINE) {
                   score += Constants.TEN;
                }

                if (wds.size() >= Constants.FIFTY) {
                    score += Constants.THIRTY;
                }
            }

            if (Typology.CHALET.equals(ad.getTypology())) {
                if (wds.size() >= Constants.FIFTY) {
                    score += Constants.TWENTY;
                }
            }
            //Bug 4: CaseSensitive: no hace match nuevo con Nuevo
            //Y por ejemplo como separa por el split de " " puede llegar un reformado. como pasa en el ejemplo y suma la puntuacion
            if (wds.contains("luminoso")) score += Constants.FIVE;
            if (wds.contains("nuevo")) score += Constants.FIVE;
            if (wds.contains("céntrico")) score += Constants.FIVE;
            if (wds.contains("reformado")) score += Constants.FIVE;
            if (wds.contains("ático")) score += Constants.FIVE;
        }

        //Calcular puntuación por completitud
        if (ad.isComplete()) {
            //BUG1 No esta sumando
            //Fix score += Constants.FORTY;
            score = Constants.FORTY;
        }

        ad.setScore(score);

        //Bug 20: Esta leyendo el objeto en vez de usar la variable local objeto
        if (ad.getScore() < Constants.ZERO) {
            ad.setScore(Constants.ZERO);
        }

        if (ad.getScore() > Constants.ONE_HUNDRED) {
            ad.setScore(Constants.ONE_HUNDRED);
        }

        if (ad.getScore() < Constants.FORTY) {
            //Bug 5: No hace comprobación si el anuncio tiene IrrelevantSince, directamente lo machaca
            ad.setIrrelevantSince(new Date());
        } else {
            ad.setIrrelevantSince(null);
        }

        adRepository.save(ad);
    }


}
