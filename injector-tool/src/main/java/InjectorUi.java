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
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.platform.unix.X11;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;

/** Small Windows-friendly front end for Injector. */
public final class InjectorUi extends JFrame {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern TASKLIST_CSV = Pattern.compile("^\"([^\"]+)\",\"([0-9]+)\"");
    private static final char AGENT_ARG_SEP = (char) 1;   // must match lbrt.InjectionLogger.ARG_SEP

    private final ProcessTableModel processModel = new ProcessTableModel();
    private final JTable processTable = new JTable(processModel);
    private final JTextField agentField = new JTextField();
    private final JTextField dataDirField = new JTextField();
    private final JComboBox<String> dataModeCombo = new JComboBox<>(new String[]{"Client game directory", "Working directory", "Custom folder…"});
    private final JTextArea logArea = new JTextArea();
    private final JButton refreshButton = new JButton("Refresh");
    private final JButton injectButton = new JButton("Inject LiquidBounce");
    private final JButton uninjectButton = new JButton("Uninject");
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
        processTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        processTable.getColumnModel().getColumn(0).setMaxWidth(110);
        processTable.getColumnModel().getColumn(1).setPreferredWidth(240);
        processTable.getSelectionModel().addListSelectionListener(this::selectionChanged);

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.add(new JScrollPane(processTable), BorderLayout.CENTER);

        JPanel agentPanel = new JPanel(new BorderLayout(8, 0));
        JLabel agentLabel = new JLabel("Agent JAR:");
        agentPanel.add(agentLabel, BorderLayout.WEST);
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

        // Where LiquidBounce/ is created: the client's game directory (default), its working directory, or a custom folder.
        JPanel dataDirPanel = new JPanel(new BorderLayout(8, 0));
        JLabel dataLabel = new JLabel("Data folder:");
        dataDirPanel.add(dataLabel, BorderLayout.WEST);
        JButton dataBrowse = new JButton("Browse...");
        dataBrowse.addActionListener(event -> browseForDataDir());
        dataDirField.setToolTipText("Folder that will contain LiquidBounce/ (config, accounts, CEF cache).");
        dataModeCombo.setToolTipText("Where to create the LiquidBounce/ folder in the injected client.");
        dataModeCombo.addActionListener(event -> {
            boolean custom = dataModeCombo.getSelectedIndex() == 2;
            dataDirField.setEnabled(custom);
            dataBrowse.setEnabled(custom);
        });
        dataDirField.setEnabled(false); dataBrowse.setEnabled(false);   // default mode = game directory
        JPanel dataCenter = new JPanel(new BorderLayout(8, 0));
        dataCenter.add(dataModeCombo, BorderLayout.WEST);
        dataCenter.add(dataDirField, BorderLayout.CENTER);
        dataDirPanel.add(dataCenter, BorderLayout.CENTER);
        dataDirPanel.add(dataBrowse, BorderLayout.EAST);

        Dimension labelSize = new Dimension(84, dataLabel.getPreferredSize().height);
        agentLabel.setPreferredSize(labelSize);
        dataLabel.setPreferredSize(labelSize);

        JPanel form = new JPanel(new GridLayout(2, 1, 0, 6));
        form.add(agentPanel);
        form.add(dataDirPanel);
        center.add(form, BorderLayout.SOUTH);
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
        uninjectButton.addActionListener(event -> uninject());
        uninjectButton.setToolTipText("<html>Removes LiquidBounce from the selected client &mdash; shuts it down (browser, listeners) and reverts the game bytecode back to vanilla.<br><br>"
                + "<b>Note:</b> you cannot inject again into the same client afterwards &mdash; restart it first.<br>"
                + "Uninject also does <b>not</b> remove LiquidBounce from the process memory (its classes stay loaded until the client exits).</html>");
        injectButton.addActionListener(event -> inject());
        buttons.add(refreshButton);
        buttons.add(uninjectButton);
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
        boolean ready = processTable.getSelectedRow() >= 0 && new File(agentField.getText().trim()).isFile();
        injectButton.setEnabled(ready);
        uninjectButton.setEnabled(ready);
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

    private void browseForDataDir() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select the folder to hold LiquidBounce/");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = dataDirField.getText().trim();
        if (!current.isEmpty()) chooser.setSelectedFile(new File(current));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            dataDirField.setText(chooser.getSelectedFile().getAbsolutePath());
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

    private void inject() { attachAgent(false); }
    private void uninject() { attachAgent(true); }

    private void attachAgent(boolean uninject) {
        int selected = processTable.getSelectedRow();
        if (selected < 0) return;
        MinecraftProcess process = processModel.row(selected);
        File jar = new File(agentField.getText().trim());
        if (!jar.isFile()) {
            JOptionPane.showMessageDialog(this, "Agent JAR not found:\n" + jar.getAbsolutePath(),
                    "Missing agent", JOptionPane.ERROR_MESSAGE);
            return;
        }
        String verb = uninject ? "uninjection" : "injection";
        String Verb = uninject ? "Uninjection" : "Injection";

        appendLog("Starting " + verb + (uninject ? " from PID " : " into PID ") + process.pid + ".");
        tailingInjectionLog = false;
        File injectionLog;
        try {
            injectionLog = new File(System.getProperty("java.io.tmpdir"), "liquidbounce-" + verb + "-" + process.pid + ".log");
            Files.writeString(injectionLog.toPath(), "", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            startInjectionLogTail(injectionLog, uninject);
            appendLog("Dedicated " + verb + " log: " + injectionLog.getAbsolutePath());
        } catch (Exception logFailure) {
            injectionLog = null;
            appendLog("Could not create the dedicated " + verb + " log: " + logFailure.getMessage());
        }
        File finalInjectionLog = injectionLog;
        final int dataMode = dataModeCombo.getSelectedIndex();   // 0=game dir, 1=working dir, 2=custom
        final String dataDir = dataDirField.getText().trim();    // captured on the EDT
        setBusy(true, uninject ? "Attaching (uninject) ..." : "Attaching ...", 15);
        new SwingWorker<Void, LogUpdate>() {
            @Override protected Void doInBackground() throws Exception {
                StringBuilder args = new StringBuilder();
                if (uninject) args.append("mode=uninject");
                if (finalInjectionLog != null) {
                    if (args.length() > 0) args.append(AGENT_ARG_SEP);
                    args.append("logFile=").append(finalInjectionLog.getAbsolutePath());
                }
                if (!uninject) {   // 0 (game dir) -> no gameDir arg (agent resolves it); 1 -> "." (working dir); 2 -> custom path.
                    String gameDir = dataMode == 1 ? "." : (dataMode == 2 && !dataDir.isEmpty()) ? dataDir : null;
                    if (gameDir != null) {
                        if (args.length() > 0) args.append(AGENT_ARG_SEP);
                        args.append("gameDir=").append(gameDir);
                    }
                }
                String agentArgs = args.toString();
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
                        appendLog(uninject ? "Agent delivered; waiting for uninjection to finish."
                                : "Agent delivered; waiting for LiquidBounce initialization to finish.");
                        setProgressIfHigher(70, uninject ? "Uninjecting ..." : "Initializing LiquidBounce ...");
                    } else {
                        appendLog(Verb + " completed successfully (target log unavailable). ");
                        progressBar.setValue(100);
                        progressBar.setString(Verb + " successful");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    appendLog(Verb + " interrupted.");
                    progressBar.setString("Interrupted");
                } catch (ExecutionException e) {
                    String message = rootMessage(e);
                    appendLog(Verb.toUpperCase() + " FAILED: " + message);
                    progressBar.setString(Verb + " failed");
                    if (logTailWorker != null) logTailWorker.cancel(true);
                    tailingInjectionLog = false;
                    JOptionPane.showMessageDialog(InjectorUi.this,
                            Verb + " failed:\n" + message + "\n\nSee the log for details.",
                            "LiquidBounce Injector", JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (!tailingInjectionLog)
                        setBusy(false, progressBar.getString(), progressBar.getValue());
                }
            }
        }.execute();
    }

    private void startInjectionLogTail(File logFile, boolean uninject) {
        if (logTailWorker != null && !logTailWorker.isDone()) logTailWorker.cancel(true);
        tailingInjectionLog = true;
        long startOffset = logFile.length();
        String successMarker = uninject ? "UNINJECT complete" : "LiquidBounce initialization completed";
        logTailWorker = new SwingWorker<>() {
            private boolean sawSuccess;

            @Override protected Void doInBackground() throws Exception {
                long deadline = System.currentTimeMillis() + 5 * 60_000L;
                try (RandomAccessFile input = new RandomAccessFile(logFile, "r")) {
                    // Only show lines produced by this attach, not the entire Minecraft session.
                    input.seek(Math.min(startOffset, input.length()));
                    while (!isCancelled() && System.currentTimeMillis() < deadline) {
                        String encodedLine = input.readLine();
                        if (encodedLine == null) {
                            Thread.sleep(150);
                            continue;
                        }
                        String line = new String(encodedLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                        if (isInjectionLogLine(line)) publish(line);
                        if (line.contains(successMarker)) {
                            sawSuccess = true;
                            if (uninject) break;
                            deadline = Math.min(deadline, System.currentTimeMillis() + 15_000L);
                        }
                        if (uninject) {
                            if (line.contains("uninject failed") || line.contains("Game crashed")) break;
                        } else if (line.contains("[LB-INJECT]")
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
                    if (!uninject) applyInjectionProgress(line);
                }
            }

            @Override protected void done() {
                String label = uninject ? "uninjection" : "injection";
                if (!isCancelled() && sawSuccess) {
                    progressBar.setValue(100);
                    progressBar.setString(uninject ? "LiquidBounce uninjected" : "LiquidBounce injection complete");
                    appendLog("LiquidBounce " + label + " completed successfully.");
                } else if (!isCancelled() && progressBar.getValue() < 100) {
                    appendLog("Stopped waiting for the " + label + " completion marker.");
                    progressBar.setString(uninject ? "Uninjection log monitoring ended" : "Injection log monitoring ended");
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
        if (busy) { injectButton.setEnabled(false); uninjectButton.setEnabled(false); } else updateInjectEnabled();
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
        Map<Long, String> titles = windowTitles();
        Map<Long, String> attachable = Attacher.attachableJvms();   // empty on the jattach/JRE path; ProcessHandle still finds JVMs

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
            javaProcesses.put(handle.pid(), new MinecraftProcess(handle.pid(), fallbackLabel + " (command line unavailable)", titles.getOrDefault(handle.pid(), "")));

            if (lower.contains("org.prismlauncher.entrypoint") || lower.contains("net.minecraft.client.main.main")) {
                String label = attachable.get(handle.pid());
                if (label == null || label.isBlank()) label = shortCommand(commandLine);
                matches.add(new MinecraftProcess(handle.pid(), label, titles.getOrDefault(handle.pid(), "")));
            }
        });
        addTaskListProcesses(javaProcesses, ownPid, "java.exe", titles);
        addTaskListProcesses(javaProcesses, ownPid, "javaw.exe", titles);
        Comparator<MinecraftProcess> newestPidFirst = Comparator.comparingLong((MinecraftProcess p) -> p.pid).reversed();
        matches.sort(newestPidFirst);
        List<MinecraftProcess> fallbackProcesses = new ArrayList<>(javaProcesses.values());
        fallbackProcesses.sort(newestPidFirst);
        return matches.isEmpty()
                ? new DiscoveryResult(fallbackProcesses, true)
                : new DiscoveryResult(matches, false);
    }

    private static void addTaskListProcesses(Map<Long, MinecraftProcess> processes, long ownPid, String imageName, Map<Long, String> titles) {
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
                        processes.putIfAbsent(pid, new MinecraftProcess(pid, matcher.group(1) + " (Windows process list)", titles.getOrDefault(pid, "")));
                }
            }
            tasklist.waitFor();
        } catch (Exception ignored) {
            // ProcessHandle and the Attach API remain available on systems where tasklist is restricted.
        }
    }

    /** Best-effort OS window title per PID via JNA, so the picker can show e.g. "Minecraft 26.2" to tell apart multiple
     *  instances. Windows: User32 EnumWindows. Linux: X11 (walk the window tree, read _NET_WM_PID + _NET_WM_NAME) — no
     *  window manager or external tools needed. macOS: not supported. Any failure yields no title (column stays blank). */
    private static Map<Long, String> windowTitles() {
        Map<Long, String> titles = new HashMap<>();
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) windowTitlesWindows(titles);
            else if (!os.contains("mac") && !os.contains("darwin")) windowTitlesX11(titles);
        } catch (Throwable ignored) { }   // JNA/native unavailable -> blank column, never fatal
        return titles;
    }

    /** Windows: EnumWindows over visible top-level windows -> pid + title. */
    private static void windowTitlesWindows(Map<Long, String> titles) {
        User32 u = User32.INSTANCE;
        u.EnumWindows((HWND hWnd, Pointer data) -> {
            if (!u.IsWindowVisible(hWnd)) return true;
            IntByReference pidRef = new IntByReference();
            u.GetWindowThreadProcessId(hWnd, pidRef);
            long pid = pidRef.getValue() & 0xFFFFFFFFL;
            char[] buf = new char[512];
            int len = u.GetWindowText(hWnd, buf, buf.length);
            if (len > 0 && pid > 0) {
                String title = new String(buf, 0, len).trim();
                if (!title.isEmpty()) titles.putIfAbsent(pid, title);
            }
            return true;   // keep enumerating
        }, null);
    }

    /** Linux: recurse the X11 window tree from the root; each window's _NET_WM_PID + _NET_WM_NAME (fallback WM_NAME). */
    private static void windowTitlesX11(Map<Long, String> titles) {
        X11 x = X11.INSTANCE;
        X11.Display display = x.XOpenDisplay(null);
        if (display == null) return;
        try {
            X11.Atom pidAtom = x.XInternAtom(display, "_NET_WM_PID", false);
            X11.Atom netName = x.XInternAtom(display, "_NET_WM_NAME", false);
            X11.Atom utf8 = x.XInternAtom(display, "UTF8_STRING", false);
            walkX11(x, display, x.XDefaultRootWindow(display), pidAtom, netName, utf8, titles);
        } finally {
            x.XCloseDisplay(display);
        }
    }

    private static void walkX11(X11 x, X11.Display d, X11.Window w, X11.Atom pidAtom, X11.Atom netName, X11.Atom utf8,
                                Map<Long, String> titles) {
        long pid = readX11Cardinal(x, d, w, pidAtom);
        if (pid > 0) {
            String title = readX11Text(x, d, w, netName, utf8);
            if (title == null) title = readX11Text(x, d, w, X11.XA_WM_NAME, X11.XA_STRING);
            if (title != null && !title.isBlank()) titles.putIfAbsent(pid, title.trim());
        }
        X11.WindowByReference root = new X11.WindowByReference(), parent = new X11.WindowByReference();
        PointerByReference children = new PointerByReference();
        IntByReference n = new IntByReference();
        if (x.XQueryTree(d, w, root, parent, children, n) != 0) {
            Pointer p = children.getValue();
            if (p != null) {
                if (n.getValue() > 0)
                    for (long id : p.getLongArray(0, n.getValue())) walkX11(x, d, new X11.Window(id), pidAtom, netName, utf8, titles);
                x.XFree(p);
            }
        }
    }

    private static long readX11Cardinal(X11 x, X11.Display d, X11.Window w, X11.Atom prop) {
        X11.AtomByReference type = new X11.AtomByReference();
        IntByReference fmt = new IntByReference();
        NativeLongByReference nitems = new NativeLongByReference(), after = new NativeLongByReference();
        PointerByReference propRef = new PointerByReference();
        if (x.XGetWindowProperty(d, w, prop, new NativeLong(0), new NativeLong(1), false, X11.XA_CARDINAL,
                type, fmt, nitems, after, propRef) != 0) return -1;
        Pointer p = propRef.getValue();
        long v = -1;
        if (p != null) {
            if (nitems.getValue().longValue() >= 1 && fmt.getValue() == 32) v = p.getNativeLong(0).longValue();
            x.XFree(p);
        }
        return v;
    }

    private static String readX11Text(X11 x, X11.Display d, X11.Window w, X11.Atom prop, X11.Atom reqType) {
        X11.AtomByReference type = new X11.AtomByReference();
        IntByReference fmt = new IntByReference();
        NativeLongByReference nitems = new NativeLongByReference(), after = new NativeLongByReference();
        PointerByReference propRef = new PointerByReference();
        if (x.XGetWindowProperty(d, w, prop, new NativeLong(0), new NativeLong(1024), false, reqType,
                type, fmt, nitems, after, propRef) != 0) return null;
        Pointer p = propRef.getValue();
        String s = null;
        if (p != null) {
            int len = nitems.getValue().intValue();
            if (len > 0) s = new String(p.getByteArray(0, len), StandardCharsets.UTF_8);
            x.XFree(p);
        }
        return s;
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

    private static final String AGENT_JAR_NAME = "liquidbounce-injector-agent.jar";

    /** Default to the agent jar sitting next to the user (cwd first, then beside this tool jar); the usual layout is
     *  the two jars in the same folder. Falls back to the bare relative name if neither exists. */
    private static String defaultAgentPath() {
        Path cwd = Path.of("").toAbsolutePath().resolve(AGENT_JAR_NAME);
        if (Files.isRegularFile(cwd)) return cwd.toString();
        try {
            Path toolJar = Path.of(InjectorUi.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path beside = toolJar.getParent() != null ? toolJar.getParent().resolve(AGENT_JAR_NAME) : null;
            if (beside != null && Files.isRegularFile(beside)) return beside.toString();
        } catch (URISyntaxException | RuntimeException ignored) { }
        return AGENT_JAR_NAME;
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--list")) {
            DiscoveryResult discovery = findMinecraftProcesses();
            System.out.println("fallback=" + discovery.fallback);
            for (MinecraftProcess process : discovery.processes)
                System.out.println(process.pid + "\t" + process.windowTitle + "\t" + process.description);
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

    private record MinecraftProcess(long pid, String description, String windowTitle) { }
    private record DiscoveryResult(List<MinecraftProcess> processes, boolean fallback) { }
    private record LogUpdate(String message, int progress) { }

    private static final class ProcessTableModel extends AbstractTableModel {
        private final String[] columns = {"PID", "Window title", "JVM / main class"};
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
            return switch (column) {
                case 0 -> process.pid;
                case 1 -> process.windowTitle;
                default -> process.description;
            };
        }
    }
}
