package com.idealista.application;

import com.idealista.domain.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdsServiceImplTest {

    @Mock
    private AdRepository adRepository;

    @InjectMocks
    private AdsServiceImpl scoreService;

    //Bug 7: Los 2 metodos privados que construyen el Ad tienen id=1, lo que hace que InMemoryPersistence.save() machaque el primero
    //removeIf(x -> x.getId().equals(ad.getId()))
    @Test
    public void calculateScoresTest() {
        when(adRepository.findAllAds()).thenReturn(Arrays.asList(irrelevantAd(), relevantAd()));
        scoreService.calculateScores();
        verify(adRepository).findAllAds();
        //No comprueba ninguna logica de negocio, solo verifica que ha llamado al metodo save 2 veces
        verify(adRepository, times(2)).save(any());
    }

    private Ad relevantAd() {
        return new Ad(1,
                Typology.FLAT,
                "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Cras dictum felis elit, vitae cursus erat blandit vitae. Maecenas eget efficitur massa. Maecenas ut dolor eget enim consequat iaculis vitae nec elit. Maecenas eu urna nec massa feugiat pharetra. Sed eu quam imperdiet orci lobortis fermentum. Sed odio justo, congue eget iaculis.",
                Arrays.asList(new Picture(1, "http://urldeprueba.com/1", Quality.HD), new Picture(2, "http://urldeprueba.com/2", Quality.HD)),
                50,
                null);
    }

    private Ad irrelevantAd() {
        return new Ad(1,
                Typology.FLAT,
                "",
                Collections.emptyList(),
                100,
                null);
    }

}