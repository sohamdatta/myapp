package com.example.loginapp.core;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Small JDBC helpers. Rows come back as ordered maps that serialise directly to JSON. */
public final class Sql {

    private Sql() {}

    public static List<Map<String, Object>> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(c, ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(camel(meta.getColumnLabel(i)), value(rs, i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    /** The first row, or null when there is none. */
    public static Map<String, Object> one(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, Object>> rows = query(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(c, ps, params);
            return ps.executeUpdate();
        }
    }

    /**
     * Runs one statement once for each row of parameters, sending them to the database together:
     * one round trip for the lot instead of one each. Returns how many rows were written. Rows are
     * bound exactly as in {@link #update}. A statement that needs the generated key back cannot use
     * this; generate the key in Java and insert it.
     */
    public static int batch(Connection c, String sql, List<Object[]> rows) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Object[] row : rows) {
                bind(c, ps, row);
                ps.addBatch();
            }
            int written = 0;
            for (int n : ps.executeBatch()) {
                // A driver may answer "succeeded, count unknown" for a batch; that is one row here.
                written += n == PreparedStatement.SUCCESS_NO_INFO ? 1 : n;
            }
            return written;
        }
    }

    private static void bind(Connection c, PreparedStatement ps, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p instanceof byte[] bytes) {
                ps.setBytes(i + 1, bytes);
            } else if (p instanceof UUID[] ids) {
                ps.setArray(i + 1, c.createArrayOf("uuid", ids));
            } else if (p instanceof String[] strings) {
                ps.setArray(i + 1, c.createArrayOf("text", strings));
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    private static Object value(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof Array array) {
            List<String> items = new ArrayList<>();
            for (Object item : (Object[]) array.getArray()) {
                items.add(item == null ? null : item.toString());
            }
            return items;
        }
        if (v instanceof byte[]) {
            return null;   // hashes and secrets never leave the database layer
        }
        return rs.getString(i);   // uuid, citext, jsonb, inet
    }

    private static String camel(String label) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char ch : label.toCharArray()) {
            if (ch == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(ch) : ch);
                upper = false;
            }
        }
        return out.toString();
    }
}
