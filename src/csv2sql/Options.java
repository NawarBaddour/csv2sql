package csv2sql;

import java.nio.charset.Charset;
import java.util.Locale;

/** The converter's settings, parsed from the command line. */
final class Options {

    static final int DEFAULT_SAMPLE = 1000;
    static final int DEFAULT_BATCH = 100;

    static final String USAGE = String.join("\n",
        "csv2sql - convert a CSV file into SQL statements",
        "",
        "USAGE",
        "  csv2sql -i <file> [-o <file>] -t <table> [options]",
        "  cat data.csv | csv2sql -t users > schema.sql",
        "",
        "INPUT / OUTPUT",
        "  -i, --input <path>       CSV file to read, or - for stdin (default: -)",
        "  -o, --output <path>      File to write, or - for stdout (default: -)",
        "  -t, --table <name>       Target table name (default: my_table)",
        "  --dialect <name>        standard | mysql | postgres | sqlite | none",
        "                           (default: standard)",
        "  --encoding <charset>    Input and output charset (default: UTF-8)",
        "",
        "CSV FORMAT",
        "  --delimiter <x>         auto | csv | tsv | semicolon | pipe | any single char",
        "                           (default: auto)",
        "  --quote <char>          Quote character, or none (default: \")",
        "  --escape <char>         Escape character, or none (default: none)",
        "  --no-header             Treat every row as data; columns become col1, col2, ...",
        "  --empty-as-string       Keep empty cells as '' instead of writing NULL",
        "",
        "OUTPUT SHAPE",
        "  --drop-table            Emit DROP TABLE IF EXISTS first",
        "  --truncate              Emit TRUNCATE TABLE after the DDL",
        "  --no-create-table       Emit INSERTs but no CREATE TABLE",
        "  --data-only             Alias for --no-create-table",
        "  --schema-only           Emit only the CREATE TABLE",
        "  --transaction           Wrap everything in BEGIN / COMMIT",
        "  --no-comments           Omit the generated-by header comment",
        "  --compact               Put each statement on a single line",
        "",
        "TUNING",
        "  --sample-size <n>       Rows to inspect when inferring types (default: 1000)",
        "  --batch-size <n>        Rows per multi-row INSERT (default: 100)",
        "  --lower-case-names      Lowercase the generated column names",
        "",
        "NOTES",
        "  Types are inferred from the first --sample-size rows and only ever widen, so a",
        "  column that looks INTEGER early on may still come out as TEXT. Use",
        "  --sample-size 0 to skip inference and make every column TEXT.",
        "",
        "EXAMPLES",
        "  csv2sql -i users.csv -t users -o users.sql",
        "  csv2sql -i users.csv -t users --dialect mysql --drop-table --transaction",
        "  csv2sql -i orders.csv -t orders --empty-as-string --batch-size 500");

    /** Input path, or {@code -} for stdin. */
    String input = "-";
    /** Output path, or {@code -} for stdout. */
    String output = "-";

    String table = "my_table";
    SqlDialect dialect = SqlDialect.STANDARD;
    Charset charset = Charset.forName("UTF-8");

    /** {@code null} means detect it from the file. */
    Character delimiter;
    char quote = '"';
    char escape = CsvReader.NO_ESCAPE;

    boolean header = true;
    boolean lowerCaseNames;
    boolean emptyAsNull = true;

    int sampleSize = DEFAULT_SAMPLE;
    int batchSize = DEFAULT_BATCH;

    boolean dropTable;
    boolean truncate;
    boolean createTable = true;
    boolean dataOnly;
    boolean schemaOnly;
    boolean transaction;
    boolean comments = true;
    boolean compact;

    static Options parse(String[] args) {
        Options o = new Options();
        Cursor c = new Cursor(args);

        while (c.hasNext()) {
            String token = c.next();
            switch (token) {
                case "-i":
                case "--input":
                    o.input = c.value(token);
                    break;
                case "-o":
                case "--output":
                    o.output = c.value(token);
                    break;
                case "-t":
                case "--table":
                    o.table = Column.sanitize(c.value(token), true);
                    break;
                case "--dialect":
                    o.dialect = SqlDialect.parse(c.value(token));
                    break;
                case "--encoding":
                    o.charset = Charset.forName(c.value(token));
                    break;
                case "--delimiter":
                    o.delimiter = parseDelimiter(c.value(token));
                    break;
                case "--quote":
                    o.quote = parseChar(c.value(token));
                    break;
                case "--escape": {
                    String raw = c.value(token);
                    o.escape = raw.equalsIgnoreCase("none") ? CsvReader.NO_ESCAPE : parseChar(raw);
                    break;
                }
                case "--sample-size":
                    o.sampleSize = c.intValue(token, 0);
                    break;
                case "--batch-size":
                    o.batchSize = c.intValue(token, 1);
                    break;
                case "--no-header":
                    o.header = false;
                    break;
                case "--lower-case-names":
                    o.lowerCaseNames = true;
                    break;
                case "--empty-as-string":
                    o.emptyAsNull = false;
                    break;
                case "--drop-table":
                    o.dropTable = true;
                    break;
                case "--truncate":
                    o.truncate = true;
                    break;
                case "--no-create-table":
                    o.createTable = false;
                    break;
                case "--data-only":
                    o.dataOnly = true;
                    o.createTable = false;
                    break;
                case "--schema-only":
                    o.schemaOnly = true;
                    break;
                case "--transaction":
                    o.transaction = true;
                    break;
                case "--no-comments":
                    o.comments = false;
                    break;
                case "--compact":
                    o.compact = true;
                    break;
                case "-h":
                case "--help":
                    throw new HelpRequested(USAGE);
                case "--version":
                    throw new HelpRequested("csv2sql 1.0.0");
                default:
                    throw new IllegalArgumentException(
                            "unexpected argument '" + token + "'\n"
                                    + "input files are given with -i <path> (or piped on stdin)");
            }
        }

        if (o.dataOnly && o.schemaOnly) {
            throw new IllegalArgumentException(
                    "--data-only and --schema-only cannot be combined");
        }
        return o;
    }

    /** Signals that the process should print something and exit successfully. */
    static final class HelpRequested extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HelpRequested(String message) {
            super(message);
        }
    }

    private static Character parseDelimiter(String raw) {
        String v = raw.toLowerCase(Locale.ROOT);
        if (v.equals("auto")) {
            return null;
        }
        if (v.equals("csv") || v.equals("comma")) {
            return ',';
        }
        if (v.equals("tsv") || v.equals("tab")) {
            return '\t';
        }
        if (v.equals("semicolon") || v.equals("semi")) {
            return ';';
        }
        if (v.equals("pipe")) {
            return '|';
        }
        return parseChar(raw);
    }

    /** Accepts a single character, or the word {@code none} to disable. */
    private static char parseChar(String raw) {
        if (raw.equalsIgnoreCase("none")) {
            return CsvReader.NO_ESCAPE;
        }
        if (raw.length() != 1) {
            throw new IllegalArgumentException(
                    "expected a single character or 'none', got '" + raw + "'");
        }
        return raw.charAt(0);
    }

    /** Walks the arguments, supporting both {@code --flag value} and {@code --flag=value}. */
    private static final class Cursor {

        private final String[] args;
        private int i;
        /** A value split off a {@code --flag=value} token, waiting to be read. */
        private String pending;

        Cursor(String[] args) {
            this.args = args;
        }

        boolean hasNext() {
            return pending != null || i < args.length;
        }

        String next() {
            if (pending != null) {
                return take();
            }
            String arg = args[i++];
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                pending = arg.substring(eq + 1);
                return arg.substring(0, eq);
            }
            return arg;
        }

        String value(String flag) {
            if (pending != null) {
                return take();
            }
            if (i >= args.length) {
                throw new IllegalArgumentException("option '" + flag + "' needs a value");
            }
            return args[i++];
        }

        int intValue(String flag, int min) {
            String raw = value(flag).trim();
            int parsed;
            try {
                parsed = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "option '" + flag + "' expects a number, got '" + raw + "'");
            }
            if (parsed < min) {
                throw new IllegalArgumentException("option '" + flag + "' must be >= " + min);
            }
            return parsed;
        }

        private String take() {
            String value = pending;
            pending = null;
            return value;
        }
    }
}
