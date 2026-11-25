package dev.datacircle.agentscope;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Toolkit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Each tool once on the live API, through AgentScope's own Toolkit. Run with DATACIRCLE_LIVE=1. */
@EnabledIfEnvironmentVariable(named = "DATACIRCLE_LIVE", matches = "1")
class DatacircleToolsLiveTest {

    private final Toolkit toolkit = new Toolkit();

    DatacircleToolsLiveTest() {
        toolkit.registerTool(new DatacircleTools());
    }

    private String call(String name, Map<String, Object> input) {
        String answer = DatacircleToolsTest.call(toolkit, name, input);
        System.out.println(answer);
        return answer;
    }

    @Test
    void listsTheApis() {
        String answer = call("datacircle_list_apis", Map.of("query", "email"));
        assertTrue(answer.contains("(email-finder): "), answer);
        assertTrue(answer.contains(" APIs. Pass a slug to datacircle_best_plans"), answer);
    }

    @Test
    void givesTheBestPlans() {
        String answer = call("datacircle_best_plans", Map.of("api", "email-finder", "limit", 3));
        assertTrue(answer.startsWith("Employee email (email-finder): the best 3 of "), answer);
        assertTrue(answer.contains("\n1. ") && answer.contains("\n3. "), answer);
    }

    @Test
    void searchesThePlans() {
        String answer = call("datacircle_search_plans", Map.of("query", "hunter", "sort", "price", "limit", 3));
        assertTrue(answer.startsWith("Found ") && answer.contains("; showing 1 to 3."), answer);
        assertTrue(answer.contains("- Hunter, "), answer);
    }

    @Test
    void givesAProvidersRanks() {
        String answer = call("datacircle_provider", Map.of("provider", "Hunter"));
        assertTrue(answer.startsWith("Hunter (https://hunter.io) sells "), answer);
        assertTrue(answer.contains(": rank "), answer);
    }
}
