package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cdimascio.dotenv.Dotenv;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.protocol.McpClientMessage;
import dev.langchain4j.mcp.client.protocol.McpInitializeRequest;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.service.tool.ToolProvider;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class LogFixAgent {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Dotenv DOTENV = Dotenv.configure()
            .filename(".env")
            .ignoreIfMissing()
            .load();

    private static final String OPENAI_API_KEY  = cfg("OPENAI_API_KEY", null);
    private static final String DD_SITE         = cfg("DD_SITE", "datadoghq.com");
    private static final String DD_API          = cfg("DD_API_KEY", null);
    private static final String DD_APP          = cfg("DD_APP_KEY", null);
    private static final String GH_TOKEN        = cfg("GITHUB_TOKEN", null);
    private static final String GH_REPO         = cfg("GITHUB_REPO", null);
    private static final String MCP_SERVER_URL  = cfg("MCP_SERVER_URL", null);

    // Derived from DD_SITE: us5.datadoghq.com → https://mcp.us5.datadoghq.com/api/unstable/mcp-server/mcp
    private static final String DD_MCP_URL =
            "https://mcp." + DD_SITE + "/api/unstable/mcp-server/mcp";

    @SystemMessage("""
            You are a senior software engineer and SRE agent for the java-csv-datadog-demo project.
            This project reads pipe-delimited CSV files (id|customer_name|amount) and processes them
            row-by-row in CsvFileProcessor.java.

            Workflow:
            1. Call search_datadog_logs with query "service:java-csv-datadog-demo status:error" and
               time range "now-2h" to find recent errors. Ignore any InvalidRecordException entries —
               those are expected validation errors, not bugs. Focus only on unexpected exceptions.
            2. Identify each distinct unexpected error type (from error.kind or stack traces).
            3. For each distinct error type:
               a. Browse the repository source tree to understand the code structure.
               b. Read the relevant Java source file to understand the root cause.
               c. Create a fix branch named for that error (e.g. fix/null-pointer-enrichment).
               d. Commit the corrected file content to that branch.
               e. Open a pull request describing the root cause and the fix.

            If no unexpected errors are found in the logs, say so and do not create any PRs.
            Preserve existing code style. Only change what the logs indicate is broken.
            """)
    interface LogFixAssistant {
        String analyze(String task);
    }

    public static void main(String[] args) throws Exception {
        validateEnv();

        // Datadog MCP client — authenticates with API key headers
        McpClient ddMcpClient = new DefaultMcpClient.Builder()
                .transport(new ResourceAwareMcpTransport(
                        new StreamableHttpMcpTransport(DD_MCP_URL, Map.of(
                                "DD-API-KEY",         DD_API,
                                "DD-APPLICATION-KEY", DD_APP))))
                .build();

        // GitHub MCP client — authenticates with Bearer token
        McpClient ghMcpClient = new DefaultMcpClient.Builder()
                .transport(new ResourceAwareMcpTransport(
                        new StreamableHttpMcpTransport(MCP_SERVER_URL, Map.of(
                                "Authorization", "Bearer " + GH_TOKEN))))
                .build();

        ToolProvider mcpToolProvider = McpToolProvider.builder()
                .mcpClients(List.of(ddMcpClient, ghMcpClient))
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(OPENAI_API_KEY)
                .modelName("gpt-4o-mini")
                .maxRetries(5)
                .build();

        LogFixAssistant assistant = AiServices.builder(LogFixAssistant.class)
                .chatModel(chatModel)
                .toolProvider(mcpToolProvider)
                .build();

        System.out.println("Running agent…\n");
        String task = "Repository: " + GH_REPO + ". "
                + "Search Datadog logs for service:java-csv-datadog-demo to find unexpected errors. "
                + "Ignore InvalidRecordException. For each real error, fix the root cause in the "
                + "Java source and open a separate GitHub PR.";

        try {
            String result = assistant.analyze(task);
            System.out.println("\n── Agent result ──────────────────────────────");
            System.out.println(result);
        } finally {
            ddMcpClient.close();
            ghMcpClient.close();
        }
    }

    private static void validateEnv() {
        for (String v : List.of("OPENAI_API_KEY", "DD_API_KEY", "DD_APP_KEY",
                                "GITHUB_TOKEN", "GITHUB_REPO", "MCP_SERVER_URL")) {
            if (cfg(v, null) == null) {
                System.err.println("Missing required config: " + v + " (set in .env or as env var)");
                System.exit(1);
            }
        }
    }

    // Implements the MCP streamable-HTTP transport (spec 2025-03-26).
    // Each operation is a POST with Accept: application/json, text/event-stream.
    // The server may reply with plain JSON or an SSE stream; both are handled.
    private static class StreamableHttpMcpTransport implements McpTransport {

        private final String url;
        private final Map<String, String> headers;
        private final OkHttpClient client;
        private volatile String sessionId;

        StreamableHttpMcpTransport(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
            this.client = new OkHttpClient.Builder()
                    .readTimeout(java.time.Duration.ofMinutes(2))
                    .build();
        }

        @Override public void start(McpOperationHandler h) {}
        @Override public void checkHealth() {}
        @Override public void onFailure(Runnable r) {}
        @Override public void close() { client.connectionPool().evictAll(); }

        @Override
        public CompletableFuture<JsonNode> initialize(McpInitializeRequest req) {
            return post(req);
        }

        @Override
        public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage msg) {
            return post(msg);
        }

        @Override
        public void executeOperationWithoutResponse(McpClientMessage msg) {
            post(msg);
        }

        private CompletableFuture<JsonNode> post(Object payload) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Request.Builder req = new Request.Builder()
                            .url(url)
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .post(RequestBody.create(
                                    JSON.writeValueAsBytes(payload),
                                    MediaType.get("application/json")));
                    headers.forEach(req::header);
                    if (sessionId != null) req.header("Mcp-Session-Id", sessionId);

                    try (Response resp = client.newCall(req.build()).execute()) {
                        String sid = resp.header("Mcp-Session-Id");
                        if (sid != null) sessionId = sid;
                        if (!resp.isSuccessful())
                            throw new RuntimeException("HTTP " + resp.code() + ": " + resp.body().string());

                        String body = resp.body().string();
                        String ct   = resp.header("Content-Type", "");

                        if (ct.contains("text/event-stream")) {
                            for (String line : body.split("\n")) {
                                line = line.trim();
                                if (line.startsWith("data:")) {
                                    String data = line.substring(5).trim();
                                    if (data.startsWith("{")) return JSON.readTree(data);
                                }
                            }
                            throw new RuntimeException("No JSON-RPC data in SSE response:\n" + body);
                        }
                        return JSON.readTree(body);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // Wraps any McpTransport and converts MCP "resource" content items to "text"
    // so that langchain4j-mcp beta5 (which only handles "text"/"image") doesn't throw.
    private static class ResourceAwareMcpTransport implements McpTransport {

        private final McpTransport delegate;

        ResourceAwareMcpTransport(McpTransport delegate) {
            this.delegate = delegate;
        }

        @Override public void start(McpOperationHandler handler)                  { delegate.start(handler); }
        @Override public void executeOperationWithoutResponse(McpClientMessage m)  { delegate.executeOperationWithoutResponse(m); }
        @Override public void checkHealth()                                        { delegate.checkHealth(); }
        @Override public void onFailure(Runnable r)                               { delegate.onFailure(r); }
        @Override public void close() throws IOException                           { delegate.close(); }

        @Override
        public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) {
            return delegate.initialize(request);
        }

        @Override
        public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage message) {
            return delegate.executeOperationWithResponse(message)
                    .thenApply(ResourceAwareMcpTransport::patchResourceContent);
        }

        private static JsonNode patchResourceContent(JsonNode response) {
            JsonNode content = response.path("result").path("content");
            if (!content.isArray()) return response;
            for (JsonNode item : content) {
                if (!"resource".equals(item.path("type").asText())) continue;
                ObjectNode node = (ObjectNode) item;
                JsonNode resource = node.path("resource");
                String text = resource.has("text")
                        ? resource.path("text").asText()
                        : resource.path("uri").asText("[binary resource]");
                node.put("type", "text");
                node.put("text", text);
                node.remove("resource");
            }
            return response;
        }
    }

    private static String cfg(String key, String fallback) {
        return DOTENV.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE).stream()
                .filter(e -> e.getKey().equals(key))
                .map(e -> e.getValue())
                .findFirst()
                .orElseGet(() -> {
                    String sys = System.getenv(key);
                    return sys != null ? sys : fallback;
                });
    }
}
