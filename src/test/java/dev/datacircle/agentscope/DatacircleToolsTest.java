package dev.datacircle.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class DatacircleToolsTest {

    private static final String APIS = """
            [{"api": "Employee email", "slug": "email-finder", "plans": 5444, "providers": 300,
              "best": {"provider": "Emelia", "plan": "100,000 credits per month", "score": 76.6, "perThousand": 4.99}},
             {"api": "Company profile", "slug": "linkedin-company", "plans": 1, "providers": 1, "best": null}]
            """;
    private static final String BEST = """
            {"api": "Employee email", "slug": "email-finder", "total": 5444,
             "sheetPage": "https://datacircle.dev/email-finder-api-pricing",
             "plans": [{"provider": "Emelia", "plan": "Pro", "api": "Employee email", "rank": 1, "score": 76.64,
                        "perThousand": 4.99, "per": "call", "notFoundPerThousand": 0, "billing": "monthly",
                        "latencyMs": 812, "page": "https://datacircle.dev/find-email-apis/emelia/pro"}]}
            """;
    private static final String PLANS = """
            {"total": 2, "offset": 1, "plans": [{"provider": "Hunter", "plan": "Starter", "api": "People search",
             "rank": 18, "score": null, "perThousand": null, "page": "https://datacircle.dev/p/hunter"}]}
            """;
    private static final String PROVIDER = """
            {"provider": "Bright Data", "slug": "bright-data", "website": "https://brightdata.com",
             "apis": [{"api": "Employee profile", "slug": "linkedin-profile", "rank": 3,
                       "plans": [{"plan": "Pay as you go", "score": 60, "perThousand": 1.5, "per": "record"},
                                 {"plan": "Growth", "score": 71.25, "perThousand": 1.05, "per": "record"}]},
                      {"api": "SERP", "slug": "serp", "rank": null, "plans": []}]}
            """;
    private static final List<String> NAMES =
            List.of("datacircle_best_plans", "datacircle_list_apis", "datacircle_provider", "datacircle_search_plans");

    private static HttpServer server;
    private static final List<String> requests = new ArrayList<>();
    private DatacircleTools tools;

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/", exchange -> {
            String target = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() == null ? "" : "?" + exchange.getRequestURI().getRawQuery());
            requests.add(target);
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            if (path.equals("/v1/apis")) {
                body = APIS;
            } else if (path.equals("/v1/apis/email-finder/best")) {
                body = BEST;
            } else if (path.equals("/v1/apis/broken/best")) {
                body = "{\"api\": ";
            } else if (path.equals("/v1/plans") && target.contains("query=busy")) {
                status = 429;
                body = "{\"error\": \"too many calls\"}";
            } else if (path.equals("/v1/plans")) {
                body = PLANS;
            } else if (path.equals("/v1/providers/bright-data")) {
                body = PROVIDER;
            } else {
                status = 404;
                body = "{\"error\": \"no API has the slug nope\", \"slugs\": [\"email-finder\"]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        requests.clear();
        tools = new DatacircleTools(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/", Duration.ofSeconds(5));
    }

    @Test
    void listsApisAndFiltersThemByWords() {
        assertEquals(
                "- Employee email (email-finder): 5,444 plans from 300 providers. Best: Emelia, 100,000 credits per "
                        + "month, score 76.6/100, $4.99 per 1,000.\n"
                        + "- Company profile (linkedin-company): 1 plan from 1 provider.\n"
                        + "2 of 2 APIs. Pass a slug to datacircle_best_plans or datacircle_search_plans.",
                text(tools.listApis(null)));
        assertTrue(text(tools.listApis("EMAIL finder")).startsWith("- Employee email (email-finder)"));
        assertTrue(text(tools.listApis("EMAIL finder")).contains("1 of 2 APIs."));
        assertEquals(
                "No API's name or slug holds every word of \"zzz\". Leave the query empty to list 2 APIs.",
                text(tools.listApis(" zzz ")));
    }

    @Test
    void givesTheBestPlans() {
        assertEquals(
                "Employee email (email-finder): the best 1 of 5,444 plans. "
                        + "Sheet: https://datacircle.dev/email-finder-api-pricing\n"
                        + "1. Emelia, Pro: score 76.6/100, $4.99 per 1,000 calls, $0 per 1,000 not found, monthly, "
                        + "median 812 ms. https://datacircle.dev/find-email-apis/emelia/pro",
                text(tools.bestPlans(" Email-Finder ", 3)));
        assertEquals(List.of("/v1/apis/email-finder/best?limit=3"), requests);
    }

    @Test
    void searchesThePlans() {
        assertEquals(
                "Found 2 plans; showing 2 to 2.\n"
                        + "- Hunter, Starter (People search): rank 18, not scored, no listed price. "
                        + "https://datacircle.dev/p/hunter",
                text(tools.searchPlans("people search", "hunter io", "Price", 5, 1)));
        assertEquals(List.of("/v1/plans?sort=price&limit=5&offset=1&api=people+search&query=hunter+io"), requests);
    }

    @Test
    void givesAProvidersRanks() {
        assertEquals(
                "Bright Data (https://brightdata.com) sells 2 APIs. Its rank among each API's providers:\n"
                        + "- Employee profile (linkedin-profile): rank 3, 2 plans. Best: Growth, score 71.3/100, "
                        + "$1.05 per 1,000 records.\n"
                        + "- SERP (serp): not ranked, 0 plans.",
                text(tools.provider("Bright Data")));
        assertEquals(List.of("/v1/providers/bright-data"), requests);
    }

    @Test
    void saysWhatWentWrongAsAnErrorResult() {
        assertError(
                "Error: Datacircle API request failed: HTTP 404. no API has the slug nope. "
                        + "Call datacircle_list_apis for every API's slug.",
                tools.bestPlans("nope", null));
        assertError(
                "Error: Datacircle API request failed: HTTP 429. too many calls. The API allows 60 calls a minute "
                        + "from one address. Retry in a minute.",
                tools.searchPlans(null, "busy", null, null, null));
        assertError("Error: The Datacircle API returned invalid JSON.", tools.bestPlans("broken", null));
        assertError("Error: limit must be between 1 and 50", tools.bestPlans("email-finder", 51));
        assertError("Error: sort must be one of [price, provider, score]", tools.searchPlans(null, null, "x", null, null));
        assertError("Error: offset must be 0 or more", tools.searchPlans(null, null, null, null, -1));
        assertError("Error: api must not be empty", tools.bestPlans(" ", null));
        assertTrue(text(tools.provider("北京")).startsWith("Error: Datacircle writes each provider's name in Latin letters"));
        assertTrue(requests.stream().noneMatch(r -> r.contains("limit=51")));
        assertEquals(ToolResultBlock.text("x").getState(), tools.listApis(null).getState());
    }

    @Test
    void saysWhenTheApiIsUnreachable() {
        DatacircleTools offline = new DatacircleTools("http://127.0.0.1:1/v1", Duration.ofSeconds(2));
        assertError(
                "Error: The Datacircle API refused the connection. Check the network and the base URL.",
                offline.listApis(null));
    }

    @Test
    void aToolkitGivesTheModelFourReadOnlyToolsBesideTheAppsOwn() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AppTools());
        toolkit.registerTool(tools);
        Map<String, ToolSchema> byName = new TreeMap<>();
        for (ToolSchema schema : toolkit.getToolSchemas()) {
            byName.put(schema.getName(), schema);
        }
        List<String> names = new ArrayList<>(NAMES);
        names.add(0, "app_note");
        assertEquals(names, new ArrayList<>(byName.keySet()));
        assertEquals(
                Set.of("api", "query", "sort", "limit", "offset"),
                properties(byName.get("datacircle_search_plans")).keySet());
        assertEquals("integer", properties(byName.get("datacircle_best_plans")).get("limit").get("type"));
        assertEquals(List.of("api"), required(byName.get("datacircle_best_plans")));
        assertEquals(List.of("provider"), required(byName.get("datacircle_provider")));
        assertEquals(List.of(), required(byName.get("datacircle_search_plans")));
        assertEquals(List.of(), required(byName.get("datacircle_list_apis")));
        assertFalse(byName.values().stream().anyMatch(s -> s.getDescription() == null || s.getDescription().isBlank()));
        for (String name : NAMES) {
            assertTrue(toolkit.getTool(name).isReadOnly(), name);
        }
        assertFalse(toolkit.getTool("app_note").isReadOnly());
    }

    @Test
    void theToolkitRunsEachToolOnTheModelsArguments() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        String answer = call(toolkit, "datacircle_best_plans", Map.of("api", "email-finder", "limit", 3));
        assertTrue(answer.startsWith("Employee email (email-finder): the best 1"), answer);
        assertTrue(answer.contains("\n1. Emelia"), answer);
        assertTrue(requests.contains("/v1/apis/email-finder/best?limit=3"), requests.toString());
        assertTrue(call(toolkit, "datacircle_provider", Map.of("provider", "bright-data")).startsWith("Bright Data"));
        assertTrue(call(toolkit, "datacircle_list_apis", Map.of()).contains("2 of 2 APIs"));
        assertTrue(call(toolkit, "datacircle_search_plans", Map.of("query", "hunter")).startsWith("Found 2 plans"));
        assertTrue(call(toolkit, "datacircle_best_plans", Map.of("api", "nope")).startsWith("Error: Datacircle API"));
    }

    @Test
    void aReActAgentCallsEachToolInItsLoop() {
        ScriptedModel model = new ScriptedModel();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AppTools());
        toolkit.registerTool(tools);
        ReActAgent agent = ReActAgent.builder()
                .name("assistant")
                .sysPrompt("Answer with the tools you are given.")
                .model(model)
                .toolkit(toolkit)
                .build();

        String answer = agent.call(user("Who is best for finding work emails?")).block().getTextContent();

        assertEquals(2, model.calls);
        assertEquals(5, model.toolsSeen.size());
        assertTrue(answer.contains("datacircle_list_apis gave: - Employee email (email-finder): 5,444 plans"), answer);
        assertTrue(answer.contains("datacircle_best_plans gave: Employee email (email-finder): the best 1"), answer);
        assertTrue(answer.contains("datacircle_search_plans gave: Found 2 plans"), answer);
        assertTrue(answer.contains("datacircle_provider gave: Bright Data (https://brightdata.com) sells 2 APIs"), answer);
        assertTrue(answer.contains("app_note gave: \"noted\""), answer);
    }

    @Test
    void exploreModeRunsTheToolsAndDeniesTheAppsWritingOne() {
        ScriptedModel model = new ScriptedModel();
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new AppTools());
        toolkit.registerTool(tools);
        ReActAgent agent = ReActAgent.builder()
                .name("assistant")
                .sysPrompt("Answer with the tools you are given.")
                .model(model)
                .toolkit(toolkit)
                .permissionContext(PermissionContextState.builder().mode(PermissionMode.EXPLORE).build())
                .build();

        String answer = agent.call(user("Who is best for finding work emails?")).block().getTextContent();

        assertTrue(answer.contains("datacircle_best_plans gave: Employee email (email-finder): the best 1"), answer);
        assertTrue(answer.contains("datacircle_provider gave: Bright Data"), answer);
        assertTrue(answer.contains("app_note gave: "), answer);
        assertFalse(answer.contains("app_note gave: \"noted\""), answer);
        assertEquals(4, requests.size(), requests.toString());
    }

    /** An app's own tool beside ours, one that writes. */
    public static class AppTools {
        @Tool(name = "app_note", description = "Write a note in the app.")
        public String note(@ToolParam(name = "text", description = "The note") String text) {
            return "noted";
        }
    }

    /** Calls the five tools at once, then answers with the first line of each result. */
    static final class ScriptedModel implements Model {
        int calls;
        final List<String> toolsSeen = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> schemas, GenerateOptions options) {
            calls++;
            if (calls == 1) {
                schemas.forEach(schema -> toolsSeen.add(schema.getName()));
            }
            List<String> results = new ArrayList<>();
            for (Msg message : messages) {
                for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                    results.add(result.getName() + " gave: " + text(result).split("\n")[0]);
                }
            }
            List<ContentBlock> content = new ArrayList<>();
            if (results.isEmpty()) {
                content.add(use("1", "datacircle_list_apis", Map.of("query", "email")));
                content.add(use("2", "datacircle_best_plans", Map.of("api", "email-finder", "limit", 3)));
                content.add(use("3", "datacircle_search_plans", Map.of("query", "hunter", "sort", "price")));
                content.add(use("4", "datacircle_provider", Map.of("provider", "Bright Data")));
                content.add(use("5", "app_note", Map.of("text", "emails")));
            } else {
                content.add(TextBlock.builder().text(String.join("\n", results)).build());
            }
            return Flux.just(ChatResponse.builder().id("r" + calls).content(content).build());
        }

        @Override
        public String getModelName() {
            return "scripted";
        }
    }

    static ToolUseBlock use(String id, String name, Map<String, Object> input) {
        String json = input.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": " + (e.getValue() instanceof String s ? "\"" + s + "\"" : e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
        return ToolUseBlock.builder().id(id).name(name).input(new LinkedHashMap<>(input)).content(json).build();
    }

    static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).name("user").textContent(text).build();
    }

    static String call(Toolkit toolkit, String name, Map<String, Object> input) {
        ToolCallParam param = ToolCallParam.builder().toolUseBlock(use("1", name, input)).input(input).build();
        return text(toolkit.callTool(param).block());
    }

    static String text(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(block -> ((TextBlock) block).getText())
                .collect(Collectors.joining());
    }

    /** The text, and the state AgentScope's own error results have: ERROR from 2.0.5 on. */
    private static void assertError(String expected, ToolResultBlock result) {
        assertEquals(expected, text(result));
        assertEquals(ToolResultBlock.error("x").getState(), result.getState());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> properties(ToolSchema schema) {
        return (Map<String, Map<String, Object>>) schema.getParameters().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(ToolSchema schema) {
        Object required = schema.getParameters().get("required");
        return required == null ? List.of() : (List<String>) required;
    }
}
