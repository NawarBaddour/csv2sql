package csv2sql;

import java.util.Locale;

/** One output column: its identifier and the type inferred for it so far. */
final class Column {

    private final String name;
    private SqlType type = SqlType.NULL;
    private int intDigits;
    private int scale;

    Column(String name) {
        this.name = name;
    }

    String name() {
        return name;
    }

    SqlType type() {
        return type;
    }

    /**
     * Folds a value into the column's inferred type. Nulls are ignored;
     * {@code emptyIsNull} tells us whether a blank cell is about to be written
     * as {@code NULL}, in which case it should not drag the column to TEXT.
     */
    void observe(String value, boolean emptyIsNull) {
        if (isNull(value, emptyIsNull)) {
            return;
        }
        SqlType seen = SqlType.classify(value);
        type = SqlType.merge(type, seen);
        if (seen == SqlType.DECIMAL) {
            widenDecimal(value);
        }
    }

    /**
     * Renders one value as a SQL literal for this column.
     *
     * <p>Numeric and boolean columns get bare literals ({@code 42}, {@code TRUE})
     * so strict databases accept them. Inference only sees a sample, so a later
     * value can still fail to fit its column; those fall back to a quoted
     * literal, which gives a type error naming the bad value instead of a
     * syntax error that would take down the whole multi-row INSERT.</p>
     */
    String literal(String raw, SqlDialect dialect, boolean emptyIsNull) {
        if (isNull(raw, emptyIsNull)) {
            return "NULL";
        }
        switch (type) {
            case BOOLEAN:
                if (SqlType.isBoolean(raw)) {
                    String lower = raw.toLowerCase(Locale.ROOT);
                    return lower.equals("yes") || lower.equals("true") ? "TRUE" : "FALSE";
                }
                return dialect.literal(raw);
            case INTEGER:
            case BIGINT:
            case DECIMAL:
            case DOUBLE:
                return SqlType.isNumeric(raw) ? raw : dialect.literal(raw);
            default:
                return dialect.literal(raw);
        }
    }

    /** How this column should be written in a CREATE TABLE. */
    String sqlType(SqlDialect dialect) {
        if (type == SqlType.DECIMAL && intDigits + scale > 0) {
            return "DECIMAL(" + (intDigits + scale) + "," + scale + ")";
        }
        return dialect.columnType(type);
    }

    private static boolean isNull(String value, boolean emptyIsNull) {
        return value == null || (value.isEmpty() && emptyIsNull);
    }

    /**
     * Tracks the widest whole part and widest fractional part seen, which
     * together give a DECIMAL(p, s) that holds every value without rounding.
     */
    private void widenDecimal(String value) {
        String digits = value.trim();
        if (digits.charAt(0) == '+' || digits.charAt(0) == '-') {
            digits = digits.substring(1);
        }
        int dot = digits.indexOf('.');
        String whole = trimLeadingZeros(dot < 0 ? digits : digits.substring(0, dot));
        int fracLength = dot < 0 ? 0 : digits.length() - dot - 1;
        intDigits = Math.max(intDigits, whole.length());
        scale = Math.max(scale, fracLength);
        if (intDigits + scale > 38) {
            type = SqlType.DOUBLE; // wider than any DECIMAL
        }
    }

    private static String trimLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }

    /**
     * Turns a header cell into a portable identifier: letters, digits and
     * underscores only, never starting with a digit.
     */
    static String sanitize(String raw, boolean lowerCase) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            s = "column";
        }
        if (lowerCase) {
            s = s.toLowerCase(Locale.ROOT);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        s = sb.toString();
        return Character.isDigit(s.charAt(0)) ? "c_" + s : s;
    }
}
