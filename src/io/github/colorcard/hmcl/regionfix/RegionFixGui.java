/*
 * HMCL Region Fix
 * Copyright (C) 2026 ColorCard and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.colorcard.hmcl.regionfix;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HMCL 地区修复
 *
 * 一个给新手用的图形界面：
 *   - 自动在常见目录里找 HMCL
 *   - 找不到就让用户拖进来 / 手动选择
 *   - 自动选择合适的 Java 17+
 *   - 用 -javaagent 启动 HMCL，内存修复地区误判
 *
 * 本 jar 同时也是 agent（Premain-Class: RegionFixAgent）。
 */
public final class RegionFixGui {

    private static final String TITLE = "HMCL 地区修复";
    private static final String DOWNLOAD_URL = "https://hmcl.huangyuhui.net/download";
    private static final String JAVA_URL = "https://adoptium.net/temurin/releases/?version=21";
    private static final String PREFS_LAST = "lastTarget";
    private static final int MIN_JAVA = 17;

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(?i)HMCL[-_ ]?([0-9]+(?:\\.[0-9A-Za-z]+)+)");

    private JFrame frame;
    private final JLabel titleLabel = new JLabel(TITLE);
    private final JLabel subtitleLabel = new JLabel("适用于台湾、香港、澳门及亚太地区被误判为中国大陆的用户");
    private final JLabel statusLabel = new JLabel();
    private final JTextField pathField = new JTextField();
    private final JLabel multiLabel = new JLabel("找到多个 HMCL，请选择：");
    private final JComboBox<Candidate> candidateBox = new JComboBox<Candidate>();
    private final JLabel hintLabel = new JLabel();
    private final JProgressBar progress = new JProgressBar();
    private final JButton launchButton = new JButton("启动 HMCL");
    private final JButton chooseButton = new JButton("选择其他 HMCL 文件…");
    private final JButton downloadButton = new JButton("打开 HMCL 下载页");
    private final JButton shortcutButton = new JButton("创建桌面快捷方式");
    private final JButton refreshButton = new JButton("重新查找");

    private Preferences prefs;
    private final List<Candidate> candidates = new ArrayList<Candidate>();
    private Path selfJar;
    private static Path startupTarget;
    private boolean busy;

    // ------------------------------------------------------------ entry

    public static void main(String[] args) {
        if (args.length > 0 && "--launch".equals(args[0]) && args.length >= 2) {
            RegionFixGui app = new RegionFixGui();
            System.exit(app.cliLaunch(Paths.get(args[1])));
            return;
        }
        if (args.length > 0 && "--scan".equals(args[0])) {
            RegionFixGui app = new RegionFixGui();
            List<Candidate> list = app.scanCandidates();
            for (Candidate c : list) {
                System.out.println(c.path);
            }
            System.exit(0);
            return;
        }
        if (args.length > 0 && !args[0].startsWith("--")) {
            try {
                Path p = Paths.get(args[0]);
                if (Files.isRegularFile(p)) {
                    startupTarget = p;
                }
            } catch (Throwable ignored) {
            }
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("HMCL 地区修复需要在桌面环境中运行。");
            System.out.println("命令行用法：java -javaagent:HMCL-Region-Fix.jar -jar HMCL.jar");
            return;
        }
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Throwable ignored) {
                }
                new RegionFixGui().createAndShow();
            }
        });
    }

    private RegionFixGui() {
        selfJar = initSelfJar();
        try {
            prefs = Preferences.userNodeForPackage(RegionFixGui.class);
        } catch (Throwable t) {
            prefs = null;
        }
    }

    // -------------------------------------------------------------- UI

    private void createAndShow() {
        frame = new JFrame(TITLE);

        if (!prefGetBoolean("regionConfirmed")) {
            int r = JOptionPane.showConfirmDialog(null,
                    "本工具仅用于修复 HMCL 对台湾、香港、澳门及亚太地区用户的地区误判。\n"
                            + "如果你在中国大陆，请勿使用本工具，并遵守当地法律法规。\n\n"
                            + "确认你的设备/网络不在中国大陆，继续吗？",
                    "使用范围确认",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                System.exit(0);
                return;
            }
            prefPutBoolean("regionConfirmed", true);
        }

        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(620, 420));

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(20, 24, 14, 24));

        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 24f));
        subtitleLabel.setForeground(new Color(0x666666));
        subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(13f));
        header.add(titleLabel);
        header.add(Box.createVerticalStrut(4));
        header.add(subtitleLabel);

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xDADADA)),
                new EmptyBorder(16, 16, 16, 16)));

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 18f));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(statusLabel);
        card.add(Box.createVerticalStrut(8));

        pathField.setEditable(false);
        pathField.setBorder(null);
        pathField.setOpaque(false);
        pathField.setFont(pathField.getFont().deriveFont(12f));
        pathField.setForeground(new Color(0x666666));
        pathField.setAlignmentX(Component.LEFT_ALIGNMENT);
        pathField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        card.add(pathField);

        multiLabel.setFont(multiLabel.getFont().deriveFont(12f));
        multiLabel.setForeground(new Color(0x666666));
        multiLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        multiLabel.setVisible(false);
        card.add(Box.createVerticalStrut(12));
        card.add(multiLabel);

        candidateBox.setAlignmentX(Component.LEFT_ALIGNMENT);
        candidateBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        candidateBox.setVisible(false);
        candidateBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof Candidate) {
                    setText(((Candidate) value).display);
                }
                return this;
            }
        });
        candidateBox.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                updateSelected();
            }
        });
        card.add(candidateBox);

        hintLabel.setFont(hintLabel.getFont().deriveFont(12f));
        hintLabel.setForeground(new Color(0x666666));
        hintLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(Box.createVerticalStrut(12));
        card.add(hintLabel);

        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(Box.createVerticalStrut(10));
        card.add(progress);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        launchButton.setFont(launchButton.getFont().deriveFont(Font.BOLD, 16f));
        launchButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        launchButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        launchButton.setPreferredSize(new Dimension(0, 48));
        launchButton.setEnabled(false);
        launchButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                launch();
            }
        });
        bottom.add(launchButton);
        bottom.add(Box.createVerticalStrut(10));

        JPanel secondary = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        secondary.setAlignmentX(Component.CENTER_ALIGNMENT);
        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!busy) {
                    scanAsync();
                }
            }
        });
        chooseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                chooseFile();
            }
        });
        downloadButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openUrl(DOWNLOAD_URL);
            }
        });
        shortcutButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                createDesktopShortcut();
            }
        });
        secondary.add(refreshButton);
        secondary.add(chooseButton);
        secondary.add(downloadButton);
        secondary.add(shortcutButton);
        bottom.add(secondary);
        bottom.add(Box.createVerticalStrut(10));

        JLabel footer = new JLabel("本工具不会修改 HMCL 文件；仅用于修复地区误判。");
        footer.setFont(footer.getFont().deriveFont(11f));
        footer.setForeground(new Color(0x888888));
        footer.setAlignmentX(Component.CENTER_ALIGNMENT);
        bottom.add(footer);

        root.add(header, BorderLayout.NORTH);
        root.add(card, BorderLayout.CENTER);
        root.add(bottom, BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.pack();
        Dimension packed = frame.getSize();
        frame.setSize(Math.max(660, packed.width), Math.max(430, packed.height));
        frame.setLocationRelativeTo(null);
        installDropTarget(root);

        updateNoSelectionState("正在查找 HMCL…");
        frame.setVisible(true);
        if (startupTarget != null) {
            addCandidate(startupTarget, true);
        }
        scanAsync();
    }

    // ---------------------------------------------------------- scanning

    private void scanAsync() {
        progress.setVisible(true);
        statusLabel.setText("正在查找 HMCL…");
        pathField.setText("正在扫描常见下载目录…");

        new SwingWorker<List<Candidate>, Void>() {
            @Override
            protected List<Candidate> doInBackground() {
                return scanCandidates();
            }

            @Override
            protected void done() {
                List<Candidate> list;
                try {
                    list = get();
                } catch (Throwable t) {
                    list = new ArrayList<Candidate>();
                }

                String last = prefGet(PREFS_LAST);
                Candidate previous = selectedCandidate();
                Path previousPath = previous != null ? previous.path
                        : (last != null ? Paths.get(last) : null);

                candidates.clear();
                candidates.addAll(list);
                if (previousPath != null && Files.isRegularFile(previousPath)
                        && !containsPath(previousPath)) {
                    candidates.add(0, new Candidate(previousPath));
                }

                rebuildCandidateBox();
                progress.setVisible(false);

                if (candidates.isEmpty()) {
                    updateNoSelectionState("还没有找到 HMCL");
                    pathField.setText("请把 HMCL 文件拖到这个窗口，或点击下面的“选择其他 HMCL 文件…”。");
                    hintLabel.setText("<html>1. 下载 HMCL（点击“打开 HMCL 下载页”）<br>"
                            + "2. 下载完点击“重新查找”，或把 HMCL 文件拖到这个窗口<br>"
                            + "3. 点击“启动 HMCL”</html>");
                    return;
                }

                Candidate select = null;
                if (previousPath != null) {
                    for (Candidate c : candidates) {
                        if (c.path.equals(previousPath)) {
                            select = c;
                            break;
                        }
                    }
                }
                if (select == null) {
                    select = candidates.get(0);
                }
                candidateBox.setSelectedItem(select);
                updateSelected();
            }
        }.execute();
    }

    private List<Candidate> scanCandidates() {
        LinkedHashMap<String, Path> roots = new LinkedHashMap<String, Path>();

        Path selfDir = selfJar != null ? selfJar.getParent() : Paths.get(System.getProperty("user.dir"));
        addRoot(roots, selfDir);
        if (selfDir != null && selfDir.getParent() != null) {
            String userHome = System.getProperty("user.home");
            try {
                Path homePath = userHome != null ? Paths.get(userHome).toAbsolutePath().normalize() : null;
                Path parentPath = selfDir.getParent().toAbsolutePath().normalize();
                if (homePath != null && !parentPath.equals(homePath) && parentPath.startsWith(homePath)) {
                    addRoot(roots, parentPath);
                }
            } catch (Throwable ignored) {
            }
        }

        String home = System.getProperty("user.home");
        if (home != null) {
            addRoot(roots, Paths.get(home, "Downloads"));
            addRoot(roots, Paths.get(home, "Desktop"));
            addRoot(roots, Paths.get(home, "Documents"));
        }

        String last = prefGet(PREFS_LAST);
        if (last != null) {
            Path p = Paths.get(last);
            if (Files.isRegularFile(p) && p.getParent() != null) {
                addRoot(roots, p.getParent());
            }
        }

        final List<Path> found = new ArrayList<Path>();
        for (Path root : roots.values()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 2,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                                if (!dir.equals(root)
                                        && (name.startsWith(".")
                                        || name.equalsIgnoreCase("node_modules")
                                        || name.equalsIgnoreCase("Library")
                                        || name.equalsIgnoreCase("AppData")
                                        || name.equalsIgnoreCase(".git"))) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                                if (isHmclPackage(file)) {
                                    found.add(file);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (Throwable ignored) {
            }
        }

        ArrayList<Candidate> result = new ArrayList<Candidate>();
        Set<String> seen = new HashSet<String>();
        for (Path p : found) {
            String key;
            try {
                key = p.toRealPath().toString();
            } catch (Throwable t) {
                key = p.toAbsolutePath().normalize().toString();
            }
            if (seen.add(key)) {
                result.add(new Candidate(p));
            }
        }

        Collections.sort(result, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                long ma = lastModified(a.path);
                long mb = lastModified(b.path);
                if (ma != mb) {
                    return ma > mb ? -1 : 1;
                }
                int pa = platformScore(a.path);
                int pb = platformScore(b.path);
                if (pa != pb) {
                    return pa < pb ? -1 : 1;
                }
                return a.display.compareToIgnoreCase(b.display);
            }
        });
        return result;
    }

    private static boolean containsRegionUtils(Path file) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            return zip.getEntry("org/jackhuang/hmcl/util/RegionUtils.class") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void addRoot(Map<String, Path> roots, Path path) {
        if (path == null) {
            return;
        }
        try {
            Path p = path.toAbsolutePath().normalize();
            roots.put(p.toString(), p);
        } catch (Throwable ignored) {
        }
    }

    private boolean isHmclPackage(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("hmcl")) {
            return false;
        }
        if (!(lower.endsWith(".jar") || lower.endsWith(".sh") || lower.endsWith(".exe"))) {
            return false;
        }
        if (lower.contains("agent")
                || lower.contains("unlock")
                || lower.contains("region-fix")
                || lower.contains("regionfix")
                || lower.contains("sources")
                || lower.contains("javadoc")
                || lower.contains(".hmcl-orig")) {
            return false;
        }
        if (selfJar != null) {
            try {
                if (Files.isSameFile(file, selfJar)) {
                    return false;
                }
            } catch (Throwable ignored) {
            }
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            return zip.getEntry("META-INF/MANIFEST.MF") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int platformScore(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean windows = isWindows();
        if (windows) {
            if (name.endsWith(".exe")) return 0;
            if (name.endsWith(".jar")) return 1;
            if (name.endsWith(".sh")) return 2;
        } else {
            if (name.endsWith(".sh")) return 0;
            if (name.endsWith(".jar")) return 1;
            if (name.endsWith(".exe")) return 2;
        }
        return 3;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ----------------------------------------------------------- UI state

    private void rebuildCandidateBox() {
        candidateBox.removeAllItems();
        for (Candidate c : candidates) {
            candidateBox.addItem(c);
        }
        boolean multiple = candidates.size() > 1;
        multiLabel.setVisible(multiple);
        candidateBox.setVisible(multiple);
        hintLabel.setVisible(true);
    }

    private Candidate selectedCandidate() {
        if (candidates.isEmpty()) {
            return null;
        }
        Object item = candidateBox.getSelectedItem();
        if (item instanceof Candidate) {
            return (Candidate) item;
        }
        return candidates.get(0);
    }

    private boolean containsPath(Path p) {
        for (Candidate c : candidates) {
            if (c.path.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private void updateSelected() {
        Candidate c = selectedCandidate();
        if (c == null) {
            updateNoSelectionState("还没有找到 HMCL");
            return;
        }

        statusLabel.setText("已找到 HMCL" + (c.version != null ? " " + c.version : ""));
        pathField.setText(c.path.toString());
        pathField.setToolTipText(c.path.toString());
        launchButton.setEnabled(!busy);
        hintLabel.setText("如果这不是你想要的版本，可以在下面选择其他文件，或点击“选择其他 HMCL 文件…”。");
        prefPut(PREFS_LAST, c.path.toString());
    }

    private void updateNoSelectionState(String status) {
        statusLabel.setText(status);
        launchButton.setEnabled(false);
        multiLabel.setVisible(false);
        candidateBox.setVisible(false);
    }

    private void setBusy(String status) {
        busy = true;
        statusLabel.setText(status);
        progress.setVisible(true);
        launchButton.setEnabled(false);
        refreshButton.setEnabled(false);
        chooseButton.setEnabled(false);
        downloadButton.setEnabled(false);
        shortcutButton.setEnabled(false);
        candidateBox.setEnabled(false);
    }

    private void clearBusy() {
        busy = false;
        progress.setVisible(false);
        refreshButton.setEnabled(true);
        chooseButton.setEnabled(true);
        downloadButton.setEnabled(true);
        shortcutButton.setEnabled(true);
        candidateBox.setEnabled(true);
        launchButton.setEnabled(selectedCandidate() != null);
    }

    // ---------------------------------------------------------- choosing

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 HMCL 文件");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileFilter(new FileNameExtensionFilter("HMCL 文件 (*.jar, *.sh, *.exe)", "jar", "sh", "exe"));

        Candidate current = selectedCandidate();
        String last = prefGet(PREFS_LAST);
        Path start = current != null ? current.path.getParent()
                : (last != null ? Paths.get(last).getParent() : null);
        if (start == null || !Files.isDirectory(start)) {
            String home = System.getProperty("user.home");
            start = home != null ? Paths.get(home, "Downloads") : null;
        }
        if (start != null && Files.isDirectory(start)) {
            chooser.setCurrentDirectory(start.toFile());
        }

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            addCandidate(chooser.getSelectedFile().toPath(), true);
        }
    }

    private void addCandidate(Path path, boolean select) {
        if (path == null || !Files.isRegularFile(path)) {
            JOptionPane.showMessageDialog(frame, "这个文件不存在。", "无法使用", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (selfJar != null) {
            try {
                if (Files.isSameFile(path, selfJar)) {
                    JOptionPane.showMessageDialog(frame, "这是本工具自己，不是 HMCL。", "选错了", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar") && !lower.endsWith(".sh") && !lower.endsWith(".exe")) {
            int r = JOptionPane.showConfirmDialog(frame,
                    "这个文件不是 .jar / .sh / .exe：\n" + name + "\n\n仍要尝试使用吗？",
                    "确认文件", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                return;
            }
        } else if (!lower.startsWith("hmcl")) {
            int r = JOptionPane.showConfirmDialog(frame,
                    "这个文件名不是以 HMCL 开头：\n" + name + "\n\n仍要尝试使用吗？",
                    "确认文件", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                return;
            }
        }

        Candidate candidate = new Candidate(path);
        int existing = -1;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).path.equals(candidate.path)) {
                existing = i;
                break;
            }
        }
        if (existing >= 0) {
            candidate = candidates.get(existing);
        } else {
            candidates.add(0, candidate);
            rebuildCandidateBox();
        }
        if (select) {
            candidateBox.setSelectedItem(candidate);
            updateSelected();
        }
        launchButton.setEnabled(!busy);
        prefPut(PREFS_LAST, candidate.path.toString());
    }

    private void installDropTarget(Component component) {
        TransferHandler handler = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                try {
                    Transferable t = support.getTransferable();
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                    if (files != null && !files.isEmpty()) {
                        addCandidate(files.get(0).toPath(), true);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }
        };
        installDropTarget(component, handler);
    }

    private void installDropTarget(Component component, TransferHandler handler) {
        if (component instanceof JComponent) {
            ((JComponent) component).setTransferHandler(handler);
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                installDropTarget(child, handler);
            }
        }
    }

    // ---------------------------------------------------------- launching

    private void launch() {
        final Candidate candidate = selectedCandidate();
        if (candidate == null) {
            return;
        }
        if (selfJar == null) {
            JOptionPane.showMessageDialog(frame,
                    "请通过 HMCL-Region-Fix.jar 运行本工具，不要直接从开发目录运行。",
                    "无法启动", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!containsRegionUtils(candidate.path)) {
            int r = JOptionPane.showConfirmDialog(frame,
                    "这个文件里没有找到 HMCL 的地区限制代码（RegionUtils）。\n"
                            + "可能不是 HMCL，或者是仍使用旧判断逻辑的版本。\n\n仍要尝试启动吗？",
                    "确认文件", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) {
                return;
            }
        }

        setBusy("正在检查 Java…");

        new SwingWorker<JavaChoice, Void>() {
            @Override
            protected JavaChoice doInBackground() {
                return findJava(candidate.path);
            }

            @Override
            protected void done() {
                JavaChoice java;
                try {
                    java = get();
                } catch (Throwable t) {
                    java = null;
                }

                if (java == null) {
                    clearBusy();
                    showJavaMissing();
                    return;
                }

                if (java.major < MIN_JAVA) {
                    Object[] options = {"打开 Java 下载页", "仍要尝试", "取消"};
                    int r = JOptionPane.showOptionDialog(frame,
                            "当前找到的 Java 版本是 " + java.major + "，\n"
                                    + "HMCL 需要 Java " + MIN_JAVA + " 或更高版本。\n\n"
                                    + "建议安装 Java 21 后再试。",
                            "Java 版本太旧",
                            JOptionPane.YES_NO_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE,
                            null, options, options[0]);
                    if (r == 0) {
                        clearBusy();
                        openUrl(JAVA_URL);
                        return;
                    }
                    if (r != 1) {
                        clearBusy();
                        return;
                    }
                }

                startLaunch(candidate, java);
            }
        }.execute();
    }

    private void startLaunch(final Candidate candidate, final JavaChoice java) {
        setBusy("正在启动 HMCL…");

        new SwingWorker<LaunchResult, Void>() {
            @Override
            protected LaunchResult doInBackground() {
                return doLaunch(candidate, java);
            }

            @Override
            protected void done() {
                LaunchResult result;
                try {
                    result = get();
                } catch (Throwable t) {
                    result = LaunchResult.fail("启动时发生异常：" + t.getMessage(), null);
                }

                if (result.ok) {
                    progress.setVisible(false);
                    statusLabel.setText("HMCL 已启动");
                    pathField.setText(candidate.path.toString());
                    hintLabel.setText("这个窗口可以关闭了。");
                    launchButton.setEnabled(false);
                    refreshButton.setEnabled(false);
                    chooseButton.setEnabled(false);
                    downloadButton.setEnabled(false);
                    shortcutButton.setEnabled(false);
                    new javax.swing.Timer(1400, new ActionListener() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            frame.dispose();
                        }
                    }).start();
                } else {
                    clearBusy();
                    showLaunchError(result);
                }
            }
        }.execute();
    }

    private LaunchResult doLaunch(Candidate candidate, JavaChoice java) {
        Path log = logFile();
        List<String> command = new ArrayList<String>();
        command.add(java.launchBin.toString());

        String opts = System.getenv("HMCL_JAVA_OPTS");
        if (opts != null && !opts.trim().isEmpty()) {
            for (String s : opts.trim().split("\\s+")) {
                if (!s.isEmpty()) {
                    command.add(s);
                }
            }
        } else {
            command.add("-XX:MinHeapFreeRatio=5");
            command.add("-XX:MaxHeapFreeRatio=15");
        }

        command.add("-javaagent:" + selfJar);
        command.add("-jar");
        command.add(candidate.path.toString());

        appendLog(log, "");
        appendLog(log, "===== " + new Date() + " =====");
        appendLog(log, "启动：" + join(command, " "));

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(candidate.path.getParent().toFile());
            pb.redirectErrorStream(true);
            try {
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            } catch (Throwable ignored) {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            Process process = pb.start();
            boolean exited = process.waitFor(2, TimeUnit.SECONDS);
            if (exited && process.exitValue() != 0) {
                String tail = tail(log, 60);
                return LaunchResult.fail(
                        "HMCL 启动失败（退出码 " + process.exitValue() + "）。\n\n"
                                + "常见原因：Java 版本不匹配、HMCL 文件损坏、或文件路径有问题。\n"
                                + "日志文件：" + log + tail,
                        log);
            }
            return LaunchResult.ok();
        } catch (Throwable t) {
            return LaunchResult.fail("无法启动 HMCL：" + t.getMessage() + "\n\n日志文件：" + log, log);
        }
    }

    private int cliLaunch(Path target) {
        if (selfJar == null) {
            System.err.println("请通过 HMCL-Region-Fix.jar 运行。");
            return 1;
        }
        if (!Files.isRegularFile(target)) {
            System.err.println("文件不存在：" + target);
            return 1;
        }
        JavaChoice java = findJava(target);
        if (java == null) {
            System.err.println("没有找到 Java。");
            return 1;
        }
        System.out.println("使用 Java " + java.major + "：" + java.versionBin);
        LaunchResult result = doLaunch(new Candidate(target), java);
        System.out.println(result.ok ? "启动成功" : result.message);
        return result.ok ? 0 : 1;
    }

    // ------------------------------------------------------------- Java

    private JavaChoice findJava(Path target) {
        List<Path> javaBins = new ArrayList<Path>();

        // HMCL 自带的 jre-*/bin/java
        Path targetDir = target.getParent();
        if (targetDir != null && Files.isDirectory(targetDir)) {
            try {
                DirectoryStream<Path> stream = Files.newDirectoryStream(targetDir, "jre*");
                try {
                    for (Path dir : stream) {
                        if (!Files.isDirectory(dir)) {
                            continue;
                        }
                        addJavaBin(javaBins, dir.resolve("bin").resolve(isWindows() ? "java.exe" : "java"));
                    }
                } finally {
                    stream.close();
                }
            } catch (Throwable ignored) {
            }
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.trim().isEmpty()) {
            addJavaBin(javaBins, Paths.get(javaHome, "bin", isWindows() ? "java.exe" : "java"));
        }

        String currentHome = System.getProperty("java.home");
        if (currentHome != null) {
            addJavaBin(javaBins, Paths.get(currentHome, "bin", isWindows() ? "java.exe" : "java"));
        }

        addCommonJavaLocations(javaBins);

        Path pathJava = findOnPath(isWindows() ? "java.exe" : "java");
        if (pathJava != null) {
            addJavaBin(javaBins, pathJava);
        }

        JavaChoice bestOld = null;
        Set<String> seen = new HashSet<String>();
        for (Path bin : javaBins) {
            String key;
            try {
                key = bin.toRealPath().toString();
            } catch (Throwable t) {
                key = bin.toAbsolutePath().normalize().toString();
            }
            if (!seen.add(key)) {
                continue;
            }

            Path launchBin = bin;
            if (isWindows()) {
                Path javaw = bin.resolveSibling("javaw.exe");
                if (Files.isRegularFile(javaw)) {
                    launchBin = javaw;
                }
            }

            int major = javaMajor(bin);
            if (major <= 0) {
                continue;
            }
            JavaChoice choice = new JavaChoice(bin, launchBin, major);
            if (major >= MIN_JAVA) {
                return choice;
            }
            if (bestOld == null || major > bestOld.major) {
                bestOld = choice;
            }
        }
        return bestOld;
    }

    private static void addJavaBin(List<Path> list, Path bin) {
        if (bin != null && Files.isRegularFile(bin)) {
            list.add(bin);
        }
    }

    private static void addCommonJavaLocations(List<Path> javaBins) {
        if (isWindows()) {
            addGlob(javaBins, Paths.get("C:\\Program Files\\Java"), "*/bin/java.exe");
            addGlob(javaBins, Paths.get("C:\\Program Files\\Eclipse Adoptium"), "*/bin/java.exe");
            addGlob(javaBins, Paths.get("C:\\Program Files\\Microsoft"), "*/bin/java.exe");
            String local = System.getenv("LOCALAPPDATA");
            if (local != null && !local.trim().isEmpty()) {
                addGlob(javaBins, Paths.get(local, "Programs", "Eclipse Adoptium"), "*/bin/java.exe");
                addGlob(javaBins, Paths.get(local, "Programs", "Microsoft"), "*/bin/java.exe");
            }
        } else if (isMac()) {
            addGlob(javaBins, Paths.get("/Library/Java/JavaVirtualMachines"), "*/Contents/Home/bin/java");
            addGlob(javaBins, Paths.get("/System/Library/Java/JavaVirtualMachines"), "*/Contents/Home/bin/java");
        } else {
            addGlob(javaBins, Paths.get("/usr/lib/jvm"), "*/bin/java");
            addGlob(javaBins, Paths.get("/usr/java"), "*/bin/java");
        }
    }

    private static void addGlob(List<Path> list, Path root, String glob) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, glob)) {
            for (Path p : stream) {
                addJavaBin(list, p);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int javaMajor(Path javaBin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(javaBin.toString(), "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = readAll(p.getInputStream());
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroy();
                return -1;
            }
            Matcher m = Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?").matcher(output);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                if (major == 1 && m.group(2) != null) {
                    major = Integer.parseInt(m.group(2));
                }
                return major;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static Path findOnPath(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(Pattern.quote(File.pathSeparator))) {
            if (dir.isEmpty()) {
                continue;
            }
            Path p = Paths.get(dir, exe);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ errors

    private void showJavaMissing() {
        Object[] options = {"打开 Java 下载页", "关闭"};
        int r = JOptionPane.showOptionDialog(frame,
                "没有找到 Java " + MIN_JAVA + " 或更高版本。\n\n"
                        + "HMCL 需要 Java " + MIN_JAVA + "+ 才能运行。\n"
                        + "请安装 Java 21，然后再点击“启动 HMCL”。",
                "需要安装 Java",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
                null, options, options[0]);
        if (r == 0) {
            openUrl(JAVA_URL);
        }
    }

    private void showLaunchError(LaunchResult result) {
        JTextArea area = new JTextArea(result.message == null ? "启动失败。" : result.message);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setRows(14);
        area.setColumns(64);
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(620, 300));
        JOptionPane.showMessageDialog(frame, scroll, "启动失败", JOptionPane.ERROR_MESSAGE);
    }

    // --------------------------------------------------------- shortcuts

    private void createDesktopShortcut() {
        if (selfJar == null) {
            JOptionPane.showMessageDialog(frame, "请通过 HMCL-Region-Fix.jar 运行本工具。", "无法创建", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String home = System.getProperty("user.home");
        if (home == null) {
            JOptionPane.showMessageDialog(frame, "找不到用户目录。", "无法创建", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path desktop = Paths.get(home, "Desktop");
        if (!Files.isDirectory(desktop)) {
            JOptionPane.showMessageDialog(frame, "找不到桌面目录：\n" + desktop, "无法创建", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            Path created;
            if (isWindows()) {
                created = desktop.resolve("HMCL 地区修复.bat");
                String javaExe = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
                if (!Files.isRegularFile(Paths.get(javaExe))) {
                    javaExe = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
                }
                String content = "@echo off\r\n"
                        + "chcp 65001 >nul\r\n"
                        + "start \"\" \"" + javaExe + "\" -jar \"" + selfJar + "\"\r\n";
                Files.write(created, content.getBytes(StandardCharsets.UTF_8));
            } else if (isMac()) {
                Path link = desktop.resolve("HMCL 地区修复.jar");
                if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(link)) {
                        Files.delete(link);
                    } else {
                        JOptionPane.showMessageDialog(frame,
                                "桌面已经有同名文件：\n" + link + "\n\n请先改名或删除它。",
                                "无法创建", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                }
                try {
                    Files.createSymbolicLink(link, selfJar);
                    created = link;
                } catch (Throwable t) {
                    created = desktop.resolve("HMCL 地区修复.command");
                    String content = "#!/bin/sh\nexec \"" + javaBinForShortcut() + "\" -jar \"" + selfJar + "\"\n";
                    Files.write(created, content.getBytes(StandardCharsets.UTF_8));
                    Files.setPosixFilePermissions(created, PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            } else {
                created = desktop.resolve("HMCL 地区修复.desktop");
                String content = "[Desktop Entry]\n"
                        + "Type=Application\n"
                        + "Name=HMCL 地区修复\n"
                        + "Comment=启动 HMCL（修复地区误判）\n"
                        + "Exec=\"" + javaBinForShortcut() + "\" -jar \"" + selfJar + "\"\n"
                        + "Terminal=false\n";
                Files.write(created, content.getBytes(StandardCharsets.UTF_8));
                Files.setPosixFilePermissions(created, PosixFilePermissions.fromString("rwxr-xr-x"));
            }

            int r = JOptionPane.showConfirmDialog(frame,
                    "已创建桌面快捷方式：\n" + created + "\n\n要打开桌面看看吗？",
                    "创建成功", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
            if (r == JOptionPane.YES_OPTION) {
                try {
                    Desktop.getDesktop().open(desktop.toFile());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(frame,
                    "创建快捷方式失败：\n" + t.getMessage()
                            + "\n\n可以手动把 HMCL-Region-Fix.jar 拖到桌面。",
                    "创建失败", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String javaBinForShortcut() {
        String home = System.getProperty("java.home");
        Path bin = Paths.get(home, "bin", isWindows() ? "java.exe" : "java");
        return bin.toString();
    }

    // ------------------------------------------------------------ utils

    private boolean prefGetBoolean(String key) {
        try {
            return prefs != null && prefs.getBoolean(key, false);
        } catch (Throwable t) {
            return false;
        }
    }

    private void prefPutBoolean(String key, boolean value) {
        try {
            if (prefs != null) {
                prefs.putBoolean(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private String prefGet(String key) {
        try {
            return prefs != null ? prefs.get(key, null) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private void prefPut(String key, String value) {
        try {
            if (prefs != null) {
                prefs.put(key, value);
            }
        } catch (Throwable ignored) {
        }
    }

    private Path initSelfJar() {
        try {
            URL url = RegionFixGui.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) {
                return null;
            }
            Path p = Paths.get(url.toURI());
            if (!Files.isRegularFile(p)) {
                return null;
            }
            try {
                return p.toRealPath();
            } catch (Throwable t) {
                return p.toAbsolutePath().normalize();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private Path logFile() {
        Path dir = selfJar != null && selfJar.getParent() != null
                ? selfJar.getParent()
                : Paths.get(System.getProperty("java.io.tmpdir"));
        try {
            if (Files.isDirectory(dir) && Files.isWritable(dir)) {
                return dir.resolve("hmcl-launch.log");
            }
        } catch (Throwable ignored) {
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "hmcl-launch.log");
    }

    private static void appendLog(Path log, String line) {
        try {
            Files.write(log, (line + System.getProperty("line.separator")).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private static String tail(Path log, int maxLines) {
        try {
            String text = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            String[] lines = text.split("\\R");
            int start = Math.max(0, lines.length - maxLines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private void openUrl(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Throwable t) {
            JOptionPane.showMessageDialog(frame,
                    "请手动在浏览器中打开：\n" + url,
                    "打开链接", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ------------------------------------------------------------ model

    static final class Candidate {
        final Path path;
        final String version;
        final String display;

        Candidate(Path path) {
            Path normalized;
            try {
                normalized = path.toAbsolutePath().normalize();
            } catch (Throwable t) {
                normalized = path;
            }
            this.path = normalized;
            this.version = parseVersion(this.path.getFileName().toString());
            this.display = (version != null ? "HMCL " + version : this.path.getFileName().toString())
                    + "    —    " + this.path;
        }

        private static String parseVersion(String name) {
            Matcher m = VERSION_PATTERN.matcher(name);
            return m.find() ? m.group(1) : null;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    static final class JavaChoice {
        final Path versionBin;
        final Path launchBin;
        final int major;

        JavaChoice(Path versionBin, Path launchBin, int major) {
            this.versionBin = versionBin;
            this.launchBin = launchBin;
            this.major = major;
        }
    }

    static final class LaunchResult {
        final boolean ok;
        final String message;
        final Path log;

        private LaunchResult(boolean ok, String message, Path log) {
            this.ok = ok;
            this.message = message;
            this.log = log;
        }

        static LaunchResult ok() {
            return new LaunchResult(true, null, null);
        }

        static LaunchResult fail(String message, Path log) {
            return new LaunchResult(false, message, log);
        }
    }
}
