package io.github.strattonshrugged.aieval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Terminal menus for whichever of suites/targets/judges weren't given on the
 * command line. Reads plain lines (not System.console()) so it also works
 * under {@code gradlew run}; end-of-input counts as a blank answer.
 */
final class InteractivePicker {

    private final BufferedReader in;
    private final PrintStream out;

    InteractivePicker(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    List<String> pickSuites(List<String> available) throws IOException {
        if (available.isEmpty()) {
            throw new IllegalStateException("No suites found in Suites/");
        }
        out.println("\nSuites:");
        for (int i = 0; i < available.size(); i++) {
            out.printf("  %d) %s%n", i + 1, available.get(i));
        }
        while (true) {
            String answer = prompt("Select suites (numbers, comma-separated, or 'all'): ");
            if (answer.isEmpty()) {
                throw new IllegalStateException("No suites selected");
            }
            try {
                return parseSuiteSelection(answer, available);
            } catch (IllegalArgumentException e) {
                out.println("  " + e.getMessage());
            }
        }
    }

    List<ModelSpec> pickModels(String role, boolean allowBlank, String blankMeaning) throws IOException {
        out.printf("%n%s: pick providers by number (uses the default model), or type provider[:model[:effort]]:%n", role);
        Provider[] providers = Provider.values();
        for (int i = 0; i < providers.length; i++) {
            out.printf("  %d) %-10s default model: %s%n", i + 1, providers[i].id(), providers[i].defaultModel);
        }
        while (true) {
            String answer = prompt(allowBlank
                    ? "Select " + role.toLowerCase() + " (comma-separated; blank = " + blankMeaning + "): "
                    : "Select " + role.toLowerCase() + " (comma-separated): ");
            if (answer.isEmpty()) {
                if (allowBlank) {
                    return List.of();
                }
                throw new IllegalStateException("No " + role.toLowerCase() + " selected");
            }
            try {
                return parseModelSelection(answer);
            } catch (IllegalArgumentException e) {
                out.println("  " + e.getMessage());
            }
        }
    }

    private String prompt(String text) throws IOException {
        out.print(text);
        out.flush();
        String line = in.readLine();
        return line == null ? "" : line.trim();
    }

    /** "all", or comma-separated 1-based indexes and/or suite ids. Order kept, duplicates dropped. */
    static List<String> parseSuiteSelection(String answer, List<String> available) {
        if (answer.trim().equalsIgnoreCase("all")) {
            return available;
        }
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        for (String token : answer.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.matches("\\d+")) {
                int index = Integer.parseInt(t) - 1;
                if (index < 0 || index >= available.size()) {
                    throw new IllegalArgumentException("No suite numbered " + t);
                }
                picked.add(available.get(index));
            } else if (available.contains(t)) {
                picked.add(t);
            } else {
                throw new IllegalArgumentException("Unknown suite: " + t);
            }
        }
        return new ArrayList<>(picked);
    }

    /** Comma-separated 1-based provider indexes and/or provider[:model[:effort]] specs. */
    static List<ModelSpec> parseModelSelection(String answer) {
        Provider[] providers = Provider.values();
        List<ModelSpec> picked = new ArrayList<>();
        for (String token : answer.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.matches("\\d+")) {
                int index = Integer.parseInt(t) - 1;
                if (index < 0 || index >= providers.length) {
                    throw new IllegalArgumentException("No provider numbered " + t);
                }
                picked.add(new ModelSpec(providers[index], providers[index].defaultModel, null));
            } else {
                picked.add(ModelSpec.parse(t));
            }
        }
        return picked;
    }
}
