/*
 * Copyright 2025-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ai.sellar.agent.application.service;

import com.ai.sellar.agent.domain.event.AgentEndEvent;
import com.ai.sellar.agent.domain.event.AgentChunkEvent;
import com.ai.sellar.agent.domain.event.STTOutputEvent;
import com.ai.sellar.agent.domain.event.STTChunkEvent;
import com.ai.sellar.agent.domain.event.TTSChunkEvent;
import com.ai.sellar.agent.domain.event.VoiceAgentEvent;
import com.ai.sellar.agent.infrastructure.freeswitch.FreeSwitchClient;
import com.ai.sellar.agent.infrastructure.freeswitch.MediaBridgeControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CallMediaBridge - Bridges FreeSWITCH call audio to the AI pipeline.
 *
 * Architecture:
 * 1. Starts recording call audio via FreeSWITCH ESL (uuid_record)
 * 2. Monitors the recording file for new PCM data (16-bit 8kHz mono WAV)
 * 3. Resamples 8kHz -> 16kHz and feeds chunks to VoiceAgentPipeline (STT -> Agent -> TTS)
 * 4. Resamples 16kHz -> 8kHz and plays TTS audio back via FreeSWITCH ESL (uuid_broadcast)
 *
 * @author buvidk
 * @since 2026-04-19
 */
@Component
public class CallMediaBridge implements MediaBridgeControl {

    private static final Logger log = LoggerFactory.getLogger(CallMediaBridge.class);

    private static final String BRIDGE_DIR = "/tmp/ai_bridge";
    private static final int CHUNK_READ_INTERVAL_MS = 2000;  // Read audio every 200ms
    private static final int MIN_CHUNK_SIZE = 320;           // 20ms of 8kHz 16-bit mono = 320 bytes

    private final FreeSwitchClient freeSwitchClient;
    private final VoiceAgentPipeline pipeline;
    private final ObjectMapper objectMapper;

    // Active call bridges: uuid -> BridgeSession
    private final ConcurrentMap<String, BridgeSession> activeBridges = new ConcurrentHashMap<>();

    // Event listeners: listenerId -> CallEventListener
    private final ConcurrentMap<String, CallEventListener> eventListeners = new ConcurrentHashMap<>();

    // Scheduled executor for audio reading tasks
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4,
        r -> {
            Thread t = new Thread(r, "audio-bridge-reader");
            t.setDaemon(true);
            return t;
        });

    public CallMediaBridge(FreeSwitchClient freeSwitchClient, VoiceAgentPipeline pipeline, ObjectMapper objectMapper) {
        this.freeSwitchClient = freeSwitchClient;
        this.pipeline = pipeline;
        this.objectMapper = objectMapper;
        log.info("CallMediaBridge initialized");

        // Create bridge directory
        try {
            Files.createDirectories(Paths.get(BRIDGE_DIR));
        } catch (IOException e) {
            log.error("Failed to create bridge directory: {}", BRIDGE_DIR, e);
        }
    }

    /**
     * Start media bridge for a call.
     * Should be called when CHANNEL_ANSWER event is received.
     */
    @Override
    public void startBridge(String uuid, String caller, String callee) {
        if (activeBridges.containsKey(uuid)) {
            log.warn("Bridge already active for call {}", uuid);
            return;
        }

        log.info("🎙️ Starting media bridge for call {} ({} -> {})", uuid, caller, callee);

        try {
            BridgeSession session = new BridgeSession(uuid, caller, callee);

            // Step 1: Start recording via FreeSWITCH ESL
            startRecording(uuid);

            // Step 2: Start monitoring recorded audio and feeding to AI pipeline
            startAudioMonitoring(session);

            // Step 3: Start the AI pipeline (STT -> Agent -> TTS -> playback)
            startAIPipeline(session);

            activeBridges.put(uuid, session);
            log.info("✅ Media bridge started for call {}", uuid);

        } catch (Exception e) {
            log.error("❌ Failed to start media bridge for call {}", uuid, e);
        }
    }

    /**
     * Stop media bridge for a call.
     * Should be called when CHANNEL_HANGUP event is received.
     */
    @Override
    public void stopBridge(String uuid) {
        BridgeSession session = activeBridges.remove(uuid);
        if (session == null) {
            log.warn("No active bridge for call {}", uuid);
            return;
        }

        log.info("🔇 Stopping media bridge for call {}", uuid);
        session.stop();

        try {
            // Stop recording
            freeSwitchClient.sendApiCommand("uuid_record",
                String.format("%s stop %s/%s.wav", uuid, BRIDGE_DIR, uuid));

            // Cleanup temp files
            Path bridgePath = Paths.get(BRIDGE_DIR, uuid);
            if (Files.exists(bridgePath)) {
                Files.walk(bridgePath)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
            }
        } catch (Exception e) {
            log.error("Error during bridge cleanup for call {}", uuid, e);
        }
    }

    /**
     * Start recording call audio via FreeSWITCH uuid_record.
     * Records as WAV (PCM 16-bit 8kHz mono) for AI processing.
     * Must set channel variable record_sample_rate=8000 before recording,
     * otherwise FreeSWITCH records at the channel's native rate (e.g., 48000Hz stereo).
     */
    private void startRecording(String uuid) {
        String recordFile = String.format("%s/%s.wav", BRIDGE_DIR, uuid);

        // Set channel variable to force 8kHz mono recording
        String setResult = freeSwitchClient.sendApiCommand("uuid_setvar",
            String.format("%s record_sample_rate 8000", uuid));
        log.info("Set record_sample_rate=8000 for call {}: {}", uuid, setResult.trim());

        // Record in write direction (captures what the user says), will use 8kHz mono PCM
        String result = freeSwitchClient.sendApiCommand("uuid_record",
            String.format("%s start %s", uuid, recordFile));

        log.info("Recording started for call {}: {} (8kHz mono via channel var)", uuid, result.trim());
    }

    /**
     * Start monitoring the recording file and feed audio chunks to the AI pipeline.
     */
    private void startAudioMonitoring(BridgeSession session) {
        String uuid = session.uuid;
        String recordFile = String.format("%s/%s.wav", BRIDGE_DIR, uuid);

        // Wait a moment for recording to start, then poll periodically
        ScheduledFuture<?> monitorTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                Path wavPath = Paths.get(recordFile);
                if (!Files.exists(wavPath)) {
                    return;
                }

                long fileSize = Files.size(wavPath);
                if (fileSize <= session.lastReadPosition) {
                    return;
                }

                // Read new audio data (skip WAV header on first read)
                long newPosition = fileSize;
                int bytesToRead = (int) (newPosition - session.lastReadPosition);
                long readStart = session.lastReadPosition;

                // Adjust for WAV header on first read
                if (readStart == 0) {
                    // Find the "data" chunk start position dynamically
                    long dataOffset = findDataChunkOffset(wavPath);
                    if (dataOffset < 0) {
                        log.debug("WAV data chunk not found yet for call {}, file size: {}", uuid, fileSize);
                        session.lastReadPosition = newPosition;
                        return;
                    }
                    session.dataChunkOffset = dataOffset;
                    readStart = dataOffset;
                    if (bytesToRead <= dataOffset) {
                        session.lastReadPosition = newPosition;
                        return;
                    }
                    bytesToRead = (int) (newPosition - readStart);

                    // Parse WAV header to detect actual format
                    int[] wavInfo = parseWavHeader(wavPath);
                    if (wavInfo != null) {
                        session.wavChannels = wavInfo[0];
                        session.wavSampleRate = wavInfo[1];
                        log.info("WAV format for call {}: channels={}, sampleRate={}, bitsPerSample={}",
                            uuid, wavInfo[0], wavInfo[1], wavInfo[2]);
                    }
                    log.info("Found WAV data chunk at offset {} for call {}", dataOffset, uuid);
                }

                if (bytesToRead < MIN_CHUNK_SIZE) {
                    return; // Not enough data yet
                }

                try (RandomAccessFile raf = new RandomAccessFile(wavPath.toFile(), "r")) {
                    long fileLength = raf.length();

                    if (readStart >= fileLength) {
                        log.warn("Read position {} exceeds file length {} for call {}", readStart, fileLength, uuid);
                        return;
                    }

                    // Seek to read position
                    raf.seek(readStart);
                    int actualBytesToRead = (int) Math.min(bytesToRead, fileLength - readStart);
                    byte[] rawData = new byte[actualBytesToRead];
                    int bytesRead = raf.read(rawData);

                    if (bytesRead <= 0) {
                        log.warn("No data read from audio file for call {} at position {}", uuid, readStart);
                        return;
                    }

                    if (bytesRead < actualBytesToRead) {
                        rawData = Arrays.copyOf(rawData, bytesRead);
                    }

                    log.debug("Audio read for call {}: position={}, size={}", uuid, readStart, rawData.length);

                    // Step 1: If stereo, convert to mono (take left channel only)
                    byte[] monoData;
                    if (session.wavChannels == 2) {
                        monoData = stereoToMono(rawData);
                    } else {
                        monoData = rawData;
                    }

                    // Step 2: Resample from 8kHz to 16kHz for STT
                    byte[] audioData = resample8kTo16k(monoData);

                    // Emit audio data to the pipeline
                    Sinks.EmitResult emitResult = session.audioSink.tryEmitNext(ByteBuffer.wrap(audioData));
                    if (emitResult.isFailure()) {
                        log.debug("Failed to emit audio for call {}: {}", uuid, emitResult);
                    }
                }

                session.lastReadPosition = newPosition;

            } catch (Exception e) {
                log.error("Error monitoring audio for call {}", uuid, e);
            }
        }, 100, CHUNK_READ_INTERVAL_MS, TimeUnit.MILLISECONDS);

        session.setMonitorTask(monitorTask);
    }

    /**
     * Find the offset of the "data" chunk in a WAV file.
     * Scans through RIFF chunks to locate "data" marker.
     * @return byte offset of audio data start, or -1 if not found
     */
    private static long findDataChunkOffset(Path wavPath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(wavPath.toFile(), "r")) {
            if (raf.length() < 12) return -1;

            // Check RIFF header
            byte[] riffHeader = new byte[12];
            raf.readFully(riffHeader);
            if (!new String(riffHeader, 0, 4).equals("RIFF") ||
                !new String(riffHeader, 8, 4).equals("WAVE")) {
                return -1;
            }

            // Scan through sub-chunks
            long pos = 12;
            while (pos < raf.length() - 8) {
                raf.seek(pos);
                byte[] chunkHeader = new byte[8];
                if (raf.read(chunkHeader) != 8) break;

                String chunkId = new String(chunkHeader, 0, 4);
                int chunkSize = ((chunkHeader[7] & 0xFF) << 24) | ((chunkHeader[6] & 0xFF) << 16) |
                                ((chunkHeader[5] & 0xFF) << 8) | (chunkHeader[4] & 0xFF);

                if ("data".equals(chunkId)) {
                    return pos + 8; // data starts right after chunk header
                }

                // Move to next chunk (chunk data + padding for odd sizes)
                pos += 8 + chunkSize;
                if (chunkSize % 2 != 0) pos++; // RIFF chunks are word-aligned
            }
        }
        return -1;
    }

    /**
     * Parse WAV header to extract channels, sample rate, and bits per sample.
     * @return array of [channels, sampleRate, bitsPerSample], or null if parsing fails
     */
    private static int[] parseWavHeader(Path wavPath) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(wavPath.toFile(), "r")) {
            if (raf.length() < 44) return null;

            byte[] header = new byte[44];
            raf.readFully(header);

            // Verify RIFF/WAVE
            if (!new String(header, 0, 4).equals("RIFF") ||
                !new String(header, 8, 4).equals("WAVE") ||
                !new String(header, 12, 4).equals("fmt ")) {
                return null;
            }

            int channels = header[22] & 0xFF | (header[23] & 0xFF) << 8;
            int sampleRate = (header[24] & 0xFF) | (header[25] & 0xFF) << 8 |
                            (header[26] & 0xFF) << 16 | (header[27] & 0xFF) << 24;
            int bitsPerSample = header[34] & 0xFF | (header[35] & 0xFF) << 8;

            return new int[]{channels, sampleRate, bitsPerSample};
        }
    }

    /**
     * Start the AI pipeline for a call session.
     */
    private void startAIPipeline(BridgeSession session) {
        String uuid = session.uuid;

        pipeline.processStream(session.audioSink.asFlux(), uuid)
            .subscribe(
                event -> handlePipelineEvent(session, event),
                error -> log.error("Pipeline error for call {}", uuid, error),
                () -> log.info("Pipeline completed for call {}", uuid)
            );
    }

    /**
     * Handle events from the AI pipeline.
     * Collects TTS chunks into a buffer and plays them as a complete WAV file
     * after the TTS stream finishes (indicated by AgentEndEvent).
     * Also pushes all events to WebSocket clients monitoring this call.
     */
    private void handlePipelineEvent(BridgeSession session, VoiceAgentEvent event) {

        log.info("handlePipelineEvent event info:{}", event);
        String uuid = session.uuid;

        // Push all events to registered listeners
        broadcastToEventListeners(uuid, event);

        if (event instanceof TTSChunkEvent ttsEvent) {
            // Accumulate TTS audio chunks
            byte[] chunk = ttsEvent.audio();
            log.info("TTS chunk received for call {}: {} bytes (total so far: {})",
                uuid, chunk.length, session.ttsBuffer.addAndGet(chunk.length));
            session.ttsChunks.add(chunk);
        } else if (event instanceof AgentEndEvent) {
            // Agent finished responding, now play the accumulated TTS audio
            new Thread(() -> {
                playCollectedTTSAudio(uuid, session);
            }, "tts-play-" + uuid).start();
        }
    }

    /**
     * Register a call event listener to receive pipeline events.
     * @param listenerId unique listener identifier (e.g., WebSocket session ID)
     * @param filterUuid call UUID to filter by (null = receive events from all calls)
     * @param callback receives serialized JSON event strings
     */
    public void addEventListener(String listenerId, String filterUuid, java.util.function.Consumer<String> callback) {
        eventListeners.put(listenerId, new CallEventListener(filterUuid, callback));
        log.info("Event listener {} registered for call {}", listenerId, filterUuid != null ? filterUuid : "all");
    }

    /**
     * Remove a call event listener.
     */
    public void removeEventListener(String listenerId) {
        eventListeners.remove(listenerId);
        log.info("Event listener {} unregistered", listenerId);
    }

    /**
     * Broadcast a pipeline event to all registered event listeners.
     */
    private void broadcastToEventListeners(String uuid, VoiceAgentEvent event) {
        if (eventListeners.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(event);
            eventListeners.forEach((id, listener) -> {
                if (listener.filterUuid != null && !listener.filterUuid.equals(uuid)) return;
                try {
                    listener.callback.accept(json);
                } catch (Exception e) {
                    log.debug("Failed to notify event listener {}: {}", id, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize event for broadcast", e);
        }
    }

    /**
     * Collect all TTS audio chunks, downsample, wrap as WAV, and play via uuid_broadcast.
     */
    private void playCollectedTTSAudio(String uuid, BridgeSession session) {
        if (session.ttsChunks.isEmpty()) {
            log.debug("No TTS audio to play for call {}", uuid);
            return;
        }

        try {
            // Merge all chunks
            int totalSize = session.ttsBuffer.get();
            byte[] pcm16k = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : session.ttsChunks) {
                System.arraycopy(chunk, 0, pcm16k, offset, chunk.length);
                offset += chunk.length;
            }

            // Downsample from 16kHz to 8kHz
            byte[] pcm8k = resample16kTo8k(pcm16k);
            log.info("Playing TTS audio for call {}: {} bytes (8kHz)", uuid, pcm8k.length);

            // Write as WAV file (FreeSWITCH requires WAV format for uuid_broadcast)
            Path wavFile = Paths.get(BRIDGE_DIR, uuid, "tts_output.wav");
            Files.createDirectories(wavFile.getParent());
            writeWavFile(wavFile, pcm8k, 8000, 16, 1);

            // Play via uuid_broadcast
            String result = freeSwitchClient.sendApiCommand("uuid_broadcast",
                String.format("%s %s both", uuid, wavFile.toString()));
            log.info("uuid_broadcast result for call {}: {}", uuid, result.trim());

            // Clear buffer for next TTS cycle
            session.ttsChunks.clear();
            session.ttsBuffer.set(0);

        } catch (Exception e) {
            log.error("Failed to play TTS audio for call {}", uuid, e);
            session.ttsChunks.clear();
            session.ttsBuffer.set(0);
        }
    }

    /**
     * Write PCM data as a WAV file.
     */
    private static void writeWavFile(Path filePath, byte[] pcmData, int sampleRate, int bitsPerSample, int channels) throws IOException {
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;

        try (FileOutputStream fos = new FileOutputStream(filePath.toFile());
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

            // RIFF header
            bb.put("RIFF".getBytes());
            bb.putInt(fileSize);
            bb.put("WAVE".getBytes());

            // fmt sub-chunk
            bb.put("fmt ".getBytes());
            bb.putInt(16);            // Sub-chunk size
            bb.putShort((short) 1);   // Audio format (PCM)
            bb.putShort((short) channels);
            bb.putInt(sampleRate);
            bb.putInt(sampleRate * channels * bitsPerSample / 8);  // Byte rate
            bb.putShort((short) (channels * bitsPerSample / 8));   // Block align
            bb.putShort((short) bitsPerSample);

            // data sub-chunk
            bb.put("data".getBytes());
            bb.putInt(dataSize);

            bos.write(bb.array());
            bos.write(pcmData);
        }
    }

    /**
     * Convert stereo 16-bit PCM to mono by averaging left and right channels.
     * Input: 16-bit signed PCM, stereo (L R L R L R ...)
     * Output: 16-bit signed PCM, mono (L L L ...)
     */
    private static byte[] stereoToMono(byte[] stereoData) {
        int numFrames = stereoData.length / 4; // 2 channels × 2 bytes each
        byte[] monoData = new byte[numFrames * 2];

        for (int i = 0; i < numFrames; i++) {
            short left  = (short) ((stereoData[i * 4]     & 0xFF) | (stereoData[i * 4 + 1] << 8));
            short right = (short) ((stereoData[i * 4 + 2] & 0xFF) | (stereoData[i * 4 + 3] << 8));
            short mono = (short) ((left + right) / 2);
            monoData[i * 2]     = (byte) (mono & 0xFF);
            monoData[i * 2 + 1] = (byte) ((mono >> 8) & 0xFF);
        }

        return monoData;
    }

    /**
     * Resample PCM audio from 8kHz to 16kHz using linear interpolation.
     * Input: 16-bit signed PCM, mono, 8kHz
     * Output: 16-bit signed PCM, mono, 16kHz (2x size)
     */
    private static byte[] resample8kTo16k(byte[] input8k) {
        int numSamples = input8k.length / 2;
        byte[] output16k = new byte[numSamples * 4]; // 2x samples, 2 bytes each

        for (int i = 0; i < numSamples; i++) {
            short current = (short) ((input8k[i * 2] & 0xFF) | (input8k[i * 2 + 1] << 8));

            short interpolated;
            if (i < numSamples - 1) {
                short next = (short) ((input8k[(i + 1) * 2] & 0xFF) | (input8k[(i + 1) * 2 + 1] << 8));
                interpolated = (short) ((current + next) / 2);
            } else {
                interpolated = current;
            }

            output16k[i * 4] = (byte) (current & 0xFF);
            output16k[i * 4 + 1] = (byte) ((current >> 8) & 0xFF);
            output16k[i * 4 + 2] = (byte) (interpolated & 0xFF);
            output16k[i * 4 + 3] = (byte) ((interpolated >> 8) & 0xFF);
        }

        return output16k;
    }

    /**
     * Downsample PCM audio from 16kHz to 8kHz (drop every other sample).
     * Input: 16-bit signed PCM, mono, 16kHz
     * Output: 16-bit signed PCM, mono, 8kHz (half size)
     */
    private static byte[] resample16kTo8k(byte[] input16k) {
        int numSamples = input16k.length / 2;
        byte[] output8k = new byte[numSamples]; // half samples, 2 bytes each

        for (int i = 0; i < numSamples / 2; i++) {
            output8k[i * 2] = input16k[i * 4];
            output8k[i * 2 + 1] = input16k[i * 4 + 1];
        }

        return output8k;
    }

    public int getActiveBridgeCount() {
        return activeBridges.size();
    }

    public boolean isBridgeActive(String uuid) {
        return activeBridges.containsKey(uuid);
    }

    /**
     * Bridge session - tracks state for a single call's media bridge.
     */
    private static class BridgeSession {
        final String uuid;
        final String caller;
        final String callee;
        final Sinks.Many<ByteBuffer> audioSink;
        volatile long lastReadPosition = 0;
        volatile long dataChunkOffset = 44;  // default, will be updated dynamically
        volatile int wavChannels = -1;        // detected from WAV header
        volatile int wavSampleRate = -1;      // detected from WAV header
        volatile ScheduledFuture<?> monitorTask;

        // TTS audio accumulation: collect chunks until AgentEndEvent, then play as WAV
        final ConcurrentLinkedQueue<byte[]> ttsChunks = new ConcurrentLinkedQueue<>();
        final AtomicInteger ttsBuffer = new AtomicInteger(0);

        BridgeSession(String uuid, String caller, String callee) {
            this.uuid = uuid;
            this.caller = caller;
            this.callee = callee;
            this.audioSink = Sinks.many().unicast().onBackpressureBuffer();
        }

        void setMonitorTask(ScheduledFuture<?> task) {
            this.monitorTask = task;
        }

        void stop() {
            if (monitorTask != null) {
                monitorTask.cancel(false);
            }
            audioSink.tryEmitComplete();
        }
    }

    /**
     * Event listener for call monitoring.
     */
    private static class CallEventListener {
        final String filterUuid;  // null = listen to all calls
        final java.util.function.Consumer<String> callback;

        CallEventListener(String filterUuid, java.util.function.Consumer<String> callback) {
            this.filterUuid = filterUuid;
            this.callback = callback;
        }
    }
}
