package com.smartchef.api;

import com.smartchef.api.dto.GeminiRecipeResult;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import javax.imageio.ImageIO;

/** Utilidades compartidas por las pruebas: imagenes sinteticas y respuestas de IA simuladas. */
public final class TestDataFactory {

    private TestDataFactory() {
    }

    /** Genera un JPEG real en memoria del tamano indicado. */
    public static byte[] jpegImage(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(220, 120, 60));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(40, 150, 90));
            graphics.fillOval(width / 8, height / 8, width / 2, height / 2);
        } finally {
            graphics.dispose();
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    public static byte[] smallJpeg() throws IOException {
        return jpegImage(120, 90);
    }

    /**
     * JPEG con ruido pseudoaleatorio: comprime mal a proposito, de forma que
     * supera 1MB y activa la ruta de redimensionado del servicio.
     */
    public static byte[] noisyJpeg(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", output);
        return output.toByteArray();
    }

    /** Resultado de IA simulado con 3 recetas completas. */
    public static GeminiRecipeResult sampleResult() {
        return new GeminiRecipeResult(
                List.of("tomate", "huevo", "cebolla", "queso"),
                List.of(
                        recipe("Tortilla campesina de tomate", 20, "FACIL", 380),
                        recipe("Huevos rotos con cebolla caramelizada", 30, "MEDIA", 520),
                        recipe("Gratinado rustico de queso y tomate", 45, "DIFICIL", 610)));
    }

    /** Resultado de IA vacio: la imagen no contenia alimentos. */
    public static GeminiRecipeResult emptyResult() {
        return new GeminiRecipeResult(List.of(), List.of());
    }

    private static GeminiRecipeResult.GeneratedRecipe recipe(String title,
                                                             int minutes,
                                                             String difficulty,
                                                             int calories) {
        return new GeminiRecipeResult.GeneratedRecipe(
                title,
                "Receta de aprovechamiento con los ingredientes de tu despensa.",
                minutes,
                difficulty,
                calories,
                List.of(
                        "Prepara y trocea los ingredientes disponibles.",
                        "Cocina a fuego medio durante " + (minutes / 2) + " minutos.",
                        "Sazona al gusto y sirve caliente."),
                List.of(
                        new GeminiRecipeResult.GeneratedIngredient("tomate", "2 unidades", true),
                        new GeminiRecipeResult.GeneratedIngredient("huevo", "3 unidades", true),
                        new GeminiRecipeResult.GeneratedIngredient("albahaca fresca", "1 ramita", false)));
    }
}
