/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.shard;

import org.elasticsearch.index.IndexMode;
import org.elasticsearch.telemetry.TelemetryProvider;
import org.elasticsearch.telemetry.metric.LongCounter;
import org.elasticsearch.telemetry.metric.LongHistogram;
import org.elasticsearch.telemetry.metric.MeterRegistry;
import org.elasticsearch.telemetry.metric.MetricAttributes;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Primary-side indexing instruments: latency per shard bulk request and failures per index operation, both attributed by index mode.
 */
public class IndexingMetrics {

    public static final String INDEXING_DURATION_HISTOGRAM = "es.indexing.shards.duration.histogram";
    public static final String INDEXING_FAILURE_TOTAL = "es.indexing.shards.failure.total";
    public static final IndexingMetrics NOOP = new IndexingMetrics(TelemetryProvider.NOOP.getMeterRegistry());

    private final LongHistogram bulkDurationInMillis;
    private final LongCounter indexFailures;

    public IndexingMetrics(MeterRegistry meterRegistry) {
        bulkDurationInMillis = meterRegistry.registerLongHistogram(
            INDEXING_DURATION_HISTOGRAM,
            "Time to execute a bulk request on a primary shard in milliseconds",
            "ms"
        );
        indexFailures = meterRegistry.registerLongCounter(
            INDEXING_FAILURE_TOTAL,
            "Number of index operations that failed on a primary",
            "unit"
        );
    }

    public void onBulk(IndexMode indexMode, long tookInNanos) {
        bulkDurationInMillis.record(
            TimeUnit.NANOSECONDS.toMillis(tookInNanos),
            Map.of(MetricAttributes.ES_INDEX_MODE, indexMode.getName())
        );
    }

    public void onFailure(IndexMode indexMode, Throwable error, long count) {
        indexFailures.incrementBy(
            count,
            Map.of(MetricAttributes.ES_INDEX_MODE, indexMode.getName(), MetricAttributes.ERROR_TYPE, MetricAttributes.errorType(error))
        );
    }
}
