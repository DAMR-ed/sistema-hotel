package hn.hotel.db;

import java.sql.*;
import java.util.*;

/** Operaciones SQL sobre UNA conexión (sirve dentro o fuera de una transacción). */
public class Tx {
    private final Connection cn;

    Tx(Connection cn) { this.cn = cn; }

    public Connection conexion() { return cn; }

    private PreparedStatement preparar(String sql, boolean claves, Object... p) throws SQLException {
        PreparedStatement ps = claves
                ? cn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                : cn.prepareStatement(sql);
        for (int i = 0; i < p.length; i++) {
            Object v = p[i];
            if (v instanceof java.time.LocalDate d) ps.setObject(i + 1, d);
            else if (v instanceof java.time.LocalDateTime d) ps.setObject(i + 1, d);
            else ps.setObject(i + 1, v);
        }
        return ps;
    }

    /** SELECT: devuelve filas como mapas columna(alias) -> valor. */
    public List<Map<String, Object>> query(String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = preparar(sql, false, p); ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            List<Map<String, Object>> filas = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> f = new LinkedHashMap<>();
                for (int i = 1; i <= n; i++) f.put(md.getColumnLabel(i), rs.getObject(i));
                filas.add(f);
            }
            return filas;
        }
    }

    /** Primera fila o null. */
    public Map<String, Object> uno(String sql, Object... p) throws SQLException {
        List<Map<String, Object>> r = query(sql, p);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Primer valor de la primera fila o null. */
    public Object scalar(String sql, Object... p) throws SQLException {
        Map<String, Object> f = uno(sql, p);
        return f == null ? null : f.values().iterator().next();
    }

    /** INSERT/UPDATE/DELETE: devuelve filas afectadas. */
    public int exec(String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = preparar(sql, false, p)) {
            return ps.executeUpdate();
        }
    }

    /** INSERT: devuelve la clave autogenerada. */
    public long insert(String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = preparar(sql, true, p)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Llama un procedimiento almacenado. Acepta "{CALL sp(?,?)}" o "CALL sp(?,?)". */
    public void call(String sql, Object... p) throws SQLException {
        String s = sql.trim();
        if (s.startsWith("{") && s.endsWith("}")) s = s.substring(1, s.length() - 1).trim();
        try (PreparedStatement ps = preparar(s, false, p)) {
            ps.execute();
        }
    }
}
