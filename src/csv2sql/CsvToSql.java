package csv2sql;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a CSV file and writes the SQL needed to recreate it.
 *
 * <p>
 * The input is read in a single streaming pass. The first {@code --sample-size}
 * data rows are held back so column types can be inferred before the
 * {@code CREATE TABLE} is written; everything after that goes straight out.
 * Memory use is bounded by the sample size, not by the file size.
 * </p>
 */
public final class CsvToSql {

    private static final int SNIFF_BYTES = 64 * 1024;
    private static final int WRITE_BUFFER_BYTES = 64 * 1024;

    private final Options opts;
    private final PrintStream err;
    private final List<Column> columns = new ArrayList<Column>();

    private int width;
    private long rows;
    private int shortRows;
    private int longRows;

    private CsvToSql(Options opts, PrintStream err) {
        this.opts = opts;
        this.err = err;
    }

    public static void main(String[] args) {
        int status;
        try {
            status = new CsvToSql(Options.parse(args), System.err).run();
        } catch (Options.HelpRequested e) {
            System.out.println(e.getMessage());
            return;
        } catch (IllegalArgumentException e) {
            System.err.println("csv2sql: " + e.getMessage());
            System.err.println("Try 'csv2sql --help' for the list of options.");
            status = 2;
        } catch (IOException e) {
            System.err.println("csv2sql: " + e);
            status = 1;
        }
        System.exit(status);
    }

    // ------------------------------------------------------------------

    private int run() throws IOException {
        InputStream in = openInput();
        try {
            CsvReader csv = openReader(in);
            boolean toStdout = isStdio(opts.output);
            OutputStream sink = toStdout
                    ? new Uncloseable(System.out)
                    : new FileOutputStream(opts.output);
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(sink, opts.charset), WRITE_BUFFER_BYTES);
            try {
                return convert(csv, out);
            } finally {
                out.flush();
                if (!toStdout) {
                    out.close();
                }
            }
        } finally {
            if (in != System.in) {
                in.close();
            }
        }
    }

    private CsvReader openReader(InputStream in) throws IOException {
        // Sniffing has to look at the front of the stream, and that peek still
        // has to reach the reader, so buffer a prefix and splice it back on
        // ahead of the rest.
        byte[] prefix = readPrefix(in, SNIFF_BYTES);
        char delimiter = opts.delimiter != null
                ? opts.delimiter
                : Delimiters.detect(new String(prefix, opts.charset));
        if (opts.delimiter == null) {
            err.println("note: detected delimiter " + quoteChar(delimiter)
                    + " (override with --delimiter)");
        }
        InputStream joined = new SequenceInputStream(new ByteArrayInputStream(prefix), in);
        return new CsvReader(new InputStreamReader(joined, opts.charset),
                delimiter, opts.quote, opts.escape);
    }

    private int convert(CsvReader csv, BufferedWriter out) throws IOException {
        List<String> first = csv.read();
        if (first == null) {
            err.println("warning: input is empty, nothing to convert");
            return 1;
        }
        buildColumns(first);
        if (width == 0) {
            err.println("warning: input has no columns, nothing to convert");
            return 1;
        }

        // Hold back enough rows to describe them in the DDL. With sampling off
        // we still keep one, or the first data row would be dropped.
        boolean sampling = opts.sampleSize > 0;
        List<List<String>> held = holdBack(csv, first);
        if (sampling) {
            for (List<String> row : held) {
                for (int i = 0; i < width; i++) {
                    columns.get(i).observe(row.get(i), opts.emptyAsNull);
                }
            }
        }

        writePrologue(out, sampling, held.size());
        if (!opts.schemaOnly) {
            writeRows(csv, out, held);
        }
        if (opts.transaction) {
            out.write("COMMIT;\n");
        }

        report();
        return 0;
    }

    private List<List<String>> holdBack(CsvReader csv, List<String> first) throws IOException {
        List<List<String>> held = new ArrayList<List<String>>();
        if (!opts.header) {
            held.add(normalize(first));
        }
        while (held.size() < Math.max(opts.sampleSize, 1)) {
            List<String> row = csv.read();
            if (row == null) {
                break;
            }
            held.add(normalize(row));
        }
        return held;
    }

    private void writePrologue(BufferedWriter out, boolean sampling, int sampled)
            throws IOException {
        if (opts.comments) {
            out.write("-- generated by csv2sql from " + sourceName() + '\n');
            if (sampling && !opts.schemaOnly) {
                out.write("-- " + describeColumns() + "; inferred from "
                        + sampled + " row(s)\n");
            }
        }
        if (opts.transaction) {
            out.write("BEGIN;\n");
        }
        if (opts.dropTable) {
            out.write("DROP TABLE IF EXISTS " + quotedTable() + ";\n");
        }
        if (opts.createTable) {
            writeCreateTable(out);
        }
        if (opts.truncate) {
            out.write("TRUNCATE TABLE " + quotedTable() + ";\n");
        }
    }

    private void writeCreateTable(BufferedWriter out) throws IOException {
        String indent = opts.compact ? "" : "  ";
        String separator = opts.compact ? ", " : ",\n";
        out.write("CREATE TABLE " + quotedTable() + " (");
        if (!opts.compact) {
            out.write("\n");
        }
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            if (i > 0) {
                out.write(separator);
            }
            out.write(indent + opts.dialect.identifier(c.name()) + " " + c.sqlType(opts.dialect));
        }
        out.write(");\n");
    }

    private void writeRows(CsvReader csv, BufferedWriter out, List<List<String>> held)
            throws IOException {
        Batches batches = new Batches(out);
        for (List<String> row : held) {
            batches.add(row);
            rows++;
        }
        List<String> row;
        while ((row = csv.read()) != null) {
            batches.add(normalize(row));
            rows++;
        }
        batches.flush();
    }

    /**
     * Accumulates rows into multi-row INSERT statements of at most
     * {@code --batch-size}.
     */
    private final class Batches {

        private final BufferedWriter out;
        private final String head;
        private final StringBuilder values = new StringBuilder();
        private int count;

        Batches(BufferedWriter out) {
            this.out = out;
            List<String> names = new ArrayList<String>(width);
            for (Column c : columns) {
                names.add(opts.dialect.identifier(c.name()));
            }
            this.head = "INSERT INTO " + quotedTable() + " (" + join(names) + ")";
        }

        void add(List<String> row) throws IOException {
            values.append(count == 0 ? "VALUES " : opts.compact ? ", " : ",\n  ");
            values.append(tuple(row));
            if (++count == opts.batchSize) {
                flush();
            }
        }

        void flush() throws IOException {
            if (count == 0) {
                return;
            }
            out.write(head);
            out.write(opts.compact ? " " : "\n");
            out.write(values.toString());
            out.write(";\n");
            values.setLength(0);
            count = 0;
        }
    }

    private String tuple(List<String> row) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < width; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(columns.get(i).literal(row.get(i), opts.dialect, opts.emptyAsNull));
        }
        return sb.append(')').toString();
    }

    // ------------------------------------------------------------------

    private void buildColumns(List<String> header) {
        Set<String> used = new HashSet<String>();
        for (int i = 0; i < header.size(); i++) {
            String name = opts.header
                    ? Column.sanitize(header.get(i), opts.lowerCaseNames)
                    : "col" + (i + 1);
            if (!opts.header && opts.lowerCaseNames) {
                name = name.toLowerCase(Locale.ROOT);
            }
            columns.add(new Column(dedupe(name, used)));
        }
        width = columns.size();
    }

    /** Appends {@code _2}, {@code _3}, ... until the name is free. */
    private static String dedupe(String name, Set<String> used) {
        if (used.add(name)) {
            return name;
        }
        for (int n = 2;; n++) {
            String candidate = name + "_" + n;
            if (used.add(candidate)) {
                return candidate;
            }
        }
    }

    /** Pads or truncates a row to the column count and applies the null policy. */
    private List<String> normalize(List<String> row) {
        if (row.size() > width) {
            longRows++;
            row = row.subList(0, width);
        } else if (row.size() < width) {
            shortRows++;
            while (row.size() < width) {
                row.add(null);
            }
        }
        if (!opts.emptyAsNull) {
            for (int i = 0; i < row.size(); i++) {
                if (row.get(i) == null) {
                    row.set(i, "");
                }
            }
        }
        return row;
    }

    private void report() {
        err.println("converted " + rows + " row(s) x " + width
                + " column(s) into " + opts.table);
        if (shortRows > 0) {
            err.println("warning: " + shortRows + " row(s) had fewer than " + width
                    + " fields, padded with NULL");
        }
        if (longRows > 0) {
            err.println("warning: " + longRows + " row(s) had more than " + width
                    + " fields, extra fields ignored");
        }
    }

    private String describeColumns() {
        List<String> parts = new ArrayList<String>(width);
        for (Column c : columns) {
            parts.add(c.name() + " " + c.sqlType(opts.dialect));
        }
        return join(parts);
    }

    private String quotedTable() {
        return opts.dialect.identifier(opts.table);
    }

    private String sourceName() {
        return isStdio(opts.input) ? "stdin" : opts.input;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static boolean isStdio(String path) {
        return "-".equals(path);
    }

    private InputStream openInput() throws IOException {
        return isStdio(opts.input) ? System.in : new FileInputStream(opts.input);
    }

    /**
     * Reads up to {@code max} bytes, stopping as soon as a read comes back
     * short. For a file that yields the whole prefix; for a pipe it takes
     * whatever has arrived rather than blocking on a producer still writing.
     */
    private static byte[] readPrefix(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (out.size() < max) {
            int want = Math.min(chunk.length, max - out.size());
            int read = in.read(chunk, 0, want);
            if (read < 0) {
                break;
            }
            out.write(chunk, 0, read);
            if (read < want) {
                break;
            }
        }
        return out.toByteArray();
    }

    private static String quoteChar(char c) {
        if (c == '\t') {
            return "\\t";
        }
        return c < 0x20 || c > 0x7e ? String.format("\\u%04x", (int) c) : "'" + c + "'";
    }

    /** Keeps {@link System#out} open when the writer is closed. */
    private static final class Uncloseable extends FilterOutputStream {
        Uncloseable(OutputStream out) {
            super(out);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
