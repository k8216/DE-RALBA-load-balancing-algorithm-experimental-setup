package edu.kunal.spike.lb;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal CSV output for experiment results.
 *
 * appendRow keeps one summary file for the whole study (one line per run). It refuses to append a row
 * whose columns differ from the file's header, so a changed schema can never silently misalign data.
 * writeSeries writes one time-series file per run.
 */
public final class CsvLog {

    private CsvLog() {
    }

    public static synchronized void appendRow(Path file, Map<String, ?> row) throws IOException {
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("Row has no columns.");
        }
        List<String> cols = new ArrayList<>(row.keySet());
        StringBuilder header = new StringBuilder();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                header.append(',');
                line.append(',');
            }
            header.append(escape(cols.get(i)));
            line.append(escape(format(row.get(cols.get(i)))));
        }

        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        if (!Files.exists(file) || Files.size(file) == 0) {
            Files.writeString(file, header + "\n" + line + "\n", StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return;
        }
        String existing;
        try (BufferedReader r = Files.newBufferedReader(file)) {
            existing = r.readLine();
        }
        if (!header.toString().equals(existing)) {
            throw new IllegalStateException("Columns differ from the header of " + file
                    + ". Use a new file or delete the old one.\n  file : " + existing + "\n  row  : " + header);
        }
        Files.writeString(file, line + "\n", StandardOpenOption.APPEND);
    }

    /** Writes columns as a table (first column is usually time). All arrays must have equal length. */
    public static void writeSeries(Path file, Map<String, double[]> columns) throws IOException {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("Series has no columns.");
        }
        List<String> names = new ArrayList<>(columns.keySet());
        int rows = -1;
        for (String name : names) {
            double[] col = columns.get(name);
            if (col == null) {
                throw new IllegalArgumentException("Series column '" + name + "' is null.");
            }
            if (rows < 0) {
                rows = col.length;
            } else if (col.length != rows) {
                throw new IllegalArgumentException("Series column '" + name + "' has " + col.length
                        + " rows, expected " + rows + ".");
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(names.get(i)));
        }
        sb.append('\n');
        for (int r = 0; r < rows; r++) {
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(format(columns.get(names.get(i))[r]));
            }
            sb.append('\n');
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, sb.toString());
    }

    /** NaN and infinity become empty cells, which R, pandas and Excel all read as missing. */
    static String format(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof Double) {
            double d = (Double) v;
            return Double.isNaN(d) || Double.isInfinite(d) ? "" : Double.toString(d);
        }
        return String.valueOf(v);
    }

    static String escape(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}