package com.codetrove.common.id;

import java.time.Instant;

public final class SnowflakeIdGenerator {

    private static final long CUSTOM_EPOCH = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
    private static final long MAX_NODE_ID = 1023L;
    private static final long SEQUENCE_MASK = 4095L;
    private static final long NODE_SHIFT = 12L;
    private static final long TIMESTAMP_SHIFT = 22L;

    private final long nodeId;
    private long lastTimestamp = -1L;
    private long sequence;

    public SnowflakeIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("nodeId must be between 0 and " + MAX_NODE_ID);
        }
        this.nodeId = nodeId;
    }

    public synchronized long nextId() {
        long timestamp = currentTimestamp();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("System clock moved backwards");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }
        lastTimestamp = timestamp;
        return ((timestamp - CUSTOM_EPOCH) << TIMESTAMP_SHIFT) | (nodeId << NODE_SHIFT) | sequence;
    }

    private long waitForNextMillis(long previousTimestamp) {
        long timestamp = currentTimestamp();
        while (timestamp <= previousTimestamp) {
            Thread.onSpinWait();
            timestamp = currentTimestamp();
        }
        return timestamp;
    }

    private long currentTimestamp() {
        return System.currentTimeMillis();
    }
}
