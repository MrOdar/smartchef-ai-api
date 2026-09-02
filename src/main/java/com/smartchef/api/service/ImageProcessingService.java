package com.smartchef.api.service;

import com.smartchef.api.config.ImageProperties;
import com.smartchef.api.exception.InvalidImageException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Valida y normaliza las fotos subidas desde celulares. Las imagenes grandes se
 * redimensionan en memoria a un ancho maximo configurable antes de enviarlas a
 * Gemini, lo que reduce drasticamente la latencia de la peticion.
 */
@Service
public class ImageProcessingService {

    private static final Logger log = LoggerFactory.getLogger(ImageProcessingService.class);

    private static final Set<String> SUPPORTED_TYPES =
            Set.of("image/jpeg", "image/jpg", "image/png", "image/webp");

    private final ImageProperties properties;

    public ImageProcessingService(ImageProperties properties) {
        this.properties = properties;
    }

    /** Resultado normalizado: bytes listos para Base64 y su MIME type real. */
    public record ProcessedImage(byte[] data, String mimeType) {
    }

    public ProcessedImage process(MultipartFile file) {
        validate(file);

        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new InvalidImageException("No se pudo leer la imagen enviada");
        }

        String mimeType = normalizeMimeType(file.getContentType());

        if (original.length <= properties.resizeThresholdBytes()) {
            return new ProcessedImage(original, mimeType);
        }

        return resize(original, mimeType);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidImageException("Debes adjuntar una imagen en el campo 'image'");
        }
        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_TYPES.contains(contentType.toLowerCase())) {
            throw new InvalidImageException(
                    "Formato no soportado. Usa una imagen JPEG, PNG o WEBP (recibido: " + contentType + ")");
        }
    }

    private String normalizeMimeType(String contentType) {
        if (contentType == null) {
            return "image/jpeg";
        }
        String lower = contentType.toLowerCase();
        return "image/jpg".equals(lower) ? "image/jpeg" : lower;
    }

    /**
     * Redimensiona manteniendo la relacion de aspecto. Si el formato no se puede
     * decodificar (por ejemplo WEBP sin plugin ImageIO), devuelve los bytes
     * originales en lugar de fallar: Gemini acepta ambos.
     */
    private ProcessedImage resize(byte[] original, String mimeType) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(original));
            if (source == null) {
                log.debug("ImageIO no pudo decodificar {}; se envia la imagen original", mimeType);
                return new ProcessedImage(original, mimeType);
            }

            int maxWidth = properties.maxWidth();
            if (source.getWidth() <= maxWidth) {
                return new ProcessedImage(original, mimeType);
            }

            int targetWidth = maxWidth;
            int targetHeight = Math.max(1,
                    Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));

            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(scaled, "jpg", output)) {
                return new ProcessedImage(original, mimeType);
            }

            byte[] resized = output.toByteArray();
            log.info("Imagen redimensionada de {} KB ({}px) a {} KB ({}px)",
                    original.length / 1024, source.getWidth(), resized.length / 1024, targetWidth);
            return new ProcessedImage(resized, "image/jpeg");

        } catch (IOException e) {
            log.warn("Fallo el redimensionado, se usara la imagen original: {}", e.getMessage());
            return new ProcessedImage(original, mimeType);
        }
    }
}
