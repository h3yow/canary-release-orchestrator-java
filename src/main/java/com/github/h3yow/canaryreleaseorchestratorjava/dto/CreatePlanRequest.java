package com.github.h3yow.canaryreleaseorchestratorjava.dto;

import java.util.List;

public record CreatePlanRequest(String planId, String targetVersion, String stableVersion,
                                List<Integer> batchTrafficPercents) {
}
