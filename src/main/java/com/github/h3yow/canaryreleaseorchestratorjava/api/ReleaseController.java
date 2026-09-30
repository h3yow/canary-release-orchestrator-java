package com.github.h3yow.canaryreleaseorchestratorjava.api;

import com.github.h3yow.canaryreleaseorchestratorjava.domain.ErrorCode;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.PlanStatus;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleaseException;
import com.github.h3yow.canaryreleaseorchestratorjava.domain.ReleasePlan;
import com.github.h3yow.canaryreleaseorchestratorjava.service.ReleaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布编排 REST 接口。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @PostMapping
    public ResponseEntity<PlanView> create(@RequestBody CreatePlanRequest request) {
        ReleasePlan plan = releaseService.createPlan(
                request.targetVersion(), request.stableVersion(), request.batchTrafficPercents());
        return ResponseEntity.status(HttpStatus.CREATED).body(PlanView.of(plan));
    }

    @PostMapping("/{planId}/advance")
    public PlanView advance(@PathVariable String planId, @RequestBody AdvanceRequest request) {
        return PlanView.of(releaseService.advance(planId, request.requestId()));
    }

    @GetMapping("/{planId}")
    public PlanView get(@PathVariable String planId) {
        return PlanView.of(releaseService.getPlan(planId));
    }

    @ExceptionHandler(ReleaseException.class)
    public ResponseEntity<ErrorView> handle(ReleaseException e) {
        HttpStatus status = switch (e.getErrorCode()) {
            case PLAN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PLAN_ALREADY_FINISHED -> HttpStatus.CONFLICT;
            case INVALID_TRAFFIC_RATIO, VERSION_CONFLICT -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(new ErrorView(e.getErrorCode().name(), e.getMessage()));
    }

    public record CreatePlanRequest(String targetVersion, String stableVersion,
                                    List<Integer> batchTrafficPercents) {
    }

    public record AdvanceRequest(String requestId) {
    }

    public record PlanView(String id, String targetVersion, String stableVersion,
                           List<Integer> batchTrafficPercents, PlanStatus status,
                           int currentBatchIndex, int canaryTrafficPercent,
                           int stableTrafficPercent, int rolledBackBatchIndex) {
        static PlanView of(ReleasePlan p) {
            return new PlanView(p.getId(), p.getTargetVersion(), p.getStableVersion(),
                    p.getBatchTrafficPercents(), p.getStatus(), p.getCurrentBatchIndex(),
                    p.getCanaryTrafficPercent(), p.getStableTrafficPercent(),
                    p.getRolledBackBatchIndex());
        }
    }

    public record ErrorView(String errorCode, String message) {
    }
}
