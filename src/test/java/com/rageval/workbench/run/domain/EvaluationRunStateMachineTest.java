package com.rageval.workbench.run.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rageval.workbench.common.error.ConflictException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvaluationRunStateMachineTest {

    private EvaluationRun newRun() {
        return new EvaluationRun(
                UUID.randomUUID(), UUID.randomUUID(), "run", RunMode.LIVE, 2, List.of(), Map.of());
    }

    @Test
    void happyPathCreatedQueuedRunningCompleted() {
        EvaluationRun run = newRun();
        assertThat(run.getStatus()).isEqualTo(RunStatus.CREATED);
        run.markQueued();
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        run.markStarted();
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.getStartedAt()).isNotNull();
        run.markCompleted(RunStatus.COMPLETED);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getCompletedAt()).isNotNull();
    }

    @Test
    void createdCannotJumpStraightToRunning() {
        assertThatThrownBy(() -> newRun().markStarted()).isInstanceOf(ConflictException.class);
    }

    @Test
    void completedIsTerminal() {
        EvaluationRun run = newRun();
        run.markQueued();
        run.markStarted();
        run.markCompleted(RunStatus.COMPLETED);
        assertThatThrownBy(run::markQueued).isInstanceOf(ConflictException.class);
        assertThatThrownBy(run::markCancelled).isInstanceOf(ConflictException.class);
    }

    @Test
    void markCompletedRejectsNonTerminalArgument() {
        EvaluationRun run = newRun();
        run.markQueued();
        run.markStarted();
        assertThatThrownBy(() -> run.markCompleted(RunStatus.RUNNING))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationAllowedFromCreatedQueuedAndRunning() {
        EvaluationRun fromCreated = newRun();
        fromCreated.markCancelled();
        assertThat(fromCreated.getStatus()).isEqualTo(RunStatus.CANCELLED);

        EvaluationRun fromQueued = newRun();
        fromQueued.markQueued();
        fromQueued.markCancelled();
        assertThat(fromQueued.getStatus()).isEqualTo(RunStatus.CANCELLED);

        EvaluationRun fromRunning = newRun();
        fromRunning.markQueued();
        fromRunning.markStarted();
        fromRunning.markCancelled();
        assertThat(fromRunning.getStatus()).isEqualTo(RunStatus.CANCELLED);
        assertThat(fromRunning.getCompletedAt()).isNotNull();
    }

    @Test
    void resumableStatesCanBeRequeuedAndOutcomeIsCleared() {
        EvaluationRun run = newRun();
        run.markQueued();
        run.markStarted();
        run.markFailed("boom");
        assertThat(run.isResumable()).isTrue();
        run.markQueued();
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.getFailureReason()).isNull();
        assertThat(run.getCompletedAt()).isNull();
    }

    @Test
    void interruptedFromQueuedAndRunningOnly() {
        EvaluationRun queued = newRun();
        queued.markQueued();
        queued.markInterrupted();
        assertThat(queued.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(queued.isResumable()).isTrue();

        assertThatThrownBy(() -> newRun().markInterrupted()).isInstanceOf(ConflictException.class);
    }

    @Test
    void startedAtIsPreservedAcrossResume() {
        EvaluationRun run = newRun();
        run.markQueued();
        run.markStarted();
        var firstStart = run.getStartedAt();
        run.markFailed("boom");
        run.markQueued();
        run.markStarted();
        assertThat(run.getStartedAt()).isEqualTo(firstStart);
    }
}
