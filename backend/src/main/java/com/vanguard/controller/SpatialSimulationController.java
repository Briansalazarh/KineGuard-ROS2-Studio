package com.vanguard.controller;

import com.vanguard.domain.model.TelemetryFrame;
import com.vanguard.engine.ChaosSimulationEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

/**
 * High-Frequency Spatial Telemetry Controller.
 * Streams 25Hz (40ms) real-time TelemetryFrames over Server-Sent Events (SSE)
 * and provides instant state snapshots and arena reset controls.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/spatial")
@RequiredArgsConstructor
public class SpatialSimulationController {

    private final ChaosSimulationEngine simulationEngine;

    /**
     * Streams real-time telemetry frames at 25Hz (within 20-30Hz specification).
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTelemetry() {
        SseEmitter emitter = new SseEmitter(0L);

        Consumer<TelemetryFrame> subscriber = new Consumer<>() {
            @Override
            public void accept(TelemetryFrame frame) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("telemetry")
                            .id(String.valueOf(frame.getFrameId()))
                            .data(frame, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException ex) {
                    simulationEngine.unregisterFrameSubscriber(this);
                    try {
                        emitter.complete();
                    } catch (Exception ignored) {
                    }
                }
            }
        };

        simulationEngine.registerFrameSubscriber(subscriber);

        emitter.onCompletion(() -> simulationEngine.unregisterFrameSubscriber(subscriber));
        emitter.onTimeout(() -> {
            simulationEngine.unregisterFrameSubscriber(subscriber);
            emitter.complete();
        });
        emitter.onError(err -> simulationEngine.unregisterFrameSubscriber(subscriber));

        // Send initial snapshot immediately upon subscription
        TelemetryFrame initialFrame = simulationEngine.getLatestFrame();
        if (initialFrame != null) {
            subscriber.accept(initialFrame);
        }

        return emitter;
    }

    @GetMapping("/snapshot")
    public ResponseEntity<TelemetryFrame> getSnapshot() {
        return ResponseEntity.ok(simulationEngine.getLatestFrame());
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> resetArena() {
        simulationEngine.resetArena();
        return ResponseEntity.ok(Map.of(
                "status", "ARENA_RESET_COMPLETE",
                "activeHazards", 0,
                "timestamp", System.currentTimeMillis()
        ));
    }
}
