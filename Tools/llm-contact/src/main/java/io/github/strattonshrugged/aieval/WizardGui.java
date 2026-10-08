package io.github.strattonshrugged.aieval;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Three-step Swing wizard — suites, targets, judges — that then turns into a
 * live log of the run. Closing the window at any point exits the process.
 */
final class WizardGui {

    private static final String[] EFFORTS = {"", "low", "medium", "high", "xhigh", "max"};

    private final Config config;
    private final int maxTokens;
    /** Completed with the process exit code when the window closes or the app crashes. */
    private final CompletableFuture<Integer> exitCode;

    private final JFrame frame = new JFrame("AI Evaluation");
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final List<JCheckBox> suiteBoxes = new ArrayList<>();
    private final ModelRows targetRows = new ModelRows();
    private final ModelRows judgeRows = new ModelRows();
    private final JTextArea log = new JTextArea();
    private final JLabel status = new JLabel("Running...");
    private final JButton close = new JButton("Close");

    private volatile boolean running;
    private volatile int runExitCode;

    private WizardGui(Config config, int maxTokens, CompletableFuture<Integer> exitCode) {
        this.config = config;
        this.maxTokens = maxTokens;
        this.exitCode = exitCode;
    }

    /** Shows the wizard and blocks until it's closed; returns the process exit code. */
    static int show(Config config, int maxTokens) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("No display available; use 'aieval run' instead");
        }
        CompletableFuture<Integer> exitCode = new CompletableFuture<>();
        // Anything uncaught — on the Swing event thread or the run worker —
        // is printed in full to the terminal that launched the GUI, then the
        // process exits non-zero rather than leaving a half-dead window up.
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            System.err.println("Fatal error in thread \"" + thread.getName() + "\":");
            e.printStackTrace();
            exitCode.complete(1);
        });

        List<String> suiteIds = Suite.listIds(config.suitesDir);
        if (suiteIds.isEmpty()) {
            throw new IllegalStateException("No suites found in " + config.suitesDir);
        }
        SwingUtilities.invokeAndWait(() -> {
            // Set before any component exists, so every one picks it up.
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // The default cross-platform look is fine too.
            }
            new WizardGui(config, maxTokens, exitCode).build(suiteIds);
        });
        return exitCode.get();
    }

    private void build(List<String> suiteIds) {
        deck.add(suitesScreen(suiteIds), "suites");
        deck.add(targetsScreen(), "targets");
        deck.add(judgesScreen(), "judges");
        deck.add(progressScreen(), "progress");

        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (running) {
                    System.err.println("Window closed during the run; exiting. Unfinished suite x target pairs were not saved.");
                }
                frame.dispose();
                exitCode.complete(running ? 1 : runExitCode);
            }
        });
        frame.setContentPane(deck);
        frame.setMinimumSize(new Dimension(620, 420));
        frame.setSize(760, 520);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ---- Screen 1: suites ----

    private JPanel suitesScreen(List<String> suiteIds) {
        JPanel list = verticalList();
        JButton next = new JButton("Next");
        next.setEnabled(false);
        for (String id : suiteIds) {
            JCheckBox box = new JCheckBox(id);
            box.addItemListener(e -> next.setEnabled(suiteBoxes.stream().anyMatch(JCheckBox::isSelected)));
            suiteBoxes.add(box);
            list.add(box);
        }
        next.addActionListener(e -> cards.show(deck, "targets"));
        return screen("Step 1 of 3: Test suites", "Select the suites to run.", list, null,
                selectAllButtons(() -> suiteBoxes), null, next);
    }

    // ---- Screen 2: targets ----

    private JPanel targetsScreen() {
        JButton next = new JButton("Next");
        next.setEnabled(false);
        targetRows.onChange(() -> next.setEnabled(targetRows.anySelected()));
        next.addActionListener(e -> cards.show(deck, "judges"));
        return screen("Step 2 of 3: Target AIs", "Select the AIs to run the tests against. "
                        + "Edit the model or effort if you don't want the defaults.",
                targetRows.panel, null, selectAllButtons(targetRows::boxes), back("suites"), next);
    }

    // ---- Screen 3: judges ----

    private JPanel judgesScreen() {
        JButton finish = new JButton("Finish");
        finish.addActionListener(e -> startRun());
        JLabel footnote = new JLabel("* If no judges are selected, each targeted AI will evaluate its own responses.");
        footnote.setFont(footnote.getFont().deriveFont(Font.ITALIC));
        return screen("Step 3 of 3: Judge AIs", "Select the AIs that will judge the responses as pass or fail.*",
                judgeRows.panel, footnote, selectAllButtons(judgeRows::boxes), back("targets"), finish);
    }

    // ---- Progress ----

    private JPanel progressScreen() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        close.setEnabled(false);
        close.addActionListener(e -> {
            frame.dispose();
            exitCode.complete(runExitCode);
        });
        JPanel left = new JPanel(new BorderLayout());
        left.add(status, BorderLayout.CENTER);
        return screen("Running", "Live log of the run. Run files are saved to Runs/ as each suite x target finishes.",
                log, null, left, null, close);
    }

    private void startRun() {
        List<String> suiteIds = suiteBoxes.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList();
        List<ModelSpec> targets = targetRows.selected();
        List<ModelSpec> judges = judgeRows.selected();
        cards.show(deck, "progress");
        running = true;

        PrintStream out = new PrintStream(new LogStream(System.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new LogStream(System.err), true, StandardCharsets.UTF_8);
        Thread worker = new Thread(() -> {
            int code;
            try {
                RunPlan plan = new RunPlan(config, suiteIds, targets, judges, maxTokens);
                List<String> missing = plan.describe(out);
                if (!missing.isEmpty()) {
                    err.println("\nNot running: set the missing key(s) in Tools/llm-contact/.env or the environment.");
                    code = 1;
                } else {
                    code = plan.execute(new LlmClient(config), out, err).anyFailed() ? 1 : 0;
                }
            } catch (Exception e) {
                err.println("\nRun failed:");
                e.printStackTrace(err);
                code = 1;
            }
            finish(code);
        }, "run-worker");
        worker.start();
    }

    private void finish(int code) {
        runExitCode = code;
        running = false;
        SwingUtilities.invokeLater(() -> {
            status.setText(code == 0 ? "Finished." : "Finished with errors; see the log.");
            close.setEnabled(true);
        });
    }

    // ---- Layout helpers ----

    private JPanel screen(String title, String subtitle, JComponent body, JComponent footnote,
                          JComponent left, JButton back, JButton forward) {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        // Let the title take the full width so the bold text is never clipped.
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, titleLabel.getPreferredSize().height));
        header.add(titleLabel);
        header.add(Box.createVerticalStrut(4));
        header.add(new JLabel(subtitle));
        header.add(Box.createVerticalStrut(8));

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(new JScrollPane(body), BorderLayout.CENTER);
        if (footnote != null) {
            center.add(footnote, BorderLayout.SOUTH);
        }

        JPanel right = new JPanel();
        if (back != null) {
            right.add(back);
        }
        right.add(forward);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);

        JPanel screen = new JPanel(new BorderLayout(0, 8));
        screen.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        screen.add(header, BorderLayout.NORTH);
        screen.add(center, BorderLayout.CENTER);
        screen.add(buttons, BorderLayout.SOUTH);
        return screen;
    }

    private JButton back(String card) {
        JButton back = new JButton("Back");
        back.addActionListener(e -> cards.show(deck, card));
        return back;
    }

    private static JPanel selectAllButtons(java.util.function.Supplier<List<JCheckBox>> boxes) {
        JButton all = new JButton("Select all");
        all.addActionListener(e -> boxes.get().forEach(b -> b.setSelected(true)));
        JButton none = new JButton("Select none");
        none.addActionListener(e -> boxes.get().forEach(b -> b.setSelected(false)));
        JPanel panel = new JPanel();
        panel.add(all);
        panel.add(none);
        return panel;
    }

    private static JPanel verticalList() {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        return list;
    }

    /** One row per provider: checkbox, editable model name, reasoning effort. */
    private static final class ModelRows {
        final JPanel panel = new JPanel(new GridBagLayout());
        private final List<JCheckBox> boxes = new ArrayList<>();
        private final List<JTextField> models = new ArrayList<>();
        private final List<JComboBox<String>> efforts = new ArrayList<>();

        ModelRows() {
            panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            addCell(bold("AI provider"), 0, 0, 0);
            addCell(bold("Model"), 1, 0, 1);
            addCell(bold("Effort"), 2, 0, 0);
            Provider[] providers = Provider.values();
            for (int i = 0; i < providers.length; i++) {
                JCheckBox box = new JCheckBox(providers[i].id());
                JTextField model = new JTextField(providers[i].defaultModel, 22);
                JComboBox<String> effort = new JComboBox<>(EFFORTS);
                effort.setRenderer(new DefaultListCellRenderer() {
                    @Override
                    public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                  boolean selected, boolean focus) {
                        return super.getListCellRendererComponent(list,
                                "".equals(value) ? "(none)" : value, index, selected, focus);
                    }
                });
                effort.setToolTipText("Leave as (none) for models without effort/thinking support");
                boxes.add(box);
                models.add(model);
                efforts.add(effort);
                addCell(box, 0, i + 1, 0);
                addCell(model, 1, i + 1, 1);
                addCell(effort, 2, i + 1, 0);
            }
            // Push the rows to the top of the scroll pane.
            GridBagConstraints filler = new GridBagConstraints();
            filler.gridy = providers.length + 1;
            filler.weighty = 1;
            panel.add(Box.createGlue(), filler);
        }

        private void addCell(JComponent c, int x, int y, double weightx) {
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = x;
            gc.gridy = y;
            gc.weightx = weightx;
            gc.anchor = GridBagConstraints.WEST;
            gc.fill = weightx > 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            gc.insets = new Insets(3, 4, 3, 8);
            panel.add(c, gc);
        }

        private static JLabel bold(String text) {
            JLabel label = new JLabel(text);
            label.setFont(label.getFont().deriveFont(Font.BOLD));
            return label;
        }

        List<JCheckBox> boxes() {
            return boxes;
        }

        void onChange(Runnable listener) {
            boxes.forEach(b -> b.addItemListener(e -> listener.run()));
        }

        boolean anySelected() {
            return boxes.stream().anyMatch(JCheckBox::isSelected);
        }

        List<ModelSpec> selected() {
            List<ModelSpec> specs = new ArrayList<>();
            Provider[] providers = Provider.values();
            for (int i = 0; i < providers.length; i++) {
                if (!boxes.get(i).isSelected()) {
                    continue;
                }
                String model = models.get(i).getText().trim();
                String effort = (String) efforts.get(i).getSelectedItem();
                specs.add(new ModelSpec(providers[i], model.isEmpty() ? providers[i].defaultModel : model,
                        effort == null || effort.isEmpty() ? null : effort));
            }
            return specs;
        }
    }

    /**
     * Appends each completed line to the log area (on the Swing thread) and
     * also echoes it to the terminal, so a run is still visible there if the
     * window goes away.
     */
    private final class LogStream extends OutputStream {
        private final PrintStream echo;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        LogStream(PrintStream echo) {
            this.echo = echo;
        }

        @Override
        public synchronized void write(int b) {
            line.write(b);
            if (b == '\n') {
                flush();
            }
        }

        @Override
        public synchronized void flush() {
            if (line.size() == 0) {
                return;
            }
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            echo.print(text);
            echo.flush();
            SwingUtilities.invokeLater(() -> {
                log.append(text);
                log.setCaretPosition(log.getDocument().getLength());
            });
        }
    }
}
