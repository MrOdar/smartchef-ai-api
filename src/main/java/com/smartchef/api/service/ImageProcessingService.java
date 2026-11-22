package com.smartchef.api.service;

import com.smartchef.api.config.ImageProperties;
import com.smartchef.api.exception.InvalidImageException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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

        // El tipo sale de los bytes, no de la cabecera que manda el cliente.
        String mimeType = detectMimeType(original);
        rejectOversizedDimensions(original);

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
            throw new InvalidImageException("Formato no soportado. Usa una imagen JPEG, PNG o WEBP");
        }
    }

    /**
     * Identifica el formato por su firma. Un archivo que se anuncia como
     * image/jpeg pero no lo es se rechaza aqui y no llega a Gemini.
     */
    static String detectMimeType(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A) {
            return "image/png";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        throw new InvalidImageException("El archivo no es una imagen JPEG, PNG o WEBP valida");
    }

    /**
     * Lee el ancho y el alto de la cabecera sin decodificar la imagen. Un PNG
     * de pocos KB puede declarar 30000x30000 px, que al decodificarse son GB de
     * memoria en un contenedor de 512 MB. Si ImageIO no conoce el formato
     * (WEBP sin plugin) no lo decodificara despues, asi que no hay riesgo.
     */
    private void rejectOversizedDimensions(byte[] data) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = stream == null ? null : ImageIO.getImageReaders(stream);
            if (readers == null || !readers.hasNext()) {
                return;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > properties.maxPixels()) {
                    throw new InvalidImageException(
                            "La imagen tiene demasiada resolucion. Usa una foto de como maximo "
                                    + properties.maxPixels() / 1_000_000 + " megapixeles");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new InvalidImageException("No se pudo leer la imagen enviada");
        }
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
