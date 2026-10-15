package com.smartchef.api.dto;

import java.time.Instant;
import java.util.List;

public record ScanResultResponse(
        Long scanSessionId,
        List<String> detectedIngredients,
        List<RecipeResponse> recipes,
        Instant createdAt) {
}
