package filesearch.ui;

import filesearch.SearchResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/** Table model backing the results list in {@link SearchFrame}. */
final class ResultTableModel extends AbstractTableModel {

    static final int COL_NAME = 0;
    static final int COL_FOLDER = 1;
    static final int COL_SIZE = 2;
    static final int COL_MODIFIED = 3;
    static final int COL_HITS = 4;

    private static final String[] COLUMNS = {"Name", "Folder", "Size", "Modified", "Hits"};
    private static final Class<?>[] TYPES = {String.class, String.class, Long.class, Instant.class, Integer.class};

    private final List<SearchResult> rows = new ArrayList<>();

    void addAll(List<SearchResult> results) {
        if (results.isEmpty()) {
            return;
        }
        int first = rows.size();
        rows.addAll(results);
        fireTableRowsInserted(first, rows.size() - 1);
    }

    void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    SearchResult get(int modelRow) {
        return rows.get(modelRow);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return TYPES[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        SearchResult r = rows.get(row);
        return switch (column) {
            case COL_NAME -> r.fileName();
            case COL_FOLDER -> {
                Path parent = r.path().getParent();
                yield parent == null ? "" : parent.toString();
            }
            case COL_SIZE -> r.size();
            case COL_MODIFIED -> r.lastModified();
            case COL_HITS -> r.matches().size();
            default -> throw new IllegalArgumentException("column " + column);
        };
    }
}
