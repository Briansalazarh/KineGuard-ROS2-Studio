package com.vanguard.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceCommandRequest {

    private String audioBase64;
    private String transcriptHint;
    private String sectorHint;
    private Double targetX;
    private Double targetY;
}
