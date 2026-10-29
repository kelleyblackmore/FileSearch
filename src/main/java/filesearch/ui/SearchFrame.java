package filesearch.ui;

import filesearch.FileSearcher;
import filesearch.SearchCriteria;
import filesearch.SearchResult;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;

/** Main application window: search form, results table and a preview of matching lines. */
public final class SearchFrame extends JFrame {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final JTextField directoryField = new JTextField(System.getProperty("user.home"), 30);
    private final JTextField nameField = new JTextField(20);
    private final JTextField contentField = new JTextField(20);
    private final JTextField extensionField = new JTextField(10);
    private final JCheckBox caseSensitiveBox = new JCheckBox("Case sensitive");
    private final JCheckBox regexBox = new JCheckBox("Regular expressions");
    private final JCheckBox hiddenBox = new JCheckBox("Include hidden files");
    private final JButton searchButton = new JButton("Search");
    private final JButton stopButton = new JButton("Stop");
    private final JLabel statusLabel = new JLabel("Ready");

    private final ResultTableModel model = new ResultTableModel();
    private final JTable table = new JTable(model);
    private final JTextArea preview = new JTextArea();

    private SearchWorker worker;

    public SearchFrame() {
        super("File Search");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(0, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        content.add(buildForm(), BorderLayout.NORTH);
        content.add(buildResults(), BorderLayout.CENTER);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        content.add(statusLabel, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(searchButton);
        getRootPane().registerKeyboardAction(e -> stopSearch(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setMinimumSize(new Dimension(640, 420));
        setSize(980, 680);
        setLocationRelativeTo(null);
    }

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;

        JButton browse = new JButton("Browse…");
        browse.addActionListener(e -> chooseDirectory());

        nameField.setToolTipText("Substring, glob such as *.java, or a regex when \"Regular expressions\" is on");
        contentField.setToolTipText("Only show files whose content contains this text");
        extensionField.setToolTipText("Comma-separated, e.g. java, txt, md");

        addRow(form, c, 0, "Look in:", directoryField, browse);
        addRow(form, c, 1, "File name:", nameField, null);
        addRow(form, c, 2, "Containing text:", contentField, null);
        addRow(form, c, 3, "Extensions:", extensionField, null);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        options.add(caseSensitiveBox);
        options.add(regexBox);
        options.add(hiddenBox);
        c.gridx = 1;
        c.gridy = 4;
        c.gridwidth = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(options, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        searchButton.addActionListener(e -> startSearch());
        stopButton.addActionListener(e -> stopSearch());
        stopButton.setEnabled(false);
        buttons.add(searchButton);
        buttons.add(stopButton);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        form.add(buttons, c);
        return form;
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label,
                               JComponent field, JComponent extra) {
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, c);
        if (extra != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            form.add(extra, c);
        }
    }

    private JSplitPane buildResults() {
        table.setAutoCreateRowSorter(false);
        table.setRowSorter(new TableRowSorter<>(model));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(900, 300));
        table.getColumnModel().getColumn(ResultTableModel.COL_NAME).setPreferredWidth(220);
        table.getColumnModel().getColumn(ResultTableModel.COL_FOLDER).setPreferredWidth(420);
        table.getColumnModel().getColumn(ResultTableModel.COL_SIZE).setPreferredWidth(80);
        table.getColumnModel().getColumn(ResultTableModel.COL_MODIFIED).setPreferredWidth(130);
        table.getColumnModel().getColumn(ResultTableModel.COL_HITS).setPreferredWidth(50);
        table.setDefaultRenderer(Long.class, new DefaultTableCellRenderer() {
            {
                setHorizontalAlignment(SwingConstants.RIGHT);
            }

            @Override
            protected void setValue(Object value) {
                setText(value == null ? "" : formatSize((Long) value));
            }
        });
        table.setDefaultRenderer(Instant.class, new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value == null ? "" : DATE_FORMAT.format((Instant) value));
            }
        });

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showPreview();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
                    SearchResult r = selectedResult();
                    if (r != null) {
                        open(r.path());
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowPopup(e);
            }
        });

        preview.setEditable(false);
        preview.setRows(10);
        preview.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(preview));
        split.setResizeWeight(0.7);
        return split;
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row < 0) {
            return;
        }
        table.setRowSelectionInterval(row, row);
        SearchResult r = selectedResult();
        if (r == null) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("Open");
        openItem.addActionListener(a -> open(r.path()));
        JMenuItem folderItem = new JMenuItem("Open containing folder");
        folderItem.addActionListener(a -> open(r.path().getParent()));
        JMenuItem copyItem = new JMenuItem("Copy path");
        copyItem.addActionListener(a -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(r.path().toString()), null));
        menu.add(openItem);
        menu.add(folderItem);
        menu.addSeparator();
        menu.add(copyItem);
        menu.show(table, e.getX(), e.getY());
    }

    private SearchResult selectedResult() {
        int viewRow = table.getSelectedRow();
        return viewRow < 0 ? null : model.get(table.convertRowIndexToModel(viewRow));
    }

    private void showPreview() {
        SearchResult r = selectedResult();
        if (r == null) {
            preview.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder(r.path().toString()).append('\n');
        if (r.matches().isEmpty()) {
            sb.append(formatSize(r.size())).append(", modified ")
                    .append(DATE_FORMAT.format(r.lastModified())).append('\n');
        } else {
            sb.append('\n');
            for (SearchResult.LineMatch m : r.matches()) {
                sb.append(String.format("%6d: %s%n", m.lineNumber(), m.line()));
            }
        }
        preview.setText(sb.toString());
        preview.setCaretPosition(0);
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose a folder to search");
        File current = new File(directoryField.getText().trim());
        if (current.isDirectory()) {
            chooser.setCurrentDirectory(current);
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            directoryField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startSearch() {
        if (worker != null && !worker.isDone()) {
            return;
        }
        String dirText = directoryField.getText().trim();
        if (dirText.isEmpty()) {
            error("Please choose a folder to search.");
            return;
        }
        Path dir;
        try {
            dir = Path.of(dirText);
        } catch (InvalidPathException e) {
            error("Invalid folder name:\n" + e.getMessage());
            return;
        }
        if (!Files.isDirectory(dir)) {
            error("Folder not found:\n" + dir);
            return;
        }

        SearchCriteria criteria;
        try {
            criteria = SearchCriteria.builder(dir)
                    .namePattern(nameField.getText())
                    .contentQuery(contentField.getText())
                    .extensions(extensionField.getText())
                    .caseSensitive(caseSensitiveBox.isSelected())
                    .regex(regexBox.isSelected())
                    .includeHidden(hiddenBox.isSelected())
                    .build();
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }

        model.clear();
        preview.setText("");
        setSearching(true);
        statusLabel.setText("Searching " + criteria.root() + " …");
        worker = new SearchWorker(criteria);
        worker.execute();
    }

    private void stopSearch() {
        if (worker != null && !worker.isDone()) {
            worker.requestStop();
            statusLabel.setText("Stopping…");
        }
    }

    private void setSearching(boolean searching) {
        searchButton.setEnabled(!searching);
        stopButton.setEnabled(searching);
    }

    private void open(Path path) {
        if (path == null) {
            return;
        }
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            error("Opening files is not supported on this system.");
            return;
        }
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | IllegalArgumentException e) {
            error("Could not open " + path + ":\n" + e.getMessage());
        }
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "File Search", JOptionPane.ERROR_MESSAGE);
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format("%.1f %s", v, units[u]);
    }

    /** Runs the search off the event dispatch thread and streams results into the table. */
    private final class SearchWorker extends SwingWorker<FileSearcher.Summary, SearchResult> {
        private final SearchCriteria criteria;
        private final AtomicBoolean stop = new AtomicBoolean();
        private final AtomicReference<Path> currentDir = new AtomicReference<>();
        private final long startNanos = System.nanoTime();
        private long lastStatusUpdate;

        SearchWorker(SearchCriteria criteria) {
            this.criteria = criteria;
        }

        void requestStop() {
            stop.set(true);
        }

        @Override
        protected FileSearcher.Summary doInBackground() throws IOException {
            return new FileSearcher().search(criteria, new FileSearcher.Listener() {
                @Override
                public void onResult(SearchResult result) {
                    publish(result);
                }

                @Override
                public void onDirectory(Path dir) {
                    currentDir.set(dir);
                }
            }, stop::get);
        }

        @Override
        protected void process(List<SearchResult> chunks) {
            // Chunks can arrive after a newer search has started; they belong to the old one.
            if (worker != this) {
                return;
            }
            model.addAll(chunks);
            long now = System.currentTimeMillis();
            // process() may also run after done(); don't overwrite the final status then.
            if (now - lastStatusUpdate > 100 && !stop.get() && !isDone()) {
                lastStatusUpdate = now;
                Path dir = currentDir.get();
                statusLabel.setText(model.getRowCount() + " found — searching "
                        + (dir == null ? criteria.root() : dir));
            }
        }

        @Override
        protected void done() {
            if (worker != this) {
                return;
            }
            setSearching(false);
            try {
                FileSearcher.Summary s = get();
                double secs = (System.nanoTime() - startNanos) / 1e9;
                StringBuilder msg = new StringBuilder()
                        .append(s.cancelled() ? "Stopped. " : "Done. ")
                        .append(s.matches()).append(s.matches() == 1 ? " file" : " files")
                        .append(" found, ").append(s.filesScanned()).append(" scanned in ")
                        .append(String.format("%.1f s", secs));
                if (s.errors() > 0) {
                    msg.append(", ").append(s.errors()).append(" could not be read");
                }
                if (s.skipped() > 0) {
                    msg.append(", ").append(s.skipped()).append(" too large to search for text");
                }
                statusLabel.setText(msg.toString());
            } catch (CancellationException e) {
                statusLabel.setText("Stopped.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                statusLabel.setText("Search failed: " + cause.getMessage());
                error("Search failed:\n" + cause.getMessage());
            }
        }
    }
}
