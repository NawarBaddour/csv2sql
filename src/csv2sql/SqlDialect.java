package csv2sql;

/** Identifier and literal quoting rules for a handful of common databases. */
enum SqlDialect {
    /** ANSI / PostgreSQL / SQLite: {@code "double quotes"}. */
    STANDARD,
    MYSQL,
    POSTGRES,
    SQLITE,
    /** Emit identifiers bare — useful when loading into a tool that fixes its own schema. */
    NONE;

    static SqlDialect parse(String name) {
        String key = name.toUpperCase().replace('-', '_');
        for (SqlDialect d : values()) {
            if (d.name().equals(key)) {
                return d;
            }
        }
        if (key.equals("ANSI")) {
            return STANDARD;
        }
        throw new IllegalArgumentException("unknown dialect '" + name
                + "' (expected: standard, mysql, postgres, sqlite, none)");
    }

    /** Renders an identifier so it survives reserved words and odd characters. */
    String identifier(String name) {
        switch (this) {
            case NONE:
                return name;
            case MYSQL:
                return "`" + name.replace("`", "``") + "`";
            default:
                return "\"" + name.replace("\"", "\"\"") + "\"";
        }
    }

    /** Renders a string constant. The caller must check for nulls first. */
    String literal(String value) {
        if (this == MYSQL) {
            // MySQL treats backslash as an escape character inside string literals.
            StringBuilder sb = new StringBuilder(value.length() + 8);
            sb.append('\'');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' || c == '\'') {
                    sb.append('\\').append(c);
                } else if (c == '\n') {
                    sb.append("\\n");
                } else if (c == '\r') {
                    sb.append("\\r");
                } else if (c == 0) {
                    sb.append("\\0");
                } else if (c == 0x1a) {
                    sb.append("\\Z");
                } else {
                    sb.append(c);
                }
            }
            return sb.append('\'').toString();
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** Maps a portable type onto this database's spelling of it. */
    String columnType(SqlType type) {
        switch (type) {
            case BOOLEAN:
                return this == MYSQL ? "TINYINT(1)" : "BOOLEAN";
            case DOUBLE:
                return this == MYSQL ? "DOUBLE" : "DOUBLE PRECISION";
            default:
                return type.sqlName();
        }
    }
}
