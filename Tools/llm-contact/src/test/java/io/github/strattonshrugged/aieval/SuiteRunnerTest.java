package io.github.strattonshrugged.aieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SuiteRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ModelSpec TARGET = ModelSpec.parse("anthropic");
    private static final ModelSpec JUDGE_A = ModelSpec.parse("openai");
    private static final ModelSpec JUDGE_B = ModelSpec.parse("mistral");

    private static Suite suite(Suite.TestCase... tests) {
        return new Suite("Test Suite", "desc", null, null, null, List.of(tests));
    }

    private static Suite.TestCase test(String name, Integer maxTokens) {
        return new Suite.TestCase(name, "d", "prompt for " + name, TextNode.valueOf("be good"), maxTokens);
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    @Test
    void recordsReplyAndEachJudgmentIndependently() {
        List<String> calls = new ArrayList<>();
        ModelCaller caller = (model, maxTokens, prompt, schema) -> {
            calls.add(model.provider().id() + ":" + maxTokens);
            if (model.equals(TARGET)) return "the reply";
            if (model.equals(JUDGE_A)) return "{\"verdict\":\"pass\",\"reasoning\":\"fine\"}";
            return "{\"verdict\":\"fail\",\"reasoning\":\"not fine\"}";
        };

        RunRecord run = new SuiteRunner(caller, 1024, quiet())
                .run("S", suite(test("t1", null), test("t2", 4096)), TARGET, "site", List.of(JUDGE_A, JUDGE_B));

        assertEquals(List.of("anthropic:1024", "openai:1024", "mistral:1024",
                "anthropic:4096", "openai:4096", "mistral:4096"), calls);
        assertEquals(2, run.tests().size());
        RunRecord.TestResult t1 = run.tests().get(0);
        assertEquals("the reply", t1.reply());
        assertNull(t1.error());
        assertEquals(2, t1.judgments().size());
        assertEquals("pass", t1.judgments().get(0).verdict());
        assertEquals("openai", t1.judgments().get(0).judgeProvider());
        assertEquals("fail", t1.judgments().get(1).verdict());
        assertEquals("not fine", t1.judgments().get(1).reasoning());
        assertEquals(List.of("openai", "mistral"), run.judges().stream().map(RunRecord.JudgeInfo::provider).toList());
    }

    @Test
    void targetFailureIsRecordedOnThatTestAndSkipsJudging() {
        ModelCaller caller = (model, maxTokens, prompt, schema) -> {
            if (model.equals(TARGET) && prompt.contains("t1")) throw new RuntimeException("boom\ndetails");
            return model.equals(TARGET) ? "ok" : "{\"verdict\":\"pass\",\"reasoning\":\"r\"}";
        };

        RunRecord run = new SuiteRunner(caller, 100, quiet())
                .run("S", suite(test("t1", null), test("t2", null)), TARGET, "site", List.of(JUDGE_A));

        RunRecord.TestResult t1 = run.tests().get(0);
        assertNull(t1.reply());
        assertTrue(t1.error().startsWith("Target call failed: boom"));
        assertTrue(t1.judgments().isEmpty());
        assertEquals("pass", run.tests().get(1).judgments().get(0).verdict());
    }

    @Test
    void oneJudgeFailingOrMisformattingDoesNotAffectTheOthers() {
        ModelCaller caller = (model, maxTokens, prompt, schema) -> {
            if (model.equals(TARGET)) return "reply";
            if (model.equals(JUDGE_A)) throw new RuntimeException("judge down");
            return "I think it passes";
        };

        RunRecord run = new SuiteRunner(caller, 100, quiet())
                .run("S", suite(test("t1", null)), TARGET, "site", List.of(JUDGE_A, JUDGE_B, TARGET));

        List<RunRecord.Judgment> judgments = run.tests().get(0).judgments();
        assertEquals("Judge call failed: judge down", judgments.get(0).error());
        assertNull(judgments.get(0).judgeFormatOk());
        assertNull(judgments.get(1).verdict());
        assertFalse(judgments.get(1).judgeFormatOk());
        assertEquals("I think it passes", judgments.get(1).reasoning());
        assertEquals(3, judgments.size());
    }

    @Test
    void judgeResolutionPrefersSuiteDeclaredThenSelectedThenSelf() {
        Suite declares = new Suite("x", null, "gemini", null, "high", List.of());
        assertEquals(List.of(new ModelSpec(Provider.GEMINI, "gemini-3.5-flash", "high")),
                SuiteRunner.resolveJudges(declares, TARGET, List.of(JUDGE_A)));
        assertEquals(List.of(JUDGE_A, JUDGE_B), SuiteRunner.resolveJudges(suite(), TARGET, List.of(JUDGE_A, JUDGE_B)));
        assertEquals(List.of(TARGET), SuiteRunner.resolveJudges(suite(), TARGET, List.of()));
    }

    @Test
    void writesSuiteFirstUtcFilenameAndAvoidsCollisions(@TempDir Path dir) throws Exception {
        RunRecord run = new RunRecord("LLM01-X", "n", "d", "anthropic", "claude-sonnet-5", "site", null,
                List.of(), "2026-07-14T04:13:22.123Z", List.of());

        Path first = SuiteRunner.write(dir, run);
        Path second = SuiteRunner.write(dir, run);

        assertEquals("LLM01-X_20260714T041322Z_claude-sonnet-5.json", first.getFileName().toString());
        assertEquals("LLM01-X_20260714T041322Z_claude-sonnet-5-2.json", second.getFileName().toString());
        JsonNode written = MAPPER.readTree(Files.readString(first));
        assertEquals("claude-sonnet-5", written.get("target_model").asText());
        assertTrue(written.has("judges"));
        assertTrue(written.get("target_effort").isNull());
    }

    @Test
    void filenameSanitizesModelNames() {
        assertEquals("S_20260101T000000Z_org-model-v1",
                SuiteRunner.runFileBase("S", "org/model:v1", Instant.parse("2026-01-01T00:00:00Z")));
    }
}
