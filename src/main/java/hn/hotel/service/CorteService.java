package hn.hotel.service;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.db.Tx;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cortes de caja.
 * Corte X = cambio de turno: suma las facturas que el cajero emitió desde su último corte y deja su caja en blanco.
 *           Las mesas abiertas no se tocan (se cobran en el turno que sigue).
 * Corte Z = cierre del día: suma todos los cortes X que aún no estaban en un Z. Exige que no queden ventas sin cortar.
 */
public final class CorteService {
    private CorteService() {}

    /** Columnas numéricas que se suman de un X al Z. */
    private static final String[] SUMAS = {"n_facturas", "n_anuladas", "n_anul_previas", "mesas_abiertas", "fondo_inicial", "ventas_exento", "ventas_exonerado",
            "ventas_grav15", "ventas_grav18", "isv15", "isv18", "descuentos", "tasa_turistica", "total_ventas", "ventas_contado", "ventas_credito",
            "pago_efectivo", "pago_tarjeta", "pago_transferencia", "pago_cheque", "pago_otro", "propinas", "anulado_total", "anul_previas_total",
            "efectivo_esperado", "efectivo_contado", "diferencia"};

    private static BigDecimal d(Object o) { return Db.dec(o); }

    /** Lo que se cortaría ahora para un cajero (sin guardar nada). */
    public static Map<String, Object> calcularX(Tx tx, int idUsuario, BigDecimal fondo, BigDecimal contado) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, Object> v = tx.uno("SELECT COUNT(*) AS n, COALESCE(SUM(total),0) AS total, COALESCE(SUM(importe_exento),0) AS exento, "
                + "COALESCE(SUM(importe_exonerado),0) AS exonerado, COALESCE(SUM(importe_gravado_15),0) AS g15, COALESCE(SUM(importe_gravado_18),0) AS g18, "
                + "COALESCE(SUM(isv_15),0) AS i15, COALESCE(SUM(isv_18),0) AS i18, COALESCE(SUM(descuento_total),0) AS desc_, COALESCE(SUM(tasa_turistica),0) AS tt, "
                + "COALESCE(SUM(IF(condicion_pago='CREDITO',total,0)),0) AS credito, MIN(numero_factura) AS ini, MAX(numero_factura) AS fin "
                + "FROM facturas WHERE id_usuario = ? AND id_corte IS NULL AND estado = 'EMITIDA'", idUsuario);
        Map<String, Object> a = tx.uno("SELECT COUNT(*) AS n, COALESCE(SUM(total),0) AS total FROM facturas WHERE id_usuario = ? AND id_corte IS NULL AND estado = 'ANULADA'", idUsuario);
        Map<String, Object> ap = tx.uno("SELECT COUNT(*) AS n, COALESCE(SUM(total),0) AS total FROM facturas WHERE id_usuario_anula = ? AND estado = 'ANULADA' "
                + "AND id_corte IS NOT NULL AND anulada_corte IS NULL", idUsuario);
        Object desde = tx.scalar("SELECT MIN(fecha_emision) FROM facturas WHERE id_usuario = ? AND id_corte IS NULL", idUsuario);
        r.put("tipo", "X");
        r.put("id_usuario", idUsuario);
        r.put("desde_fecha", desde);
        r.put("desde_factura", v.get("ini"));
        r.put("hasta_factura", v.get("fin"));
        r.put("n_facturas", Db.ent(v.get("n")));
        r.put("n_anuladas", Db.ent(a.get("n")));
        r.put("n_anul_previas", Db.ent(ap.get("n")));
        r.put("mesas_abiertas", Db.ent(tx.scalar("SELECT COUNT(*) FROM comandas WHERE estado IN ('ABIERTA','CERRADA')")));
        r.put("ventas_exento", d(v.get("exento")));
        r.put("ventas_exonerado", d(v.get("exonerado")));
        r.put("ventas_grav15", d(v.get("g15")));
        r.put("ventas_grav18", d(v.get("g18")));
        r.put("isv15", d(v.get("i15")));
        r.put("isv18", d(v.get("i18")));
        r.put("descuentos", d(v.get("desc_")));
        r.put("tasa_turistica", d(v.get("tt")));
        r.put("total_ventas", d(v.get("total")));
        r.put("ventas_credito", d(v.get("credito")));
        r.put("ventas_contado", d(v.get("total")).subtract(d(v.get("credito"))));
        r.put("anulado_total", d(a.get("total")));
        r.put("anul_previas_total", d(ap.get("total")));
        BigDecimal ef = BigDecimal.ZERO, ta = BigDecimal.ZERO, tr = BigDecimal.ZERO, ch = BigDecimal.ZERO, ot = BigDecimal.ZERO;
        for (Map<String, Object> p : tx.query("SELECT p.metodo, COALESCE(SUM(p.monto),0) AS m FROM pagos p JOIN facturas f ON f.id_factura = p.id_factura "
                + "WHERE f.id_usuario = ? AND f.id_corte IS NULL AND f.estado = 'EMITIDA' GROUP BY p.metodo", idUsuario)) {
            BigDecimal m = d(p.get("m"));
            switch (Db.str(p.get("metodo"))) {
                case "EFECTIVO" -> ef = ef.add(m);
                case "TARJETA" -> ta = ta.add(m);
                case "TRANSFERENCIA" -> tr = tr.add(m);
                case "CHEQUE" -> ch = ch.add(m);
                default -> ot = ot.add(m);
            }
        }
        r.put("pago_efectivo", ef);
        r.put("pago_tarjeta", ta);
        r.put("pago_transferencia", tr);
        r.put("pago_cheque", ch);
        r.put("pago_otro", ot);
        r.put("propinas", d(tx.scalar("SELECT COALESCE(SUM(c.propina),0) FROM comandas c JOIN facturas f ON f.id_factura = c.id_factura "
                + "WHERE f.id_usuario = ? AND f.id_corte IS NULL AND f.estado = 'EMITIDA'", idUsuario)));
        r.put("fondo_inicial", fondo);
        r.put("efectivo_esperado", fondo.add(ef));
        r.put("efectivo_contado", contado);
        r.put("diferencia", contado.subtract(fondo.add(ef)));
        return r;
    }

    public static Map<String, Object> vistaPreviaX(int idUsuario) {
        return Db.sin(tx -> calcularX(tx, idUsuario, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    private static int guardar(Tx tx, Map<String, Object> c, String notas) throws Exception {
        StringBuilder cols = new StringBuilder("tipo, id_usuario, desde_fecha, hasta_fecha, desde_factura, hasta_factura, notas");
        StringBuilder qs = new StringBuilder("?,?,?,NOW(),?,?,?");
        java.util.List<Object> vals = new java.util.ArrayList<>(List.of(c.get("tipo"), Sesion.idUsuario));
        vals.add(c.get("desde_fecha"));
        vals.add(c.get("desde_factura"));
        vals.add(c.get("hasta_factura"));
        vals.add(notas == null || notas.isBlank() ? null : notas.trim());
        for (String k : SUMAS) { cols.append(", ").append(k); qs.append(",?"); vals.add(c.get(k)); }
        return (int) tx.insert("INSERT INTO cortes (" + cols + ") VALUES (" + qs + ")", vals.toArray());
    }

    /** Corte X del cajero que está en sesión: guarda el corte y deja su caja en blanco. Devuelve el id del corte. */
    public static int hacerX(BigDecimal fondo, BigDecimal contado, String notas) {
        if (fondo.signum() < 0 || contado.signum() < 0) throw new IllegalArgumentException("Los montos no pueden ser negativos.");
        return Db.transaccion(tx -> {
            tx.query("SELECT id_usuario FROM usuarios WHERE id_usuario = ? FOR UPDATE", Sesion.idUsuario);   // evita dos cortes a la vez del mismo cajero
            Map<String, Object> c = calcularX(tx, Sesion.idUsuario, fondo, contado);
            if (Db.ent(c.get("n_facturas")) == 0 && Db.ent(c.get("n_anuladas")) == 0 && Db.ent(c.get("n_anul_previas")) == 0
                    && fondo.signum() == 0 && contado.signum() == 0)
                throw new IllegalArgumentException("No hay ventas ni movimientos desde el último corte.");
            int id = guardar(tx, c, notas);
            tx.exec("UPDATE facturas SET id_corte = ? WHERE id_usuario = ? AND id_corte IS NULL", id, Sesion.idUsuario);
            tx.exec("UPDATE facturas SET anulada_corte = ? WHERE estado = 'ANULADA' AND id_corte = ? AND anulada_corte IS NULL", id, id);
            tx.exec("UPDATE facturas SET anulada_corte = ? WHERE id_usuario_anula = ? AND estado = 'ANULADA' AND id_corte IS NOT NULL AND anulada_corte IS NULL", id, Sesion.idUsuario);
            Bitacora.registrar("CORTE_X", "cortes", id, "ventas " + c.get("total_ventas"));
            return id;
        });
    }

    /** Cuántos cortes X esperan un Z y qué cajeros tienen ventas sin cortar. */
    public static List<Map<String, Object>> pendientesSinCorte() {
        return Db.query("SELECT u.nombre, COUNT(*) AS n FROM facturas f LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario WHERE f.id_corte IS NULL GROUP BY u.nombre");
    }

    public static Map<String, Object> vistaPreviaZ() {
        return Db.sin(tx -> calcularZ(tx));
    }

    private static Map<String, Object> calcularZ(Tx tx) throws Exception {
        Map<String, Object> r = new LinkedHashMap<>();
        StringBuilder sel = new StringBuilder("COUNT(*) AS cuantos, MIN(desde_fecha) AS desde, MIN(desde_factura) AS ini, MAX(hasta_factura) AS fin");
        for (String k : SUMAS) sel.append(", COALESCE(SUM(").append(k).append("),0) AS ").append(k);
        Map<String, Object> s = tx.uno("SELECT " + sel + " FROM cortes WHERE tipo = 'X' AND id_z IS NULL");
        r.put("tipo", "Z");
        r.put("cortes_x", Db.ent(s.get("cuantos")));
        r.put("desde_fecha", s.get("desde"));
        r.put("desde_factura", s.get("ini"));
        r.put("hasta_factura", s.get("fin"));
        for (String k : SUMAS) r.put(k, s.get(k));
        r.put("mesas_abiertas", Db.ent(tx.scalar("SELECT COUNT(*) FROM comandas WHERE estado IN ('ABIERTA','CERRADA')")));
        return r;
    }

    /** Corte Z: cierra el día con la suma de todos los X pendientes. */
    public static int hacerZ(String notas) {
        return Db.transaccion(tx -> {
            tx.query("SELECT id_corte FROM cortes WHERE tipo = 'X' AND id_z IS NULL FOR UPDATE");
            Map<String, Object> c = calcularZ(tx);
            if (Db.ent(c.get("cortes_x")) == 0) throw new IllegalArgumentException("No hay cortes X pendientes: nada que cerrar.");
            StringBuilder pend = new StringBuilder();
            for (Map<String, Object> p : tx.query("SELECT u.nombre, COUNT(*) AS n FROM facturas f LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario WHERE f.id_corte IS NULL GROUP BY u.nombre"))
                pend.append("\n  • ").append(Db.str(p.get("nombre"))).append(": ").append(p.get("n")).append(" factura(s)");
            if (pend.length() > 0) throw new IllegalArgumentException("Antes del corte Z, estos cajeros deben hacer su corte X:" + pend);
            int id = guardar(tx, c, notas);
            tx.exec("UPDATE cortes SET id_z = ? WHERE tipo = 'X' AND id_z IS NULL AND id_corte <> ?", id, id);
            Bitacora.registrar("CORTE_Z", "cortes", id, "ventas " + c.get("total_ventas"));
            return id;
        });
    }

    public static Map<String, Object> obtener(int idCorte) {
        Map<String, Object> c = Db.uno("SELECT c.*, u.nombre AS cajero, DATE_FORMAT(c.creado_en, '%d/%m/%Y %H:%i') AS creado_txt, "
                + "DATE_FORMAT(c.desde_fecha, '%d/%m/%Y %H:%i') AS desde_txt, DATE_FORMAT(c.hasta_fecha, '%d/%m/%Y %H:%i') AS hasta_txt "
                + "FROM cortes c LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario WHERE c.id_corte = ?", idCorte);
        if (c == null) throw new IllegalArgumentException("El corte no existe.");
        return c;
    }

    public static List<Map<String, Object>> incluidos(int idZ) {
        return Db.query("SELECT c.id_corte, u.nombre AS cajero, DATE_FORMAT(c.creado_en, '%d/%m %H:%i') AS hora, c.total_ventas FROM cortes c "
                + "LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario WHERE c.id_z = ? ORDER BY c.id_corte", idZ);
    }
}
