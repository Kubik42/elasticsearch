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
import org.elasticsearch.telemetry.InstrumentType;
import org.elasticsearch.telemetry.Measurement;
import org.elasticsearch.telemetry.RecordingMeterRegistry;
import org.elasticsearch.telemetry.metric.MetricAttributes;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

public class IndexingMetricsTests extends ESTestCase {

    public void testBulkRecordsMillisWithIndexMode() {
        RecordingMeterRegistry registry = new RecordingMeterRegistry();
        IndexingMetrics metrics = new IndexingMetrics(registry);
        IndexMode indexMode = randomFrom(IndexMode.values());
        long tookNanos = randomLongBetween(0, TimeUnit.MINUTES.toNanos(10));

        metrics.onBulk(indexMode, tookNanos);

        Measurement duration = single(registry, InstrumentType.LONG_HISTOGRAM, IndexingMetrics.INDEXING_DURATION_HISTOGRAM);
        assertThat(duration.getLong(), equalTo(TimeUnit.NANOSECONDS.toMillis(tookNanos)));
        assertThat(duration.attributes(), equalTo(Map.of(MetricAttributes.ES_INDEX_MODE, indexMode.getName())));
        assertThat(registry.getRecorder().getMeasurements(InstrumentType.LONG_COUNTER, IndexingMetrics.INDEXING_FAILURE_TOTAL), hasSize(0));
    }

    public void testFailureCarriesIndexModeAndErrorType() {
        RecordingMeterRegistry registry = new RecordingMeterRegistry();
        IndexingMetrics metrics = new IndexingMetrics(registry);
        IndexMode indexMode = randomFrom(IndexMode.values());
        Throwable error = randomFrom(new IOException(randomAlphaOfLength(5)), new IllegalStateException(randomAlphaOfLength(5)));
        long count = randomLongBetween(1, 100);

        metrics.onFailure(indexMode, error, count);

        Measurement failure = single(registry, InstrumentType.LONG_COUNTER, IndexingMetrics.INDEXING_FAILURE_TOTAL);
        assertThat(failure.getLong(), equalTo(count));
        assertThat(
            failure.attributes(),
            equalTo(
                Map.of(MetricAttributes.ES_INDEX_MODE, indexMode.getName(), MetricAttributes.ERROR_TYPE, error.getClass().getSimpleName())
            )
        );
    }

    private static Measurement single(RecordingMeterRegistry registry, InstrumentType type, String name) {
        List<Measurement> measurements = registry.getRecorder().getMeasurements(type, name);
        assertThat(name, measurements, hasSize(1));
        return measurements.get(0);
    }
}
