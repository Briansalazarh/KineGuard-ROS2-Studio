package com.vanguard.controller;

import com.vanguard.domain.dto.ChaosInjectionRequest;
import com.vanguard.domain.dto.MetaApiDtos.AnalyzeSceneRequest;
import com.vanguard.domain.dto.MetaApiDtos.ClosedLoopExecutionResponse;
import com.vanguard.domain.dto.MetaApiDtos.MuseVoiceTranscribeResponse;
import com.vanguard.domain.dto.MetaApiDtos.Ros2ExportBundle;
import com.vanguard.domain.dto.MetaApiDtos.VerifiableSceneResponse;
import com.vanguard.domain.dto.VoiceCommandRequest;
import com.vanguard.meta.MetaServiceOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * KineGuard ROS2 Studio — Verifiable Physical AI & Metric Navigation REST Controller.
 * Exposes:
 *   - POST /api/v1/scene/analyze-and-verify (Homography + SAM 3.1 + Muse Spark + Multi-Turn Tool Verification + ROS 2 Export)
 *   - POST /api/v1/scene/red-team (Muse Image 1.0 Adversarial Obstacle Synthesis + Re-Verification)
 *   - POST /api/v1/voice/transcribe (Muse Voice Transcribe 24kHz WAV ASR)
 *   - GET  /api/v1/ros2/export (Downloadable ROS 2 Nav2 map.pgm, map.yaml, nav2_verified_waypoints.json)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class VoiceChaosController {

    private final MetaServiceOrchestrator orchestrator;

    /**
     * KineGuard ROS2 Studio primary pipeline:
     * Calibrates 4-point floor homography, segments obstacles with SAM 3.1 + Muse Spark 1.3,
     * executes the multi-turn verify_kinematic_safety Tool-Calling loop (Turn 1 ❌ -> Turn 2 ✅),
     * and compiles the ROS 2 Nav2 occupancy grid and verified metric waypoints.
     */
    @PostMapping("/scene/analyze-and-verify")
    public ResponseEntity<VerifiableSceneResponse> analyzeAndVerifyScene(
            @RequestBody(required = false) AnalyzeSceneRequest request) {
        log.info("Analyzing and verifying scene: sceneId={}, concept={}",
                request != null ? request.getSceneId() : "DEFAULT",
                request != null ? request.getConceptPrompt() : "box");
        VerifiableSceneResponse response = orchestrator.analyzeAndVerifyScene(request);
        return ResponseEntity.ok(response);
    }

    /**
     * KineGuard ROS2 Studio Adversarial Red-Team Generator:
     * Synthesizes an unexpected floor hazard using Muse Image 1.0 (/v1/images/edits),
     * segments it with SAM 3.1, and runs the multi-turn verify_kinematic_safety replanner.
     */
    @PostMapping("/scene/red-team")
    public ResponseEntity<VerifiableSceneResponse> executeRedTeamScenario(
            @RequestBody(required = false) AnalyzeSceneRequest request) {
        log.info("Executing Adversarial Red-Team stress test: hazardPrompt={}",
                request != null ? request.getRedTeamPrompt() : "DEFAULT");
        VerifiableSceneResponse response = orchestrator.executeRedTeamAdversarialTest(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Transcribes 24kHz 16-bit mono PCM WAV audio using Muse Voice Transcribe (/v1/asr/transcribe) via JSON payload.
     */
    @PostMapping(value = "/voice/transcribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MuseVoiceTranscribeResponse> transcribeVoiceJson(
            @RequestBody(required = false) VoiceCommandRequest request) {
        byte[] rawAudio = decodeBase64Audio(request != null ? request.getAudioBase64() : null);
        String hint = request != null ? request.getTranscriptHint() : null;
        MuseVoiceTranscribeResponse response = orchestrator.transcribeOperatorAudio(rawAudio, hint);
        return ResponseEntity.ok(response);
    }

    /**
     * Transcribes 24kHz 16-bit mono PCM WAV audio using Muse Voice Transcribe (/v1/asr/transcribe) via Multipart upload.
     */
    @PostMapping(value = "/voice/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MuseVoiceTranscribeResponse> transcribeVoiceMultipart(
            @RequestParam(value = "audio", required = false) MultipartFile audioFile,
            @RequestParam(value = "transcriptHint", required = false) String transcriptHint) throws IOException {
        byte[] rawAudio = (audioFile != null && !audioFile.isEmpty()) ? audioFile.getBytes() : new byte[0];
        MuseVoiceTranscribeResponse response = orchestrator.transcribeOperatorAudio(rawAudio, transcriptHint);
        return ResponseEntity.ok(response);
    }

    /**
     * Exports the ROS 2 Nav2 bundle (map.pgm, map.yaml, nav2_verified_waypoints.json).
     * With an executionId, returns exactly that execution's bundle (404 when unknown —
     * never another execution's package). Without one, returns the latest (compatibility).
     */
    @GetMapping("/ros2/export")
    public ResponseEntity<Ros2ExportBundle> exportRos2Bundle(
            @RequestParam(value = "executionId", required = false) String executionId) {
        if (executionId != null && !executionId.isBlank()) {
            Ros2ExportBundle bundle = orchestrator.getRos2BundleForExecution(executionId);
            return bundle != null
                    ? ResponseEntity.ok(bundle)
                    : ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(orchestrator.getLatestRos2Bundle());
    }

    /**
     * Legacy chaos injection endpoint preserved for backwards compatibility.
     */
    @PostMapping("/chaos/inject")
    public ResponseEntity<ClosedLoopExecutionResponse> injectChaos(
            @RequestBody(required = false) ChaosInjectionRequest request) {
        ChaosInjectionRequest effectiveRequest = request != null
                ? request
                : ChaosInjectionRequest.builder().presetId("THERMAL_SECTOR_B").build();

        ClosedLoopExecutionResponse response = orchestrator.executeChaosInjection(effectiveRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Legacy JSON voice command endpoint preserved for backwards compatibility.
     */
    @PostMapping(value = "/voice/command", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ClosedLoopExecutionResponse> handleVoiceCommandJson(
            @RequestBody(required = false) VoiceCommandRequest request) {
        VoiceCommandRequest effectiveRequest = request != null
                ? request
                : VoiceCommandRequest.builder()
                .transcriptHint("Navigate AMR-01 around central corridor pallet")
                .build();

        ClosedLoopExecutionResponse response = orchestrator.executeVoiceClosedLoop(effectiveRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * Legacy multipart voice command endpoint preserved for backwards compatibility.
     */
    @PostMapping(value = "/voice/command", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClosedLoopExecutionResponse> handleVoiceCommandMultipart(
            @RequestParam(value = "audio", required = false) MultipartFile audioFile,
            @RequestParam(value = "transcriptHint", required = false) String transcriptHint,
            @RequestParam(value = "targetX", required = false) Double targetX,
            @RequestParam(value = "targetY", required = false) Double targetY) throws IOException {

        String base64Audio = "";
        if (audioFile != null && !audioFile.isEmpty()) {
            base64Audio = Base64.getEncoder().encodeToString(audioFile.getBytes());
        }

        VoiceCommandRequest request = VoiceCommandRequest.builder()
                .audioBase64(base64Audio)
                .transcriptHint(transcriptHint)
                .targetX(targetX)
                .targetY(targetY)
                .build();

        ClosedLoopExecutionResponse response = orchestrator.executeVoiceClosedLoop(request);
        return ResponseEntity.ok(response);
    }

    private byte[] decodeBase64Audio(String base64) {
        if (base64 == null || base64.isBlank()) {
            return new byte[0];
        }
        try {
            String cleaned = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
            return Base64.getDecoder().decode(cleaned);
        } catch (Exception e) {
            return base64.getBytes(StandardCharsets.UTF_8);
        }
    }
}
