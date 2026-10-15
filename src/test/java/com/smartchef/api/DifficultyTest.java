package com.smartchef.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartchef.api.entity.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/** El modelo puede devolver la dificultad con acentos, en ingles o en minusculas. */
class DifficultyTest {

    @ParameterizedTest
    @CsvSource({
            "FACIL,FACIL",
            "facil,FACIL",
            "Fácil,FACIL",
            "easy,FACIL",
            "Sencilla,FACIL",
            "MEDIA,MEDIA",
            "media,MEDIA",
            "intermedia,MEDIA",
            "DIFICIL,DIFICIL",
            "Difícil,DIFICIL",
            "hard,DIFICIL",
            "compleja,DIFICIL"
    })
    @DisplayName("Normaliza las variantes que devuelve la IA")
    void normalizesModelVariants(String raw, Difficulty expected) {
        assertThat(Difficulty.fromText(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Usa MEDIA como valor por defecto seguro")
    void defaultsToMedia(String raw) {
        assertThat(Difficulty.fromText(raw)).isEqualTo(Difficulty.MEDIA);
    }

    @Test
    @DisplayName("Un valor desconocido no rompe el parseo")
    void unknownValueFallsBack() {
        assertThat(Difficulty.fromText("nivel-chef-estrella")).isEqualTo(Difficulty.MEDIA);
    }
}
