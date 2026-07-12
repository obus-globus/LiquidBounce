import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small Windows-friendly front end for Injector. No dependencies beyond the JDK. */
public final class InjectorUi extends JFrame {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern TASKLIST_CSV = Pattern.compile("^\"([^\"]+)\",\"([0-9]+)\"");

    private final ProcessTableModel processModel = new ProcessTableModel();
    private final JTable processTable = new JTable(processModel);
    private final JTextField agentField = new JTextField();
    private final JTextArea logArea = new JTextArea();
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton injectButton = new JButton("Inject LiquidBounce");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private SwingWorker<Void, String> logTailWorker;
    private volatile boolean tailingInjectionLog;

    private InjectorUi(String initialAgent) {
        super("LiquidBounce Injector");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(690, 500));
        setSize(760, 560);
        setLocationByPlatform(true);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(new EmptyBorder(14, 14, 14, 14));
        setContentPane(content);

        JLabel heading = new JLabel("Running Minecraft instances");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 16f));
        content.add(heading, BorderLayout.NORTH);

        processTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        processTable.setFillsViewportHeight(true);
        processTable.setRowHeight(24);
        processTable.getColumnModel().getColumn(0).setPreferredWidth(90);
        processTable.getColumnModel().getColumn(0).setMaxWidth(120);
        processTable.getSelectionModel().addListSelectionListener(this::selectionChanged);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(new JScrollPane(processTable), BorderLayout.CENTER);

        JPanel agentPanel = new JPanel(new BorderLayout(8, 0));
        agentPanel.add(new JLabel("Agent JAR:"), BorderLayout.WEST);
        agentField.setText(initialAgent);
        agentField.setToolTipText("LiquidBounce agent JAR to load into the selected Minecraft JVM");
        agentField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { updateInjectEnabled(); }
            @Override public void removeUpdate(DocumentEvent event) { updateInjectEnabled(); }
            @Override public void changedUpdate(DocumentEvent event) { updateInjectEnabled(); }
        });
        agentPanel.add(agentField, BorderLayout.CENTER);
        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(event -> browseForAgent());
        agentPanel.add(browseButton, BorderLayout.EAST);
        center.add(agentPanel, BorderLayout.SOUTH);
        content.add(center, BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(100, 145));
        logScroll.setBorder(BorderFactory.createTitledBorder("Injection log"));

        JPanel actions = new JPanel(new BorderLayout(10, 8));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        refreshButton.addActionListener(event -> refreshProcesses());
        injectButton.addActionListener(event -> inject());
        buttons.add(refreshButton);
        buttons.add(injectButton);
        progressBar.setStringPainted(true);
        progressBar.setString("Ready");
        actions.add(progressBar, BorderLayout.NORTH);
        actions.add(logScroll, BorderLayout.CENTER);
        actions.add(buttons, BorderLayout.SOUTH);
        content.add(actions, BorderLayout.SOUTH);

        getRootPane().setDefaultButton(injectButton);
        updateInjectEnabled();
        refreshProcesses();
    }

    private void selectionChanged(ListSelectionEvent ignored) {
        updateInjectEnabled();
    }

    private void updateInjectEnabled() {
        injectButton.setEnabled(processTable.getSelectedRow() >= 0 && new File(agentField.getText().trim()).isFile());
    }

    private void browseForAgent() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select LiquidBounce agent JAR");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Java archives (*.jar)", "jar"));
        File current = new File(agentField.getText().trim());
        if (current.exists()) chooser.setSelectedFile(current);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            agentField.setText(chooser.getSelectedFile().getAbsolutePath());
            updateInjectEnabled();
        }
    }

    private void refreshProcesses() {
        setBusy(true, "Searching for Minecraft ...", 10);
        appendLog("Searching for running Minecraft JVMs ...");
        new SwingWorker<DiscoveryResult, Void>() {
            @Override protected DiscoveryResult doInBackground() {
                return findMinecraftProcesses();
            }

            @Override protected void done() {
                try {
                    DiscoveryResult discovery = get();
                    List<MinecraftProcess> found = discovery.processes;
                    processModel.setRows(found);
                    if (!found.isEmpty()) processTable.setRowSelectionInterval(0, 0);
                    if (found.isEmpty()) {
                        appendLog("No Minecraft or Java JVM process found.");
                    } else if (discovery.fallback) {
                        appendLog("Minecraft command lines are unavailable; showing " + found.size()
                                + " Java process(es). Select the Minecraft PID.");
                    } else {
                        appendLog("Found " + found.size() + " Minecraft JVM(s).");
                    }
                    progressBar.setValue(100);
                    progressBar.setString(found.isEmpty() ? "No Java process found"
                            : discovery.fallback ? "Select the Minecraft PID" : "Ready");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendLog("Process search interrupted.");
                } catch (ExecutionException e) {
                    appendLog("Process search failed: " + rootMessage(e));
                    progressBar.setString("Search failed");
                } finally {
                    setBusy(false, progressBar.getString(), progressBar.getValue());
                }
            }
        }.execute();
    }

    private void inject() {
        int selected = processTable.getSelectedRow();
        if (selected < 0) return;
        MinecraftProcess process = processModel.row(selected);
        File jar = new File(agentField.getText().trim());
        if (!jar.isFile()) {
            JOptionPane.showMessageDialog(this, "Agent JAR not found:\n" + jar.getAbsolutePath(),
                    "Missing agent", JOptionPane.ERROR_MESSAGE);
            return;
        }

        appendLog("Starting injection into PID " + process.pid + ".");
        tailingInjectionLog = false;
        File injectionLog;
        try {
            injectionLog = new File(System.getProperty("java.io.tmpdir"), "liquidbounce-injection-" + process.pid + ".log");
            Files.writeString(injectionLog.toPath(), "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            startInjectionLogTail(injectionLog);
            appendLog("Dedicated injection log: " + injectionLog.getAbsolutePath());
        } catch (Exception logFailure) {
            injectionLog = null;
            appendLog("Could not create the dedicated injection log: " + logFailure.getMessage());
        }
        File finalInjectionLog = injectionLog;
        setBusy(true, "Attaching ...", 15);
        new SwingWorker<Void, LogUpdate>() {
            @Override protected Void doInBackground() throws Exception {
                String agentArgs = finalInjectionLog == null ? "" : "logFile=" + finalInjectionLog.getAbsolutePath();
                Injector.inject(Long.toString(process.pid), jar, agentArgs, message -> {
                    int progress = message.startsWith("Attached") ? 40
                            : message.startsWith("Loading") ? 45
                            : message.startsWith("Agent loaded") ? 70 : 20;
                    publish(new LogUpdate(message, progress));
                });
                return null;
            }

            @Override protected void process(List<LogUpdate> updates) {
                for (LogUpdate update : updates) {
                    appendLog(update.message);
                    progressBar.setValue(update.progress);
                    progressBar.setString(update.message);
                }
            }

            @Override protected void done() {
                try {
                    get();
                    if (tailingInjectionLog) {
                        appendLog("Agent delivered; waiting for LiquidBounce initialization to finish.");
                        setProgressIfHigher(70, "Initializing LiquidBounce ...");
                    } else {
                        appendLog("Injection completed successfully (target log unavailable). ");
                        progressBar.setValue(100);
                        progressBar.setString("Injection successful");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendLog("Injection interrupted.");
                    progressBar.setString("Interrupted");
                } catch (ExecutionException e) {
                    String message = rootMessage(e);
                    appendLog("INJECTION FAILED: " + message);
                    progressBar.setString("Injection failed");
                    if (logTailWorker != null) logTailWorker.cancel(true);
                    tailingInjectionLog = false;
                    JOptionPane.showMessageDialog(InjectorUi.this,
                            "Injection failed:\n" + message + "\n\nSee the injection log for details.",
                            "LiquidBounce Injector", JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (!tailingInjectionLog)
                        setBusy(false, progressBar.getString(), progressBar.getValue());
                }
            }
        }.execute();
    }

    private void startInjectionLogTail(File logFile) {
        if (logTailWorker != null && !logTailWorker.isDone()) logTailWorker.cancel(true);
        tailingInjectionLog = true;
        long startOffset = logFile.length();
        logTailWorker = new SwingWorker<>() {
            private boolean sawInitializationSuccess;

            @Override protected Void doInBackground() throws Exception {
                long deadline = System.currentTimeMillis() + 5 * 60_000L;
                try (RandomAccessFile input = new RandomAccessFile(logFile, "r")) {
                    // Only show lines produced by this injection, not the entire Minecraft session.
                    input.seek(Math.min(startOffset, input.length()));
                    while (!isCancelled() && System.currentTimeMillis() < deadline) {
                        String encodedLine = input.readLine();
                        if (encodedLine == null) {
                            Thread.sleep(150);
                            continue;
                        }
                        String line = new String(encodedLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                        if (isInjectionLogLine(line)) publish(line);
                        if (line.contains("LiquidBounce initialization completed")) {
                            sawInitializationSuccess = true;
                            deadline = Math.min(deadline, System.currentTimeMillis() + 15_000L);
                        }
                        if (line.contains("[LB-INJECT]")
                                && (line.contains("restored original state") || line.contains("initialization failed"))
                                || line.contains("Game crashed")) {
                            break;
                        }
                    }
                }
                return null;
            }

            @Override protected void process(List<String> lines) {
                for (String line : lines) {
                    appendInjectionLog(line);
                    applyInjectionProgress(line);
                }
            }

            @Override protected void done() {
                if (!isCancelled() && sawInitializationSuccess && progressBar.getValue() < 100) {
                    progressBar.setValue(100);
                    progressBar.setString("LiquidBounce injection complete");
                    appendLog("LiquidBounce injection completed successfully.");
                } else if (!isCancelled() && progressBar.getValue() < 100) {
                    appendLog("Stopped waiting for the LiquidBounce completion marker.");
                    progressBar.setString("Injection log monitoring ended");
                }
                tailingInjectionLog = false;
                setBusy(false, progressBar.getString(), progressBar.getValue());
            }
        };
        logTailWorker.execute();
    }

    private static boolean isInjectionLogLine(String line) {
        return line.contains("[LB-INJECT]")
                || line.contains("LiquidBounce initialization completed");
    }

    private void applyInjectionProgress(String line) {
        if (line.contains("staging LB bundle"))
            setProgressIfHigher(48, "Staging LiquidBounce bundle ...");
        else if (line.contains("inaccessible already-loaded AW classes"))
            setProgressIfHigher(52, "Checking loaded classes ...");
        else if (line.contains("phase A:"))
            setProgressIfHigher(58, "Transforming mixin targets ...");
        else if (line.contains("converted "))
            setProgressIfHigher(63, "Converting targets ...");
        else if (line.contains("retransformed "))
            setProgressIfHigher(70, "Retransforming loaded classes ...");
        else if (line.contains("scheduled LB bootstrap"))
            setProgressIfHigher(75, "Bootstrap scheduled ...");
        else if (line.contains("unfroze ") || line.contains("thawed "))
            setProgressIfHigher(80, "Preparing registries ...");
        else if (line.contains("callEvent(ClientStartEvent)"))
            setProgressIfHigher(85, "Starting LiquidBounce ...");
        else if (line.contains("ClientStartEvent dispatched"))
            setProgressIfHigher(88, "Loading LiquidBounce ...");
        else if (line.contains("LiquidBounce initialization completed"))
            setProgressIfHigher(95, "LiquidBounce initialized; restoring registries ...");
        else if (line.contains("restored original state")) {
            progressBar.setValue(100);
            progressBar.setString("LiquidBounce injection complete");
            appendLog("LiquidBounce injection completed successfully.");
        } else if (line.contains("FAILED") || line.contains("initialization failed")) {
            progressBar.setString("LiquidBounce injection reported a failure");
        }
    }

    private void setProgressIfHigher(int value, String text) {
        if (value >= progressBar.getValue()) {
            progressBar.setValue(value);
            progressBar.setString(text);
        }
    }

    private void setBusy(boolean busy, String text, int progress) {
        refreshButton.setEnabled(!busy);
        processTable.setEnabled(!busy);
        agentField.setEnabled(!busy);
        progressBar.setValue(progress);
        progressBar.setString(text);
        if (busy) injectButton.setEnabled(false); else updateInjectEnabled();
    }

    private void appendLog(String message) {
        logArea.append("[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void appendInjectionLog(String line) {
        logArea.append(line + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static DiscoveryResult findMinecraftProcesses() {
        Map<Long, String> attachable = new HashMap<>();
        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            try {
                attachable.put(Long.parseLong(descriptor.id()), descriptor.displayName());
            } catch (NumberFormatException ignored) { }
        }

        List<MinecraftProcess> matches = new ArrayList<>();
        Map<Long, MinecraftProcess> javaProcesses = new HashMap<>();
        long ownPid = ProcessHandle.current().pid();
        ProcessHandle.allProcesses().forEach(handle -> {
            if (handle.pid() == ownPid) return;
            ProcessHandle.Info info = handle.info();
            String command = info.command().orElse("");
            String commandLine = info.commandLine().orElseGet(() ->
                    command + " " + String.join(" ", info.arguments().orElse(new String[0])));
            String lower = commandLine.toLowerCase();
            String executable = new File(command).getName().toLowerCase();
            boolean isJava = executable.equals("java.exe") || executable.equals("javaw.exe")
                    || executable.equals("java") || executable.equals("javaw");
            if (!isJava && !attachable.containsKey(handle.pid())) return;

            String fallbackLabel = executable.isBlank() ? "Java process" : executable;
            javaProcesses.put(handle.pid(), new MinecraftProcess(handle.pid(), fallbackLabel + " (command line unavailable)"));

            if (lower.contains("org.prismlauncher.entrypoint") || lower.contains("net.minecraft.client.main.main")) {
                String label = attachable.get(handle.pid());
                if (label == null || label.isBlank()) label = shortCommand(commandLine);
                matches.add(new MinecraftProcess(handle.pid(), label));
            }
        });
        addTaskListProcesses(javaProcesses, ownPid, "java.exe");
        addTaskListProcesses(javaProcesses, ownPid, "javaw.exe");
        Comparator<MinecraftProcess> newestPidFirst = Comparator.comparingLong((MinecraftProcess p) -> p.pid).reversed();
        matches.sort(newestPidFirst);
        List<MinecraftProcess> fallbackProcesses = new ArrayList<>(javaProcesses.values());
        fallbackProcesses.sort(newestPidFirst);
        return matches.isEmpty()
                ? new DiscoveryResult(fallbackProcesses, true)
                : new DiscoveryResult(matches, false);
    }

    private static void addTaskListProcesses(Map<Long, MinecraftProcess> processes, long ownPid, String imageName) {
        try {
            Process tasklist = new ProcessBuilder("tasklist.exe", "/FI", "IMAGENAME eq " + imageName, "/FO", "CSV", "/NH")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(tasklist.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = TASKLIST_CSV.matcher(line.trim());
                    if (!matcher.find()) continue;
                    long pid = Long.parseLong(matcher.group(2));
                    if (pid != ownPid)
                        processes.putIfAbsent(pid, new MinecraftProcess(pid, matcher.group(1) + " (Windows process list)"));
                }
            }
            tasklist.waitFor();
        } catch (Exception ignored) {
            // ProcessHandle and the Attach API remain available on systems where tasklist is restricted.
        }
    }

    private static String shortCommand(String commandLine) {
        if (commandLine.length() <= 180) return commandLine;
        return commandLine.substring(0, 177) + "...";
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String defaultAgentPath() {
        try {
            Path classes = Path.of(InjectorUi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path buildDir = classes.getParent();
            if (buildDir != null && buildDir.getParent() != null)
                return buildDir.getParent().resolve("build/injector/liquidbounce-injector-agent.jar").toString();
        } catch (URISyntaxException | RuntimeException ignored) { }
        return "build/injector/liquidbounce-injector-agent.jar";
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--list")) {
            DiscoveryResult discovery = findMinecraftProcesses();
            System.out.println("fallback=" + discovery.fallback);
            for (MinecraftProcess process : discovery.processes)
                System.out.println(process.pid + "\t" + process.description);
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) { }
            String agent = args.length > 0 ? args[0] : defaultAgentPath();
            new InjectorUi(agent).setVisible(true);
        });
    }

    private record MinecraftProcess(long pid, String description) { }
    private record DiscoveryResult(List<MinecraftProcess> processes, boolean fallback) { }
    private record LogUpdate(String message, int progress) { }

    private static final class ProcessTableModel extends AbstractTableModel {
        private final String[] columns = {"PID", "JVM / main class"};
        private List<MinecraftProcess> rows = List.of();

        void setRows(List<MinecraftProcess> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

        MinecraftProcess row(int index) { return rows.get(index); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) {
            MinecraftProcess process = rows.get(row);
            return column == 0 ? process.pid : process.description;
        }
    }
}
