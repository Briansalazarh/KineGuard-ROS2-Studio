package com.vanguard.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChaosInjectionRequest {

    private String presetId;
    private String commandText;
    private String hazardType;
    private String sector;
    private Double centerX;
    private Double centerY;
    private Double radius;
    private Double severity;
}
