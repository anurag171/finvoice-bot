package com.finvoicebot.service.stt;

import com.finvoicebot.exception.SkillExecutionException;
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1.RecognitionAudio;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.RecognizeResponse;
import com.google.cloud.speech.v1.SpeechClient;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.SpeechRecognitionResult;
import com.google.cloud.speech.v1.SpeechSettings;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Wraps Google Cloud Speech-to-Text to transcribe spoken audio clips.
 *
 * <p>Same credential resolution chain as {@link com.finvoicebot.service.tts.TextToSpeechService}
 * (env var key.json -> ADC -> Workload Identity).
 */
@Slf4j
@Service
public class SpeechToTextService {

    @Value("${finvoice.audio.language-code:en-IN}")
    private String languageCode;

    @Value("${finvoice.ocr.credentials-file:}")
    private String credentialsFilePath;

    /**
     * Transcribes an audio file located at the given path into text.
     *
     * @param audioFilePath Local path to the recorded audio file (e.g. WAV/MP3)
     * @return Transcribed text string
     */
    public String transcribeFile(Path audioFilePath) {
        if (audioFilePath == null || !Files.exists(audioFilePath)) {
            throw new SkillExecutionException("SpeechToTextSkill", "Audio file does not exist.");
        }

        try {
            byte[] audioBytes = Files.readAllBytes(audioFilePath);
            return transcribeBytes(audioBytes);
        } catch (IOException e) {
            log.error("Failed to read audio file from path: {}", audioFilePath, e);
            throw new SkillExecutionException("SpeechToTextSkill", "Could not read audio file.", e);
        }
    }

    /**
     * Transcribes raw audio byte array into text.
     *
     * @param audioBytes Byte array of audio content
     * @return Transcribed text string
     */
    public String transcribeBytes(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new SkillExecutionException("SpeechToTextSkill", "No audio data provided to transcribe.");
        }

        log.info("Transcribing audio content ({} bytes)", audioBytes.length);

        try (SpeechClient client = SpeechClient.create(buildSettings())) {
            ByteString audioData = ByteString.copyFrom(audioBytes);

            RecognitionConfig config = RecognitionConfig.newBuilder()
                    .setLanguageCode(languageCode)
                    .setEnableAutomaticPunctuation(true)
                    // Auto-detect encoding based on headers (works for MP3, WAV, FLAC, OGG, etc.)
                    .setEncoding(RecognitionConfig.AudioEncoding.ENCODING_UNSPECIFIED) 
                    .build();

            RecognitionAudio audio = RecognitionAudio.newBuilder()
                    .setContent(audioData)
                    .build();

            RecognizeResponse response = client.recognize(config, audio);
            List<SpeechRecognitionResult> results = response.getResultsList();

            if (results.isEmpty()) {
                log.warn("Speech recognition completed with no results.");
                return "";
            }

            StringBuilder transcription = new StringBuilder();
            for (SpeechRecognitionResult result : results) {
                if (!result.getAlternativesList().isEmpty()) {
                    SpeechRecognitionAlternative bestAlternative = result.getAlternativesList().get(0);
                    transcription.append(bestAlternative.getTranscript()).append(" ");
                }
            }

            String resultText = transcription.toString().trim();
            log.info("Transcription completed successfully: \"{}\"", resultText);
            return resultText;

        } catch (IOException e) {
            log.error("Could not reach Google Speech-to-Text API", e);
            throw new SkillExecutionException("SpeechToTextSkill",
                    "Could not reach the Speech-to-Text service. Check network access and credentials.", e);
        }
    }

    private SpeechSettings buildSettings() throws IOException {
        if (credentialsFilePath != null && !credentialsFilePath.isBlank()) {
            try (InputStream credentialsStream = resolveCredentialsStream()) {
                GoogleCredentials credentials = GoogleCredentials.fromStream(credentialsStream)
                        .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
                return SpeechSettings.newBuilder()
                        .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                        .build();
            }
        }

        // Uses default credential resolution chain (env var key.json -> ADC -> Workload Identity).
        return SpeechSettings.newBuilder().build();
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