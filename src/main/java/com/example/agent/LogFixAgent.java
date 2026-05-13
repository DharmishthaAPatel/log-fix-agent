package com.example.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.cdimascio.dotenv.Dotenv;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.data.embedding.Embedding;
import okhttp3.*;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain4j agent that:
 *  1. Fetches Datadog logs and embeds them into an in-memory vector store (RAG).
 *  2. Uses GPT-4o via LangChain4j AiServices + @Tool methods to diagnose,
 *     fix Java source files via the GitHub API, and create a GitHub PR.
 *
 * Required env vars:
 *   OPENAI_API_KEY, DD_API_KEY, DD_APP_KEY,
 *   DD_SITE (default datadoghq.com), GITHUB_TOKEN, GITHUB_REPO (owner/repo)
 */
public class LogFixAgent {

    // ── config ────────────────────────────────────────────────────────────────

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Dotenv DOTENV = Dotenv.configure()
            .filename(".env")
            .ignoreIfMissing()
            .load();

    private static final String OPENAI_API_KEY = cfg("OPENAI_API_KEY", null);
    private static final String DD_SITE  = cfg("DD_SITE",  "datadoghq.com");
    private static final String DD_API   = cfg("DD_API_KEY",  null);
    private static final String DD_APP   = cfg("DD_APP_KEY",  null);
    private static final String GH_TOKEN = cfg("GITHUB_TOKEN", null);
    private static final String GH_REPO  = cfg("GITHUB_REPO",  null);

    private static final String GH_API   = "https://api.github.com/repos/" + GH_REPO;

    private static final OkHttpClient HTTP = new OkHttpClient();

    private static final Pattern ERROR_PATTERN = Pattern.compile("(\\w+Exception|\\w+Error)");

    // ── LangChain4j AiService interface ──────────────────────────────────────

    @SystemMessage("""
            You are a senior software engineer and SRE agent for the java-csv-datadog-demo project.
            This project reads pipe-delimited CSV files (id|customer_name|amount) and processes them
            row-by-row in CsvFileProcessor.java.

            The logs provided to you have already been filtered — InvalidRecordException entries have
            been removed. Focus only on the errors present in the context.

            Workflow — repeat for EACH distinct error type in the task:
            1. Call searchEmbeddedLogs with the error type name to retrieve representative log examples.
            2. Call listSourceFiles, then readFile on the relevant source file.
            3. Call createBranch with a name specific to that error (e.g. fix/null-pointer-enrichment).
            4. Call writeFile with the corrected content on that branch.
            5. Call createPullRequest describing the root cause of that specific error.
            Then move on to the next error type and repeat.

            If no errors are found in the logs, say so and do not create any PRs.
            Preserve existing code style. Only change what the logs indicate is broken.
            All file operations go directly to GitHub — do not use local filesystem or shell commands.
            """)
    interface LogFixAssistant {
        String analyze(String task);
    }

    static class RepoTools {

        private final EmbeddingStore<TextSegment> embeddingStore;
        private final EmbeddingModel embeddingModel;

        RepoTools(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
            this.embeddingStore = embeddingStore;
            this.embeddingModel = embeddingModel;
        }

        @Tool("Retrieve representative log examples for a specific error type from the embedded store.")
        public String searchEmbeddedLogs(
                @P("Error type to search for, e.g. 'NullPointerException' or 'TimeoutException'") String errorType) {
            Embedding queryEmbedding = embeddingModel.embed(errorType).content();
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, 10, 0.0);
            if (matches.isEmpty()) return "No log entries found for: " + errorType;
            StringBuilder sb = new StringBuilder();
            matches.forEach(m -> sb.append(m.embedded().text()).append("\n---\n"));
            return sb.toString();
        }

        @Tool("List all Java source files in the GitHub repository. Call this before reading files.")
        public String listSourceFiles() throws IOException {
            Request req = ghGet(GH_API + "/git/trees/HEAD?recursive=1");
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful()) return "GitHub error " + resp.code();
                ArrayNode tree = (ArrayNode) JSON.readTree(resp.body().string()).path("tree");
                List<String> files = new ArrayList<>();
                tree.forEach(node -> {
                    String path = node.path("path").asText();
                    if (path.endsWith(".java") && "blob".equals(node.path("type").asText()))
                        files.add(path);
                });
                return String.join("\n", files);
            }
        }

        @Tool("Read the full content of a file from the GitHub repository.")
        public String readFile(@P("File path in the repo, e.g. src/main/java/com/example/csvdatadog/App.java") String path)
                throws IOException {
            Request req = ghGet(GH_API + "/contents/" + path);
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful()) return "GitHub error " + resp.code() + ": not found: " + path;
                JsonNode data = JSON.readTree(resp.body().string());
                String encoded = data.path("content").asText().replaceAll("\\s", "");
                return new String(Base64.getDecoder().decode(encoded));
            }
        }

        @Tool("Commit a file with fixed content directly to a GitHub branch.")
        public String writeFile(
                @P("File path in the repo") String path,
                @P("Complete new file content with the bug fixed") String content,
                @P("Branch name to commit to, e.g. fix/null-pointer") String branch)
                throws IOException {
            // Fetch current file SHA (needed for updates)
            String sha = null;
            Request getReq = ghGet(GH_API + "/contents/" + path + "?ref=" + branch);
            try (Response getResp = HTTP.newCall(getReq).execute()) {
                if (getResp.isSuccessful())
                    sha = JSON.readTree(getResp.body().string()).path("sha").asText(null);
            }

            String encoded = Base64.getEncoder().encodeToString(content.getBytes());
            var bodyMap = new java.util.LinkedHashMap<String, String>();
            bodyMap.put("message", "fix: update " + path);
            bodyMap.put("content", encoded);
            bodyMap.put("branch", branch);
            if (sha != null) bodyMap.put("sha", sha);

            Request req = new Request.Builder()
                    .url(GH_API + "/contents/" + path)
                    .addHeader("Authorization", "Bearer " + GH_TOKEN)
                    .addHeader("Accept", "application/vnd.github+json")
                    .put(RequestBody.create(JSON.writeValueAsString(bodyMap), MediaType.get("application/json")))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful()) return "GitHub error " + resp.code() + ": " + resp.body().string();
                return "Committed " + path + " to branch " + branch;
            }
        }

        @Tool("Create a new branch on GitHub from main.")
        public String createBranch(@P("Branch name, e.g. fix/null-pointer-in-csv-processor") String branch)
                throws IOException {
            // Get HEAD SHA of main
            Request headReq = ghGet(GH_API + "/git/ref/heads/main");
            String sha;
            try (Response resp = HTTP.newCall(headReq).execute()) {
                if (!resp.isSuccessful()) return "GitHub error " + resp.code() + " getting main SHA";
                sha = JSON.readTree(resp.body().string()).path("object").path("sha").asText();
            }

            String payload = JSON.writeValueAsString(
                    java.util.Map.of("ref", "refs/heads/" + branch, "sha", sha));
            Request req = new Request.Builder()
                    .url(GH_API + "/git/refs")
                    .addHeader("Authorization", "Bearer " + GH_TOKEN)
                    .addHeader("Accept", "application/vnd.github+json")
                    .post(RequestBody.create(payload, MediaType.get("application/json")))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful()) return "GitHub error " + resp.code() + ": " + resp.body().string();
                return "Branch created: " + branch;
            }
        }

        @Tool("Open a GitHub pull request from a feature branch to main.")
        public String createPullRequest(
                @P("Git branch name, e.g. fix/null-pointer-in-csv-processor") String branch,
                @P("Short PR title") String title,
                @P("Markdown PR body: root cause, fix summary, test notes") String body)
                throws IOException {
            String payload = JSON.writeValueAsString(
                    java.util.Map.of("title", title, "body", body, "head", branch, "base", "main"));
            Request req = new Request.Builder()
                    .url(GH_API + "/pulls")
                    .addHeader("Authorization", "Bearer " + GH_TOKEN)
                    .addHeader("Accept", "application/vnd.github+json")
                    .post(RequestBody.create(payload, MediaType.get("application/json")))
                    .build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful())
                    return "GitHub error " + resp.code() + ": " + resp.body().string();
                JsonNode data = JSON.readTree(resp.body().string());
                return "PR created: " + data.path("html_url").asText();
            }
        }

        private static Request ghGet(String url) {
            return new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + GH_TOKEN)
                    .addHeader("Accept", "application/vnd.github+json")
                    .get()
                    .build();
        }
    }

    // ── RAG: fetch + embed Datadog logs ───────────────────────────────────────

    private static List<Document> fetchAndBuildDocuments(int minutes, String query)
            throws IOException {
        Instant now  = Instant.now();
        Instant from = now.minus(minutes, ChronoUnit.MINUTES);

        String body = JSON.writeValueAsString(java.util.Map.of(
                "filter", java.util.Map.of(
                        "query", query,
                        "from",  from.toString(),
                        "to",    now.toString()),
                "sort", "-timestamp",
                "page", java.util.Map.of("limit", 500)));

        Request req = new Request.Builder()
                .url("https://api." + DD_SITE + "/api/v2/logs/events/search")
                .addHeader("DD-API-KEY",         DD_API)
                .addHeader("DD-APPLICATION-KEY", DD_APP)
                .addHeader("Content-Type",       "application/json")
                .post(RequestBody.create(body, MediaType.get("application/json")))
                .build();

        List<Document> docs = new ArrayList<>();
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                System.err.println("Datadog error: " + resp.code() + " — " + resp.body().string());
                return docs;
            }
            ArrayNode events = (ArrayNode) JSON.readTree(resp.body().string()).path("data");
            System.out.println("Fetched " + events.size() + " log entries from Datadog.");
            events.forEach(e -> {
                JsonNode a = e.path("attributes");
                String message = a.path("message").asText();
                // Skip Datadog internal tracer/agent configuration logs
                if (message.contains("DATADOG TRACER CONFIGURATION") || message.startsWith("[dd.trace")) return;
                String stackTrace = a.path("attributes").path("stack_trace").asText("");
                String text = String.format("[%s] %s %s%s",
                        a.path("timestamp").asText(),
                        a.path("status").asText().toUpperCase(),
                        message,
                        stackTrace.isEmpty() ? "" : "\n" + stackTrace);
                Metadata meta = Metadata.from("level",    a.path("status").asText())
                        .put("trace_id", a.path("dd").path("trace_id").asText(""));
                docs.add(Document.from(text, meta));
            });
        }
        return docs;
    }

    public static void main(String[] args) throws Exception {
        validateEnv();

        // 1. RAG: fetch Datadog logs and embed them
        System.out.println("Fetching Datadog logs…");
        List<Document> logDocs = fetchAndBuildDocuments(
                120, "service:java-csv-datadog-demo");

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(OPENAI_API_KEY)
                .modelName("text-embedding-3-large")
                .build();
        EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();

        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        if (logDocs.isEmpty()) {
            System.out.println("No logs fetched — skipping embedding, agent will run without log context.");
        } else {
            // 1 API call for the filter query + 1 batch call for all docs
            Embedding filterEmbedding = embeddingModel.embed("InvalidRecordException").content();
            List<TextSegment> segments = new ArrayList<>();
            for (Document doc : logDocs) segments.add(TextSegment.from(doc.text()));
            List<Embedding> docEmbeddings = embeddingModel.embedAll(segments).content();

            int filtered = 0;
            for (int i = 0; i < segments.size(); i++) {
                String text = segments.get(i).text();
                boolean isInvalidRecord = text.contains("InvalidRecordException")
                        || cosineSimilarity(filterEmbedding.vector(), docEmbeddings.get(i).vector()) >= 0.5;
                if (isInvalidRecord) {
                    filtered++;
                } else {
                    embeddingStore.add(docEmbeddings.get(i), segments.get(i));
                    Matcher m = ERROR_PATTERN.matcher(text);
                    if (m.find()) errorCounts.merge(m.group(1), 1, Integer::sum);
                }
            }
            System.out.println("Filtered " + filtered + " InvalidRecordException log(s). Embedded "
                    + (logDocs.size() - filtered) + " clean entries into vector store.");
            System.out.println("Distinct error types: " + errorCounts);
        }

        // 2. OpenAI chat model via LangChain4j
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(OPENAI_API_KEY)
                .modelName("gpt-4o")
                .build();

        // 3. Assemble AiService with tools only — no passive retriever
        RepoTools tools = new RepoTools(embeddingStore, embeddingModel);
        LogFixAssistant assistant = AiServices.builder(LogFixAssistant.class)
                .chatLanguageModel(chatModel)
                .tools(tools)
                .build();

        // 4. Run the agent — pass error summary so LLM knows what types exist
        System.out.println("Running agent…\n");
        String task = errorCounts.isEmpty()
                ? "No errors found in Datadog logs. Nothing to fix."
                : "These error types were found in the Datadog logs (name=count): " + errorCounts
                  + ". For each error type, call searchEmbeddedLogs to get log examples, "
                  + "fix the root cause in the Java source, and open a separate GitHub PR.";
        String result = assistant.analyze(task);

        System.out.println("\n── Agent result ──────────────────────────────");
        System.out.println(result);
    }

    private static void validateEnv() {
        for (String v : List.of("OPENAI_API_KEY", "DD_API_KEY", "DD_APP_KEY",
                                "GITHUB_TOKEN", "GITHUB_REPO")) {
            if (DOTENV.get(v) == null) {
                System.err.println("Missing required config: " + v + " (set in .env or as env var)");
                System.exit(1);
            }
        }
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // .env file values take priority over system env vars
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
