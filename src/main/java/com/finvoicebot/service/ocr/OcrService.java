package com.finvoicebot.service.ocr;

import com.finvoicebot.exception.SkillExecutionException;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * Wraps the Google Cloud Vision API's DOCUMENT_TEXT_DETECTION feature, which is tuned for
 * dense printed text (invoices, receipts, forms) rather than TEXT_DETECTION's scene-text mode.
 *
 * <p>Authentication resolves in this order (see application.yml / README):
 * <ol>
 *   <li>{@code GOOGLE_APPLICATION_CREDENTIALS} env var pointing at a service-account key.json</li>
 *   <li>Application Default Credentials (gcloud auth application-default login)</li>
 *   <li>Workload Identity Federation, when running on GCP/GKE with no static key at all</li>
 * </ol>
 * No code path in this class references a key file directly — credential resolution is entirely
 * delegated to the client library, so swapping auth mechanisms requires zero code changes.
 */
@Slf4j
@Service
public class OcrService {

    @Value("${finvoice.ocr.language-hints:en}")
    private List<String> languageHints;

    @Value("${finvoice.ocr.credentials-file:}")
    private String credentialsFilePath;

    /**
     * Runs OCR against the given image bytes and returns the full extracted text block.
     *
     * @param imageBytes    raw bytes of the uploaded invoice image (jpg/png/pdf-page)
     * @param sourceForLogs filename or identifier, used only for log/audit context
     * @return the concatenated text Vision detected, or empty string if none found
     */
    public String extractText(byte[] imageBytes, String sourceForLogs) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new SkillExecutionException("OcrService", "No invoice image was provided to scan.");
        }

        try (ImageAnnotatorClient client = ImageAnnotatorClient.create(buildSettings())) {
            Image image = Image.newBuilder().setContent(ByteString.copyFrom(imageBytes)).build();
            Feature feature = Feature.newBuilder().setType(Feature.Type.DOCUMENT_TEXT_DETECTION).build();

            AnnotateImageRequest.Builder requestBuilder = AnnotateImageRequest.newBuilder()
                    .addFeatures(feature)
                    .setImage(image);
            if (languageHints != null && !languageHints.isEmpty()) {
                requestBuilder.setImageContext(
                        com.google.cloud.vision.v1.ImageContext.newBuilder()
                                .addAllLanguageHints(languageHints)
                                .build());
            }

            List<AnnotateImageRequest> requests = Collections.singletonList(requestBuilder.build());
            BatchAnnotateImagesResponse batchResponse = client.batchAnnotateImages(requests);
            AnnotateImageResponse response = batchResponse.getResponses(0);

            if (response.hasError()) {
                log.error("Vision API error for {}: {}", sourceForLogs, response.getError().getMessage());
                throw new SkillExecutionException("OcrService",
                        "OCR failed: " + response.getError().getMessage());
            }

            String text = response.hasFullTextAnnotation()
                    ? response.getFullTextAnnotation().getText()
                    : "";
            log.info("OCR extracted {} chars from {}", text.length(), sourceForLogs);
            return text;

        } catch (IOException e) {
            log.error("Could not reach Google Vision API", e);
            throw new SkillExecutionException("OcrService",
                    "Could not reach the OCR service. Check network access and GOOGLE_APPLICATION_CREDENTIALS.", e);
        }
    }

    private ImageAnnotatorSettings buildSettings() throws IOException {
        if (credentialsFilePath != null && !credentialsFilePath.isBlank()) {
            try (InputStream credentialsStream = resolveCredentialsStream()) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream)
                        .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
                return ImageAnnotatorSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                        .build();
            }
        }

        // Uses default credential resolution chain (env var key.json -> ADC -> Workload Identity).
        return ImageAnnotatorSettings.newBuilder().build();
    }

    private InputStream resolveCredentialsStream() throws IOException {
        if (credentialsFilePath.startsWith("classpath:")) {
            String resourcePath = credentialsFilePath.substring("classpath:".length());
            InputStream stream = getClass().getClassLoader().getResourceAsStream(resourcePath);
            if (stream == null) {
                throw new IOException("Google credentials resource not found: " + resourcePath);
            }
            return stream;
        }
        return new FileInputStream(credentialsFilePath);
    }
}
