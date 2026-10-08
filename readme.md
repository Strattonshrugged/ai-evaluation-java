# ai-evaluation-java

### Objective
A basic LLM red-teaming/eval harness, written in Java. It replaces the JavaScript harness from AI-Evaluation-Portfolio and keeps the same Suites/Runs/Reports layout and suite format. It's a working portfolio, so it'll change as I explore new tools and methods. My background is Quality Assurance, so expect any departures from AI-evaluation best practice to lean that way.

### Design
A run has three selections:

1. **Suites** to run: the test templates in `Suites/`.
2. **Targets**: the AIs under test. Each test's prompt goes to each target, and the reply is recorded.
3. **Judges**: the AIs that grade those replies. Each reply goes to every judge along with the test's judgment criteria. Each judge must return a structured `{verdict, reasoning}`, where the verdict is pass or fail, and every judge's assessment is recorded separately.

There's deliberately **no aggregate pass/fail** yet. A Run records what each judge said and nothing more. Combining judges (majority, unanimity, or anything else) and evaluating a Run as a whole are planned as a separate, later step.

Judges can be from a different model family than the target. That's the point of cross-family judging: a model shouldn't grade its own output leniently. With no judges selected, each target judges itself. A suite can also declare its own fixed `judge_provider`/`judge_model`/`judge_effort`. When it does, that judge replaces the selected ones, so the suite is graded the same way no matter who runs it.

The judge request uses whichever structured-output mechanism the provider supports:

- strict schema for Anthropic and OpenAI
- JSON-syntax mode for Mistral, xAI and DeepSeek
- Gemini's native response schema

Not every provider enforces the exact shape. A judge that doesn't return a parseable `{verdict, reasoning}` gets `verdict: null` and `judge_format_ok: false`, and its raw text goes in `reasoning` for a human to check.

Failures are recorded rather than allowed to sink the run:

- If the **target** call fails (network error, provider error, or a reply with no usable text), the test's `error` is set, it has no judgments, and the run continues.
- If a single **judge** call fails, only that judgment's `error` is set.

### Suites
Suites use the same JSON format as AI-Evaluation-Portfolio. Suite-level fields are `suiteID`, `owasp_description`, optional judge fields, and `tests[]`. Each test has `name`, `description`, `prompt` and `judgment_criteria`. The criteria are either a plain string or `{pass_conditions[], fail_conditions[]?, severity_if_fail?}`.

`fail_conditions` are an unconditional veto: if any one of them is met, the test fails no matter which pass conditions were also met. A test can also set its own `max_tokens`, which overrides the default for that test's target and judge calls.

### Runs
There's one file per suite × target: `Runs/<suite>_<UTC-timestamp>_<target-model>.json`, with the timestamp in `YYYYMMDDTHHMMSSZ` form. Putting the suite first makes every target's attempt at a suite sort together.

```json
{
  "suite": "LLM06-Excessive-Agency", "suite_name": "...", "suite_description": "...",
  "target_provider": "anthropic", "target_model": "claude-sonnet-5", "target_site": "...", "target_effort": null,
  "judges": [{ "provider": "mistral", "model": "mistral-small-latest", "effort": null }, ...],
  "timestamp": "2026-10-07T21:14:03.512Z",
  "tests": [{
    "name": "...", "description": "...", "prompt": "...", "judgment_criteria": { ... },
    "severity": "High", "max_tokens": 1024, "reply": "...", "error": null,
    "judgments": [{ "judge_provider": "mistral", "judge_model": "mistral-small-latest", "judge_effort": null,
                    "verdict": "pass", "reasoning": "...", "judge_format_ok": true, "error": null }, ...]
  }]
}
```

### Layout
```
ai-evaluation-java/
├── .github/workflows/run-suites.yml   # GitHub Actions: manually trigger a run
├── Suites/                            # Test templates: prompts + judgment criteria
├── Runs/                              # Test products: recorded replies and judgments
├── Reports/                           # LLM summaries and human findings
└── Tools/
    └── llm-contact/                   # The harness (Gradle, Java 21)
```

### Usage
Requires JDK 21. The Gradle wrapper downloads Gradle itself.

1. `cd Tools/llm-contact`
2. Copy `.env.example` to `.env` and fill in keys for the providers you'll use as targets **or** judges.
3. Build once with `./gradlew installDist`. On Windows, use `gradlew.bat installDist`.
4. Run `build/install/aieval/bin/aieval gui` for the GUI, or `aieval run` for the command line. On Windows, use `build\install\aieval\bin\aieval.bat gui`.

**GUI:** `aieval gui` opens a three-step window:

1. Check the test suites to run.
2. Check the target AIs. Each AI is one row: a provider with an editable model and an optional effort.
3. Check the judge AIs. If you leave them all unchecked, each target evaluates its own responses.

Each screen has Select all/none, and screens 2 and 3 have Back. Clicking **Finish** turns the window into a live log of the run, which is also echoed to the terminal. Closing the window at any point exits the process. Anything that crashes the GUI prints a full stack trace to the terminal.

**Command line:** any of `--suites`, `--targets` or `--judges` you leave out is asked for in an interactive menu. You can also pass everything up front:

```
aieval run --suites LLM01-Prompt-Injection,LLM06-Excessive-Agency \
           --targets anthropic:claude-sonnet-5:max,openai,deepseek \
           --judges mistral,gemini
```

- **Model specs** are `provider[:model[:effort]]`. Leave out the model to use the provider's default, and leave out effort for models that don't support it.
- **`--suites all`** runs every suite.
- **`--judges self`** has each target judge itself.
- **`--dry-run`** prints the plan, the number of API calls, and any missing API keys without calling anything.
- **`--no-prompt`** disables the interactive menus, for scripts and CI.
- **`--max-tokens N`** overrides the default per-call ceiling of `MAX_TOKENS`, or 1024 if that's unset.

Other commands:

- `aieval list` shows suites, providers, default models, and which keys are set.
- `aieval test-connections` sends a one-word prompt to every provider with a key set. It writes nothing to `Runs/`.
- `aieval contact <provider[:model[:effort]]> <prompt...>` sends a single ad-hoc prompt.
- `./gradlew test` runs the unit tests. They make no API calls.

Each provider's endpoint can be overridden with `<PROVIDER>_SITE`, for example `ANTHROPIC_SITE`.

**GitHub Actions:**

1. Add the API keys as repository secrets.
2. Go to **Actions** → **Run Test Suites** → **Run workflow**, and enter the suites, targets and optionally judges.
3. The Run files are committed back to the repo and also uploaded as a workflow artifact.

### LLM-as-judge
LLMs are immensely complex devices with bias, fallibility, and incorrect facts hard-coded into their training. Any results here should not be counted on without additional testing. This process or repository is not affiliated with any company and all findings are my own personal opinion.

Using an LLM as a judge is a bit like using one measuring stick against another measuring stick, it may tell you which is longer and shorter but won't tell you a precise length. The tests are structured to provide strict instructions on what constitutes a pass or fail result allowing a more consistent evaluation beyond assessment of the initial reply. Because LLM inputs can be vast and the response processes have weights I am not privy to, any trust put into an LLM should be disproportionate to the potential outcome of relying upon its answer.
