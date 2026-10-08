package io.github.strattonshrugged.aieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParsingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void modelSpecFillsDefaultsAndParsesAllSegments() {
        assertEquals(new ModelSpec(Provider.ANTHROPIC, "claude-sonnet-5", null), ModelSpec.parse("anthropic"));
        assertEquals(new ModelSpec(Provider.OPENAI, "gpt-x", null), ModelSpec.parse("OpenAI:gpt-x"));
        assertEquals(new ModelSpec(Provider.ANTHROPIC, "claude-sonnet-5", "max"), ModelSpec.parse("anthropic::max"));
        assertEquals(2, ModelSpec.parseList("anthropic, mistral:mistral-small-latest,").size());
        assertThrows(IllegalArgumentException.class, () -> ModelSpec.parse("nope"));
        assertThrows(IllegalArgumentException.class, () -> ModelSpec.parse("anthropic:a:b:c"));
    }

    @Test
    void criteriaFormattingHandlesLegacyAndStructured() throws Exception {
        assertEquals("Evaluation criteria:\nbe nice", Judging.formatCriteria(TextNode.valueOf("be nice")));
        assertNull(Judging.severityOf(TextNode.valueOf("be nice")));

        JsonNode structured = MAPPER.readTree(
                "{\"pass_conditions\":[\"a\",\"b\"],\"fail_conditions\":[\"c\"],\"severity_if_fail\":\"High\"}");
        String text = Judging.formatCriteria(structured);
        assertTrue(text.startsWith("This test passes only if ALL"));
        assertTrue(text.contains("- a\n- b"));
        assertTrue(text.contains("automatically FAILS") && text.endsWith("- c"));
        assertEquals("High", Judging.severityOf(structured));

        JsonNode passOnly = MAPPER.readTree("{\"pass_conditions\":[\"a\"]}");
        assertFalse(Judging.formatCriteria(passOnly).contains("FAILS"));
        assertEquals("Medium", Judging.severityOf(passOnly));
    }

    @Test
    void verdictParsing() {
        assertEquals(new Judging.ParsedVerdict("pass", "ok", true),
                Judging.parseVerdict("{\"verdict\":\"pass\",\"reasoning\":\"ok\"}"));
        assertEquals(new Judging.ParsedVerdict(null, "{\"verdict\":\"maybe\"}", false),
                Judging.parseVerdict("{\"verdict\":\"maybe\"}"));
        assertEquals(new Judging.ParsedVerdict(null, "```json\n{}\n```", false),
                Judging.parseVerdict("```json\n{}\n```"));
        assertFalse(Judging.parseVerdict("").formatOk());
    }

    @Test
    void selectionParsing() {
        List<String> available = List.of("A", "B", "C");
        assertEquals(available, InteractivePicker.parseSuiteSelection("all", available));
        assertEquals(List.of("C", "A"), InteractivePicker.parseSuiteSelection("3, A, 3", available));
        assertThrows(IllegalArgumentException.class, () -> InteractivePicker.parseSuiteSelection("4", available));
        assertThrows(IllegalArgumentException.class, () -> InteractivePicker.parseSuiteSelection("Z", available));

        List<ModelSpec> models = InteractivePicker.parseModelSelection("1, gemini:gemini-x:low");
        assertEquals(new ModelSpec(Provider.ANTHROPIC, "claude-sonnet-5", null), models.get(0));
        assertEquals(new ModelSpec(Provider.GEMINI, "gemini-x", "low"), models.get(1));
    }

    @Test
    void repoSuitesLoad() throws Exception {
        Path suitesDir = Path.of("../../Suites");
        List<String> ids = Suite.listIds(suitesDir);
        assertTrue(ids.contains("LLM01-Prompt-Injection"));
        for (String id : ids) {
            Suite suite = Suite.load(suitesDir, id);
            assertNotNull(suite.suiteName());
            assertTrue(suite.tests().stream().allMatch(Suite.TestCase::isComplete), id);
        }
    }
}
