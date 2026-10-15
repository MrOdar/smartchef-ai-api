package com.smartchef.api.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.text.Normalizer;
import java.util.Locale;

public enum Difficulty {
    FACIL,
    MEDIA,
    DIFICIL;

    /**
     * Acepta variantes con acentos, mayusculas mezcladas o sinonimos en ingles
     * tal como puede devolverlas el modelo generativo.
     */
    @JsonCreator
    public static Difficulty fromText(String raw) {
        if (raw == null || raw.isBlank()) {
            return MEDIA;
        }
        String normalized = stripAccents(raw.trim()).toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "FACIL", "EASY", "BAJA", "SENCILLA" -> FACIL;
            case "DIFICIL", "HARD", "ALTA", "COMPLEJA" -> DIFICIL;
            default -> MEDIA;
        };
    }

    /** Descompone en NFD y descarta las marcas diacriticas combinantes. */
    private static String stripAccents(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char character = decomposed.charAt(i);
            if (Character.getType(character) != Character.NON_SPACING_MARK) {
                result.append(character);
            }
        }
        return result.toString();
    }
}
