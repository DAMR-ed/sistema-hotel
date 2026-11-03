package hn.hotel.db;

import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Acceso a MySQL. Lee la configuración de "hotel.properties" (junto al programa);
 * si no existe lo crea con valores para desarrollo local (Docker, puerto 3307).
 */
public final class Db {
    private Db() {}

    private static String url, usuario, clave;

    @FunctionalInterface
    public interface Trabajo<T> { T hacer(Tx tx) throws Exception; }

    /**
     * hotel.properties: si existe junto al programa (modo desarrollo) se usa ese; si no, la carpeta del usuario
     * (Windows: %APPDATA%\SistemaHotel). Así funciona también instalado en «Archivos de programa», donde no se puede escribir.
     */
    public static File archivoConfig() {
        File local = new File("hotel.properties");
        if (local.exists()) return local;
        String appdata = System.getenv("APPDATA");
        File dir = appdata != null && !appdata.isBlank() ? new File(appdata, "SistemaHotel") : new File(System.getProperty("user.home"), ".sistemahotel");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "hotel.properties");
    }

    public static synchronized void cargarConfiguracion() throws IOException {
        File f = archivoConfig();
        Properties p = new Properties();
        if (!f.exists()) {
            p.setProperty("db.url", "jdbc:mysql://localhost:3307/hotel_sistema"
                    + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=America/Tegucigalpa"
                    + "&allowPublicKeyRetrieval=true&useSSL=false&tinyInt1isBit=false");
            p.setProperty("db.usuario", "hotel_app");
            p.setProperty("db.clave", "Hotel_App_2026!");
            try (OutputStream os = new FileOutputStream(f)) {
                p.store(os, "Conexion a la base de datos (cambie la clave en produccion)");
            }
        } else {
            try (InputStream is = new FileInputStream(f)) { p.load(is); }
        }
        url = p.getProperty("db.url");
        usuario = p.getProperty("db.usuario");
        clave = p.getProperty("db.clave");
    }

    /** Servidor y puerto actuales (de la URL de conexión). */
    public static String[] servidorActual() {
        if (url == null) { try { cargarConfiguracion(); } catch (IOException e) { return new String[]{"localhost", "3306"}; } }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("//([^:/]+):?(\\d*)/").matcher(url);
        return m.find() ? new String[]{m.group(1), m.group(2).isEmpty() ? "3306" : m.group(2)} : new String[]{"localhost", "3306"};
    }

    public static String usuarioActual() { return usuario; }

    /** Prueba una conexión sin guardar nada. Lanza SQLException con el motivo si falla. */
    public static void probar(String host, String puerto, String user, String pass) throws SQLException {
        DriverManager.setLoginTimeout(6);
        try (Connection cn = DriverManager.getConnection(urlDe(host, puerto), user, pass)) { cn.isValid(3); }
    }

    private static String urlDe(String host, String puerto) {
        return "jdbc:mysql://" + host.trim() + ":" + puerto.trim() + "/hotel_sistema?useUnicode=true&characterEncoding=UTF-8"
                + "&serverTimezone=America/Tegucigalpa&allowPublicKeyRetrieval=true&useSSL=false&tinyInt1isBit=false&connectTimeout=6000";
    }

    /** Guarda la conexión (servidor de la red) en hotel.properties y la usa de inmediato. */
    public static synchronized void guardarConexion(String host, String puerto, String user, String pass) throws IOException {
        Properties p = new Properties();
        p.setProperty("db.url", urlDe(host, puerto));
        p.setProperty("db.usuario", user);
        p.setProperty("db.clave", pass);
        try (OutputStream os = new FileOutputStream(archivoConfig())) { p.store(os, "Conexion a la base de datos del sistema"); }
        url = p.getProperty("db.url");
        usuario = user;
        clave = pass;
    }

    public static Connection abrir() throws SQLException {
        if (url == null) {
            try { cargarConfiguracion(); } catch (IOException e) { throw new SQLException("No se pudo leer hotel.properties", e); }
        }
        return DriverManager.getConnection(url, usuario, clave);
    }

    /** Ejecuta trabajo sin transacción (autocommit). */
    public static <T> T sin(Trabajo<T> t) {
        try (Connection cn = abrir()) {
            return t.hacer(new Tx(cn));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DbException(mensaje(e), e);
        }
    }

    /** Ejecuta trabajo dentro de UNA transacción: todo o nada. */
    public static <T> T transaccion(Trabajo<T> t) {
        try (Connection cn = abrir()) {
            cn.setAutoCommit(false);
            try {
                T r = t.hacer(new Tx(cn));
                cn.commit();
                return r;
            } catch (Throwable e) {
                try { cn.rollback(); } catch (SQLException ignorar) { }
                if (e instanceof RuntimeException re) throw re;
                throw new DbException(mensaje(e), e);
            }
        } catch (DbException e) {
            throw e;
        } catch (Exception e) {
            throw new DbException(mensaje(e), e);
        }
    }

    // Atajos para consultas simples
    public static List<Map<String, Object>> query(String sql, Object... p) { return sin(tx -> tx.query(sql, p)); }
    public static Map<String, Object> uno(String sql, Object... p) { return sin(tx -> tx.uno(sql, p)); }
    public static Object scalar(String sql, Object... p) { return sin(tx -> tx.scalar(sql, p)); }
    public static int exec(String sql, Object... p) { return sin(tx -> tx.exec(sql, p)); }
    public static long insert(String sql, Object... p) { return sin(tx -> tx.insert(sql, p)); }

    /** Mensaje entendible: los triggers/procedimientos devuelven el texto en español. */
    private static String mensaje(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException se && "45000".equals(se.getSQLState())) return se.getMessage();
            t = t.getCause();
        }
        if (e instanceof SQLException se) {
            if (se.getErrorCode() == 1062) return "Ya existe un registro con esos datos (duplicado).";
            if (se.getErrorCode() == 1451) return "No se puede eliminar: tiene registros relacionados.";
            if (se.getErrorCode() == 1452) return "Falta un dato relacionado (llave foránea).";
            if (se.getErrorCode() == 3819) return "Un dato no cumple las reglas de validación.";
            return "Error de base de datos: " + se.getMessage();
        }
        return String.valueOf(e.getMessage());
    }

    public static class DbException extends RuntimeException {
        public DbException(String m, Throwable c) { super(m, c); }
    }

    // Conversión segura de valores devueltos por JDBC
    public static String str(Object o) { return o == null ? "" : o.toString(); }
    public static int ent(Object o) { if (o == null) return 0; if (o instanceof Boolean b) return b ? 1 : 0; return ((Number) o).intValue(); }
    public static long lng(Object o) { if (o == null) return 0; if (o instanceof Boolean b) return b ? 1 : 0; return ((Number) o).longValue(); }
    public static java.math.BigDecimal dec(Object o) {
        if (o == null) return java.math.BigDecimal.ZERO;
        if (o instanceof java.math.BigDecimal b) return b;
        return new java.math.BigDecimal(o.toString());
    }
}
