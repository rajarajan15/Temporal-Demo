package com.occlet.issuance.engine;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import java.time.Duration;

/** Named retry profiles, so tuning lives in one place. */
public enum RetryProfile {
    FAST_READ(Duration.ofSeconds(5), null, Duration.ofMillis(500), Duration.ofSeconds(5), 5),
    WRITE_IDEMPOTENT(Duration.ofSeconds(5), null, Duration.ofSeconds(1), Duration.ofSeconds(60), 6),
    COMPENSATION(Duration.ofSeconds(5), null, Duration.ofSeconds(1), Duration.ofSeconds(30), 10),
    BEST_EFFORT(Duration.ofSeconds(5), null, Duration.ofSeconds(1), Duration.ofSeconds(5), 3),
    /**
     * Long activities that record progress. If heartbeats stop for 5 seconds, Temporal assumes the
     * worker died and retries, and the next attempt resumes from the last recorded progress.
     */
    LONG_RUNNING_HEARTBEAT(Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(10), 5);

    private final Duration startToClose;
    private final Duration heartbeat;
    private final Duration initialInterval;
    private final Duration maximumInterval;
    private final int maximumAttempts;

    RetryProfile(Duration startToClose, Duration heartbeat, Duration initialInterval, Duration maximumInterval, int maximumAttempts) {
        this.startToClose = startToClose;
        this.heartbeat = heartbeat;
        this.initialInterval = initialInterval;
        this.maximumInterval = maximumInterval;
        this.maximumAttempts = maximumAttempts;
    }

    public int maximumAttempts() {
        return maximumAttempts;
    }

    public ActivityOptions options() {
        ActivityOptions.Builder builder = ActivityOptions.newBuilder()
                .setStartToCloseTimeout(startToClose)
                .setRetryOptions(RetryOptions.newBuilder()
                        .setInitialInterval(initialInterval)
                        .setBackoffCoefficient(2.0)
                        .setMaximumInterval(maximumInterval)
                        .setMaximumAttempts(maximumAttempts)
                        .build());
        if (heartbeat != null) {
            builder.setHeartbeatTimeout(heartbeat);
        }
        return builder.build();
    }
}
