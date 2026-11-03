package hn.hotel.service;

import hn.hotel.db.Db;
import hn.hotel.db.Tx;

import java.io.*;
import java.util.Properties;

/**
 * Almacenes y puntos de venta. El almacén que mueve cada operación se indica con la variable de sesión
 * {@code @almacen_id}, que leen los triggers de la base de datos (sin valor = almacén principal).
 * Cada operación abre su propia conexión, así que la variable no se «pega» entre operaciones.
 */
public final class Almacenes {
    private Almacenes() {}

    /** Los movimientos de inventario de esta conexión afectarán al almacén indicado (null = principal). */
    public static void usar(Tx tx, Integer idAlmacen) throws Exception {
        if (idAlmacen == null || idAlmacen <= 0) tx.exec("SET @almacen_id = NULL");
        else tx.exec("SET @almacen_id = ?", idAlmacen);
    }

    /** Almacén del punto de venta con el que se abrió la comanda. */
    public static void deComanda(Tx tx, int idComanda) throws Exception {
        Object a = tx.scalar("SELECT pv.id_almacen FROM comandas c JOIN puntos_venta pv ON pv.id_punto = c.id_punto_venta WHERE c.id_comanda = ?", idComanda);
        usar(tx, a == null ? null : Db.ent(a));
    }

    public static void deDetalle(Tx tx, int idDetalle) throws Exception {
        Object a = tx.scalar("SELECT pv.id_almacen FROM comanda_detalle d JOIN comandas c ON c.id_comanda = d.id_comanda "
                + "JOIN puntos_venta pv ON pv.id_punto = c.id_punto_venta WHERE d.id_detalle = ?", idDetalle);
        usar(tx, a == null ? null : Db.ent(a));
    }

    /** Puntos de venta en los que puede trabajar el usuario actual (el administrador, en todos). Cada fila: id_punto, nombre. */
    public static java.util.List<java.util.Map<String, Object>> permitidos() {
        try {
            if (hn.hotel.app.Sesion.esAdmin()) return Db.query("SELECT id_punto, nombre FROM puntos_venta WHERE activo = 1 ORDER BY nombre");
            return Db.query("SELECT p.id_punto, p.nombre FROM usuario_puntos u JOIN puntos_venta p ON p.id_punto = u.id_punto WHERE u.id_usuario = ? AND p.activo = 1 ORDER BY p.nombre",
                    hn.hotel.app.Sesion.idUsuario);
        } catch (RuntimeException e) { return java.util.List.of(); }   // aún no se ha corrido el script 20
    }

    public static String nombrePuntoActual() {
        Integer id = puntoLocal();
        if (id == null) return null;
        Object n = Db.scalar("SELECT nombre FROM puntos_venta WHERE id_punto = ?", id);
        return n == null ? null : n.toString();
    }

    // ------------------------------------------------------------------ punto de venta de ESTA computadora
    /** Punto de venta configurado en esta PC (hotel.properties), o null. */
    public static synchronized Integer puntoLocal() {
        if (hn.hotel.app.Sesion.idPuntoVenta != null) return hn.hotel.app.Sesion.idPuntoVenta;   // el punto asignado al usuario manda
        return puntoDeLaPc();
    }

    /** Punto de venta configurado solo en esta computadora. */
    public static synchronized Integer puntoDeLaPc() {
        File f = Db.archivoConfig();
        if (!f.exists()) return null;
        Properties p = new Properties();
        try (InputStream is = new FileInputStream(f)) { p.load(is); } catch (IOException e) { return null; }
        String v = p.getProperty("punto_venta");
        try { return v == null || v.isBlank() ? null : Integer.valueOf(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    public static synchronized void guardarPuntoLocal(Integer id) {
        try { guardar(id); } catch (IOException e) { throw new UncheckedIOException("No se pudo guardar la configuración de esta computadora.", e); }
    }

    private static void guardar(Integer id) throws IOException {
        File f = Db.archivoConfig();
        Properties p = new Properties();
        if (f.exists()) try (InputStream is = new FileInputStream(f)) { p.load(is); }
        if (id == null) p.remove("punto_venta"); else p.setProperty("punto_venta", String.valueOf(id));
        try (OutputStream os = new FileOutputStream(f)) { p.store(os, "Configuracion del sistema"); }
    }
}
