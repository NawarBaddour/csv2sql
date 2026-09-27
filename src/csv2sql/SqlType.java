package csv2sql;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * The SQL types the converter can infer, ordered from narrowest to widest so
 * that {@link #merge(SqlType, SqlType)} can widen a column as it sees more
 * data.
 */
enum SqlType {
    /** Every sampled value was null; the column still needs a storage type. */
    NULL("TEXT"),
    BOOLEAN("BOOLEAN"),
    INTEGER("INTEGER"),
    BIGINT("BIGINT"),
    DECIMAL("DECIMAL"),
    DOUBLE("DOUBLE PRECISION"),
    DATE("DATE"),
    TIMESTAMP("TIMESTAMP"),
    TEXT("TEXT");

    private static final Pattern INT = Pattern.compile("^[+-]?\\d+$");
    private static final Pattern DEC = Pattern.compile("^[+-]?(\\d+\\.\\d*|\\.\\d+)$");
    private static final Pattern SCI = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)[eE][+-]?\\d+$");
    private static final Pattern BOOL = Pattern.compile("^(true|false|yes|no)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final Pattern TS_RE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2})?(\\.\\d+)?$");

    private final String sqlName;

    SqlType(String sqlName) {
        this.sqlName = sqlName;
    }

    String sqlName() {
        return sqlName;
    }

    /**
     * Combines the types observed so far. Widening only, never narrowing: a
     * column that is {@code INTEGER} for the first 1 000 rows and {@code TEXT}
     * for row 1 001 stays {@code TEXT}.
     */
    static SqlType merge(SqlType a, SqlType b) {
        if (a == null) {
            return b;
        }
        if (b == null || a == b) {
            return a;
        }
        if (a == TEXT || b == TEXT) {
            return TEXT;
        }
        if (a == BOOLEAN || b == BOOLEAN) {
            // mixing "yes" with numbers or dates is not worth guessing about
            return TEXT;
        }
        boolean aTemporal = (a == DATE || a == TIMESTAMP);
        boolean bTemporal = (b == DATE || b == TIMESTAMP);
        if (aTemporal != bTemporal) {
            return TEXT; // temporal and numeric never mix cleanly
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** True for anything that can be written as a bare SQL numeric literal. */
    static boolean isNumeric(String value) {
        return INT.matcher(value).matches()
                || DEC.matcher(value).matches()
                || SCI.matcher(value).matches();
    }

    /** True for the boolean spellings this tool recognises. */
    static boolean isBoolean(String value) {
        return BOOL.matcher(value).matches();
    }

    /** Classifies a single non-null value. */
    static SqlType classify(String value) {
        if (INT.matcher(value).matches()) {
            return fitsInInt(value) ? INTEGER : BIGINT;
        }
        if (DEC.matcher(value).matches() || SCI.matcher(value).matches()) {
            return SCI.matcher(value).matches() ? DOUBLE : DECIMAL;
        }
        if (BOOL.matcher(value).matches()) {
            return BOOLEAN;
        }
        if (DATE_RE.matcher(value).matches() && isDate(value)) {
            return DATE;
        }
        if (TS_RE.matcher(value).matches() && isTimestamp(value)) {
            return TIMESTAMP;
        }
        return TEXT;
    }

    private static boolean fitsInInt(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDate(String value) {
        try {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isTimestamp(String value) {
        String normalised = value.indexOf(' ') > 0 ? value.replace(' ', 'T') : value;
        try {
            LocalDateTime.parse(normalised, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
