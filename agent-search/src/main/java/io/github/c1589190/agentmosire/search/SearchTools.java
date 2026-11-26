package io.github.c1589190.agentmosire.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.c1589190.agentmosire.tool.JsonValues;
import io.github.c1589190.agentmosire.tool.Resource;
import io.github.c1589190.agentmosire.tool.ToolDefinition;
import io.github.c1589190.agentmosire.tool.ToolId;
import io.github.c1589190.agentmosire.tool.ToolModule;
import io.github.c1589190.agentmosire.tool.ToolResult;

import static io.github.c1589190.agentmosire.tool.ToolResult.Code.*;

public final class SearchTools {
    public static final ToolId SEARCH = new ToolId("web", "search");
    public static final ToolId FETCH = new ToolId("web", "fetch");
    private static final int MAX_RESULT_CHARS = 16_000;
    private static final String SEARCH_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string","minLength":1,"maxLength":128},
            "count":{"type":"integer","minimum":1,"maximum":50}},
            "required":["query"],"additionalProperties":false}
            """;
    private static final String FETCH_SCHEMA = """
            {"type":"object","properties":{"url":{"type":"string","minLength":1,"maxLength":8192}},
            "required":["url"],"additionalProperties":false}
            """;

    private SearchTools() { }

    public static ToolModule search(SearchProvider provider) {
        return module(Objects.requireNonNull(provider), null);
    }

    public static ToolModule fetch(FetchProvider provider) {
        return module(null, Objects.requireNonNull(provider));
    }

    /** The host owns the providers and may configure either capability independently. */
    public static ToolModule module(SearchProvider search, FetchProvider fetch) {
        var tools = new ArrayList<ToolDefinition>();
        if (search != null) {
            tools.add(new ToolDefinition(SEARCH, "Search public web pages for links, titles and snippets",
                    SEARCH_SCHEMA, true, args -> List.of(new Resource("web", "public", "search")),
                    (caller, args) -> ToolDefinition.Verdict.ALLOW,
                    (args, access) -> find(search, args)));
        }
        if (fetch != null) {
            tools.add(new ToolDefinition(FETCH, "Retrieve the Markdown text of a public web page",
                    FETCH_SCHEMA, true, args -> List.of(), (caller, args) -> ToolDefinition.Verdict.ALLOW,
                    (args, access) -> fetch(fetch, args, access)));
        }
        if (tools.isEmpty()) {
            throw new IllegalArgumentException("At least one web provider is required");
        }
        return new ToolModule("web", tools);
    }

    private static ToolResult find(SearchProvider provider, Map<String, Object> args) {
        try {
            var request = new SearchRequest((String) args.get("query"),
                    ((Number) args.getOrDefault("count", SearchRequest.DEFAULT_COUNT)).intValue());
            var response = provider.search(request);
            var results = new ArrayList<Map<String, Object>>();
            int remaining = MAX_RESULT_CHARS;
            int skipped = response.skipped();
            boolean truncated = false;
            for (var hit : response.hits()) {
                String url;
                try {
                    url = new FetchRequest(hit.url()).url().toASCIIString();
                } catch (IllegalArgumentException invalidHit) {
                    skipped++;
                    continue;
                }
                if (results.size() == request.count()) {
                    truncated = true;
                    break;
                }
                var item = new LinkedHashMap<String, Object>();
                item.put("url", url);
                item.put("title", clip(hit.title(), 300));
                item.put("snippet", clip(hit.snippet(), 600));
                if (hit.date() != null) {
                    item.put("date", clip(hit.date(), 64));
                }
                remaining -= 1 + JsonValues.write(item).length();
                if (remaining < 0) {
                    truncated = true;
                    break;
                }
                results.add(item);
            }
            return new ToolResult(OK, "Web search results",
                    Map.of("results", results, "skipped", skipped, "truncated", truncated));
        } catch (IllegalArgumentException invalid) {
            return ToolResult.of(INVALID_ARGUMENTS, "Invalid web search request");
        } catch (WebProviderException failed) {
            return providerFailure(failed);
        }
    }

    private static ToolResult fetch(FetchProvider provider, Map<String, Object> args, ToolDefinition.Access access) {
        FetchRequest request;
        try {
            request = FetchRequest.of((String) args.get("url"));
        } catch (IllegalArgumentException invalid) {
            return ToolResult.of(INVALID_ARGUMENTS, "Expected a public HTTP(S) URL");
        }
        access.require(new Resource("web", request.host(), "fetch"));
        if (!provider.confinesToRequestedHost()) {
            access.require(new Resource("web", "public", "fetch"));
        }
        try {
            var page = provider.fetch(request);
            return new ToolResult(OK, "Public web page", Map.of("url", request.url().toASCIIString(),
                    "title", clip(page.title(), 300), "content", clip(page.content(), MAX_RESULT_CHARS),
                    "truncated", page.content().length() > MAX_RESULT_CHARS));
        } catch (WebProviderException failed) {
            return providerFailure(failed);
        }
    }

    private static String clip(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    private static ToolResult providerFailure(WebProviderException failure) {
        return switch (failure.kind()) {
            case INVALID_REQUEST -> ToolResult.of(INVALID_ARGUMENTS, "The web provider rejected the request");
            case INTERRUPTED -> ToolResult.of(CANCELLED, "Web request was interrupted");
            default -> ToolResult.of(FAILED, "Web provider returned " + failure.kind());
        };
    }
}
