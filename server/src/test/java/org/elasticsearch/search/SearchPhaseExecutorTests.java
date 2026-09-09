/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.search;

import org.elasticsearch.core.CheckedRunnable;
import org.elasticsearch.index.query.SearchExecutionContext;
import org.elasticsearch.index.shard.SearchOperationListener;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.TestSearchContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.sameInstance;

public class SearchPhaseExecutorTests extends ESTestCase {

    public void testExecutePhaseReportsExactlyOneOutcome() {
        List<String> events = new ArrayList<>();
        List<Exception> failures = new ArrayList<>();
        SearchOperationListener listener = new SearchOperationListener() {
            @Override
            public void onPreDfsPhase(SearchContext searchContext) {
                events.add("pre");
            }

            @Override
            public void onDfsPhase(SearchContext searchContext, long tookInNanos) {
                assertThat(tookInNanos, greaterThanOrEqualTo(0L));
                events.add("success");
            }

            @Override
            public void onFailedDfsPhase(SearchContext searchContext, Exception e) {
                events.add("failure");
                failures.add(e);
            }

            @Override
            public void onPreQueryPhase(SearchContext searchContext) {
                onPreDfsPhase(searchContext);
            }

            @Override
            public void onQueryPhase(SearchContext searchContext, long tookInNanos) {
                onDfsPhase(searchContext, tookInNanos);
            }

            @Override
            public void onFailedQueryPhase(SearchContext searchContext, Exception e) {
                onFailedDfsPhase(searchContext, e);
            }

            @Override
            public void onPreFetchPhase(SearchContext searchContext) {
                onPreDfsPhase(searchContext);
            }

            @Override
            public void onFetchPhase(SearchContext searchContext, long tookInNanos) {
                onDfsPhase(searchContext, tookInNanos);
            }

            @Override
            public void onFailedFetchPhase(SearchContext searchContext, Exception e) {
                onFailedDfsPhase(searchContext, e);
            }
        };
        try (SearchContext ctx = new TestSearchContext((SearchExecutionContext) null)) {
            Exception failure = randomFrom(new IOException("io"), new IllegalStateException("state"), new RuntimeException("runtime"));
            CheckedRunnable<Exception> body = () -> { throw failure; };
            Exception thrown = switch (randomInt(2)) {
                case 0 -> expectThrows(Exception.class, () -> SearchPhaseExecutor.executeDfsPhase(listener, ctx, body));
                case 1 -> expectThrows(Exception.class, () -> SearchPhaseExecutor.executeQueryPhase(listener, ctx, body));
                default -> expectThrows(Exception.class, () -> SearchPhaseExecutor.executeFetchPhase(listener, ctx, body));
            };
            assertThat(thrown, sameInstance(failure));
            assertEquals(List.of("pre", "failure"), events);
            assertEquals(List.of(failure), failures);

            events.clear();
            switch (randomInt(2)) {
                case 0 -> SearchPhaseExecutor.executeDfsPhase(listener, ctx, () -> {});
                case 1 -> SearchPhaseExecutor.executeQueryPhase(listener, ctx, () -> {});
                default -> SearchPhaseExecutor.executeFetchPhase(listener, ctx, () -> {});
            }
            assertEquals(List.of("pre", "success"), events);
        }
    }
}
