package com.smartchef.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartchef.api.config.ImageProperties;
import com.smartchef.api.exception.InvalidImageException;
import com.smartchef.api.service.ImageProcessingService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Validacion de formatos y redimensionado en memoria de fotos de celular. */
class ImageProcessingServiceTest {

    private ImageProcessingService service;

    @BeforeEach
    void setUp() {
        service = new ImageProcessingService(new ImageProperties(1024, 1_048_576L));
    }

    @Test
    @DisplayName("Acepta un JPEG pequeno sin modificarlo")
    void keepsSmallImageUntouched() throws IOException {
        byte[] original = TestDataFactory.smallJpeg();
        MockMultipartFile file =
                new MockMultipartFile("image", "despensa.jpg", "image/jpeg", original);

        ImageProcessingService.ProcessedImage processed = service.process(file);

        assertThat(processed.data()).isEqualTo(original);
        assertThat(processed.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("Redimensiona a 1024px de ancho las fotos de mas de 1MB")
    void resizesLargeImageToMaxWidth() throws IOException {
        // Una imagen de 3000x2200 con ruido supera comodamente 1MB en JPEG.
        byte[] original = TestDataFactory.noisyJpeg(3000, 2200);
        assertThat(original.length).isGreaterThan(1_048_576);

        MockMultipartFile file =
                new MockMultipartFile("image", "refri.jpg", "image/jpeg", original);

        ImageProcessingService.ProcessedImage processed = service.process(file);

        assertThat(processed.data().length).isLessThan(original.length);
        assertThat(processed.mimeType()).isEqualTo("image/jpeg");

        BufferedImage resized = ImageIO.read(new ByteArrayInputStream(processed.data()));
        assertThat(resized).isNotNull();
        assertThat(resized.getWidth()).isEqualTo(1024);
        // Mantiene la relacion de aspecto 3000:2200.
        assertThat(resized.getHeight()).isEqualTo(Math.round(2200 * (1024 / 3000f)));
    }

    @Test
    @DisplayName("Normaliza image/jpg a image/jpeg")
    void normalizesJpgMimeType() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "image", "foto.jpg", "image/jpg", TestDataFactory.smallJpeg());

        assertThat(service.process(file).mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("Rechaza formatos no soportados")
    void rejectsUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile(
                "image", "receta.pdf", "application/pdf", new byte[] { 1, 2, 3 });

        assertThatThrownBy(() -> service.process(file))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("Formato no soportado");
    }

    @Test
    @DisplayName("Rechaza una peticion sin imagen")
    void rejectsEmptyFile() {
        MockMultipartFile file =
                new MockMultipartFile("image", "vacio.jpg", "image/jpeg", new byte[0]);

        assertThatThrownBy(() -> service.process(file))
                .isInstanceOf(InvalidImageException.class)
                .hasMessageContaining("adjuntar una imagen");
    }

    @Test
    @DisplayName("Devuelve los bytes originales si ImageIO no puede decodificar el formato")
    void fallsBackWhenFormatCannotBeDecoded() {
        // Payload WEBP de mas de 1MB que ImageIO no sabe leer por defecto.
        byte[] fakeWebp = new byte[1_200_000];
        fakeWebp[0] = 'R';
        fakeWebp[1] = 'I';
        fakeWebp[2] = 'F';
        fakeWebp[3] = 'F';

        MockMultipartFile file =
                new MockMultipartFile("image", "despensa.webp", "image/webp", fakeWebp);

        ImageProcessingService.ProcessedImage processed = service.process(file);

        assertThat(processed.data()).isEqualTo(fakeWebp);
        assertThat(processed.mimeType()).isEqualTo("image/webp");
    }
}
