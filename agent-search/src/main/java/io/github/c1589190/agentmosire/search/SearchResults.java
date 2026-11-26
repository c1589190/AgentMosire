package io.github.c1589190.agentmosire.search;

import java.util.List;

public record SearchResults(List<SearchHit> hits, int skipped) {
    public SearchResults(List<SearchHit> hits) {
        this(hits, 0);
    }

    public SearchResults {
        hits = List.copyOf(hits);
        if (skipped < 0) {
            throw new IllegalArgumentException("The skipped count must be nonnegative");
        }
    }
}
