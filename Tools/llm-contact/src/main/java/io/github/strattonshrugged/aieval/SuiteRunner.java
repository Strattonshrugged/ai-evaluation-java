package io.github.strattonshrugged.aieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one suite against one target: each test's prompt goes to the target,
 * the reply is recorded, then the reply + criteria go to every judge and each
 * judgment is recorded on its own.
 */
public final class SuiteRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** UTC, ISO 8601 basic format (YYYYMMDDTHHMMSSZ) — sorts chronologically as a plain string. */
    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final ModelCaller caller;
    private final int defaultMaxTokens;
    private final PrintStream log;

    public SuiteRunner(ModelCaller caller, int defaultMaxTokens, PrintStream log) {
        this.caller = caller;
        this.defaultMaxTokens = defaultMaxTokens;
        this.log = log;
    }

    /**
     * The judges that will actually grade this suite for this target: the
     * suite's own declared judge if it has one (authoritative), otherwise the
     * selected judges, otherwise the target judges itself.
     */
    public static List<ModelSpec> resolveJudges(Suite suite, ModelSpec target, List<ModelSpec> selectedJudges) {
        ModelSpec declared = suite.declaredJudge();
        if (declared != null) {
            return List.of(declared);
        }
        return selectedJudges.isEmpty() ? List.of(target) : selectedJudges;
    }

    public RunRecord run(String suiteId, Suite suite, ModelSpec target, String targetSite, List<ModelSpec> judges) {
        log.printf("Running suite: %s (%d test%s)%n", suiteId, suite.tests().size(), suite.tests().size() == 1 ? "" : "s");
        log.println("  Target: " + target);
        judges.forEach(j -> log.println("  Judge:  " + j));

        List<RunRecord.TestResult> results = new ArrayList<>();
        for (Suite.TestCase test : suite.tests()) {
            if (!test.isComplete()) {
                log.printf("  Skipping a test in \"%s\" missing required field(s): name, prompt, judgment_criteria%n", suiteId);
                continue;
            }
            log.println("  Running test: " + test.name());
            results.add(runTest(test, target, judges));
        }

        return new RunRecord(
                suiteId,
                suite.suiteName(),
                suite.description(),
                target.provider().id(),
                target.model(),
                targetSite,
                target.effort(),
                judges.stream().map(RunRecord.JudgeInfo::of).toList(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                results);
    }

    private RunRecord.TestResult runTest(Suite.TestCase test, ModelSpec target, List<ModelSpec> judges) {
        // A test may declare its own max_tokens for both its target and judge
        // calls — for the rare reply that legitimately needs more budget
        // without raising the ceiling (and cost) for every other test.
        int maxTokens = test.maxTokens() != null && test.maxTokens() > 0 ? test.maxTokens() : defaultMaxTokens;
        String severity = Judging.severityOf(test.judgmentCriteria());

        String reply;
        try {
            reply = caller.call(target, maxTokens, test.prompt(), null);
        } catch (Exception e) {
            // Recorded on this one test rather than aborting the run, so one
            // bad call doesn't cost every other test's results.
            String error = "Target call failed: " + e.getMessage();
            log.println("    Error: " + firstLine(error));
            return new RunRecord.TestResult(test.name(), test.description(), test.prompt(), test.judgmentCriteria(),
                    severity, maxTokens, null, error, List.of());
        }

        String judgePrompt = Judging.buildJudgePrompt(test, reply);
        List<RunRecord.Judgment> judgments = new ArrayList<>();
        for (ModelSpec judge : judges) {
            judgments.add(judge(judge, maxTokens, judgePrompt));
        }
        return new RunRecord.TestResult(test.name(), test.description(), test.prompt(), test.judgmentCriteria(),
                severity, maxTokens, reply, null, judgments);
    }

    private RunRecord.Judgment judge(ModelSpec judge, int maxTokens, String judgePrompt) {
        String provider = judge.provider().id();
        try {
            String text = caller.call(judge, maxTokens, judgePrompt, Judging.JUDGMENT_SCHEMA);
            Judging.ParsedVerdict parsed = Judging.parseVerdict(text);
            if (!parsed.formatOk()) {
                log.printf("    Warning: %s requested JSON but the reply wasn't a well-formed {verdict, reasoning}%n", judge);
            }
            log.printf("    %-8s %s%n", parsed.verdict() != null ? parsed.verdict() : "?", judge);
            return new RunRecord.Judgment(provider, judge.model(), judge.effort(),
                    parsed.verdict(), parsed.reasoning(), parsed.formatOk(), null);
        } catch (Exception e) {
            String error = "Judge call failed: " + e.getMessage();
            log.printf("    Error from %s: %s%n", judge, firstLine(error));
            return new RunRecord.Judgment(provider, judge.model(), judge.effort(), null, null, null, error);
        }
    }

    /**
     * Writes {@code Runs/<suite>_<UTC-timestamp>_<target-model>.json} — suite
     * first so every target's attempt at the same suite sorts together.
     * Appends -2, -3, ... on the (unexpected) same-second collision.
     */
    public static Path write(Path runsDir, RunRecord run) throws IOException {
        Files.createDirectories(runsDir);
        String base = runFileBase(run.suite(), run.targetModel(), Instant.parse(run.timestamp()));
        Path path = runsDir.resolve(base + ".json");
        for (int n = 2; Files.exists(path); n++) {
            path = runsDir.resolve(base + "-" + n + ".json");
        }
        MAPPER.writeValue(path.toFile(), run);
        return path;
    }

    static String runFileBase(String suiteId, String targetModel, Instant timestamp) {
        // Model names can contain characters that aren't filename-safe on every OS.
        String safeModel = targetModel.replaceAll("[^A-Za-z0-9._-]", "-");
        return suiteId + "_" + FILENAME_TIMESTAMP.format(timestamp) + "_" + safeModel;
    }

    private static String firstLine(String s) {
        int newline = s.indexOf('\n');
        return newline < 0 ? s : s.substring(0, newline);
    }
}
