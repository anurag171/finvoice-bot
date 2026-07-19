package com.finvoicebot.service.tts;

import com.finvoicebot.exception.SkillExecutionException;
import com.google.cloud.texttospeech.v1.AudioConfig;
import com.google.cloud.texttospeech.v1.AudioEncoding;
import com.google.cloud.texttospeech.v1.SsmlVoiceGender;
import com.google.cloud.texttospeech.v1.SynthesisInput;
import com.google.cloud.texttospeech.v1.SynthesizeSpeechResponse;
import com.google.cloud.texttospeech.v1.TextToSpeechClient;
import com.google.cloud.texttospeech.v1.VoiceSelectionParams;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Wraps Google Cloud Text-to-Speech to synthesize spoken payment confirmations.
 *
 * <p>Same credential resolution chain as {@link com.finvoicebot.service.ocr.OcrService}
 * (env var key.json -> ADC -> Workload Identity) — no auth code lives here.
 *
 * <p>Generated clips are written under {@code finvoice.audio.storage-dir} and served back via
 * the {@code /audio/**} static mapping (see {@link com.finvoicebot.config.WebConfig}), so the
 * frontend can simply drop the returned URL into an {@code <audio>} tag.
 */
@Slf4j
@Service
public class TextToSpeechService {

    @Value("${finvoice.audio.storage-dir:./audio-clips}")
    private String storageDir;

    @Value("${finvoice.audio.voice-name:en-IN-Neural2-A}")
    private String voiceName;

    @Value("${finvoice.audio.language-code:en-IN}")
    private String languageCode;

    /**
     * Synthesizes the given text to an mp3 file and returns its public-facing URL path.
     */
    public String synthesizeToFile(String text) {
        if (text == null || text.isBlank()) {
            throw new SkillExecutionException("ReadAloudSkill", "Nothing to read aloud yet.");
        }

        try (TextToSpeechClient client = TextToSpeechClient.create()) {
            SynthesisInput input = SynthesisInput.newBuilder().setText(text).build();
            VoiceSelectionParams voice = VoiceSelectionParams.newBuilder()
                    .setLanguageCode(languageCode)
                    .setName(voiceName)
                    .setSsmlGender(SsmlVoiceGender.NEUTRAL)
                    .build();
            AudioConfig audioConfig = AudioConfig.newBuilder()
                    .setAudioEncoding(AudioEncoding.MP3)
                    .build();

            SynthesizeSpeechResponse response = client.synthesizeSpeech(input, voice, audioConfig);
            ByteString audioContents = response.getAudioContent();

            Path dir = Path.of(storageDir);
            Files.createDirectories(dir);
            String filename = "confirmation-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".mp3";
            Path outputFile = dir.resolve(filename);
            Files.write(outputFile, audioContents.toByteArray());

            log.info("Synthesized {} bytes of audio to {}", audioContents.size(), outputFile);
            return "/audio/" + filename;

        } catch (IOException e) {
            log.error("Could not reach Google Text-to-Speech API", e);
            throw new SkillExecutionException("ReadAloudSkill",
                    "Could not reach the Text-to-Speech service. Check network access and credentials.", e);
        }
    }
}
