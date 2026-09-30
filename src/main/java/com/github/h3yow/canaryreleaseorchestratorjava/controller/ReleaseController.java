package com.github.h3yow.canaryreleaseorchestratorjava.controller;

import com.github.h3yow.canaryreleaseorchestratorjava.domain.AdvanceOutcome;
import com.github.h3yow.canaryreleaseorchestratorjava.dto.AdvanceRequest;
import com.github.h3yow.canaryreleaseorchestratorjava.dto.CreatePlanRequest;
import com.github.h3yow.canaryreleaseorchestratorjava.service.ReleaseOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseOrchestrator orchestrator;

    public ReleaseController(ReleaseOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping
    public ResponseEntity<AdvanceOutcome> createPlan(@RequestBody CreatePlanRequest request) {
        AdvanceOutcome outcome = orchestrator.createPlan(
                request.planId(), request.targetVersion(), request.stableVersion(),
                request.batchTrafficPercents());
        return ResponseEntity.ok(outcome);
    }

    @PostMapping("/{planId}/advance")
    public ResponseEntity<AdvanceOutcome> advance(@PathVariable String planId,
                                                  @RequestBody AdvanceRequest request) {
        return ResponseEntity.ok(orchestrator.advance(planId, request.requestId()));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<?> getPlan(@PathVariable String planId) {
        return orchestrator.getPlan(planId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
