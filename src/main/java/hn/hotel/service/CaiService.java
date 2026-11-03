package hn.hotel.service;

import hn.hotel.db.Db;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Control del CAI antes de vender: bloquea la facturación cuando el CAI del punto de venta venció o se agotó,
 * y avisa cuando está por vencer. La numeración real la asigna la base de datos (sp_siguiente_numero_factura).
 */
public final class CaiService {
    private CaiService() {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static List<Map<String, Object>> vigentes(Integer idPunto) {
        return Db.query("SELECT c.id_cai, c.cai, c.fecha_limite_emision, c.alerta_dias, c.alerta_numeros, (c.rango_final - c.siguiente_correlativo + 1) AS disponibles, "
                + "DATEDIFF(c.fecha_limite_emision, CURDATE()) AS dias FROM cai_autorizaciones c WHERE c.activo = 1 AND c.tipo_documento = '01' "
                + "AND c.siguiente_correlativo <= c.rango_final AND c.fecha_limite_emision >= CURDATE() AND (c.id_punto_venta = ? OR c.id_punto_venta IS NULL) "
                + "ORDER BY (c.id_punto_venta IS NULL), c.fecha_limite_emision, c.id_cai", idPunto);
    }

    private static String nombrePunto(Integer idPunto) {
        if (idPunto == null) return "esta computadora (sin punto de venta asignado)";
        Object n = Db.scalar("SELECT nombre FROM puntos_venta WHERE id_punto = ?", idPunto);
        return n == null ? "esta computadora" : "«" + n + "»";
    }

    /** null si se puede facturar; si no, el motivo del bloqueo en lenguaje claro. */
    public static String bloqueo(Integer idPunto) {
        if (!vigentes(idPunto).isEmpty()) return null;
        String donde = nombrePunto(idPunto);
        // ¿Hay algún CAI registrado para este punto (o general) y por qué no sirve?
        List<Map<String, Object>> todos = Db.query("SELECT cai, fecha_limite_emision, siguiente_correlativo > rango_final AS agotado, fecha_limite_emision < CURDATE() AS vencido, activo "
                + "FROM cai_autorizaciones WHERE tipo_documento = '01' AND (id_punto_venta = ? OR id_punto_venta IS NULL) ORDER BY fecha_limite_emision DESC, id_cai DESC", idPunto);
        if (todos.isEmpty())
            return "VENTA BLOQUEADA: no hay ningún CAI registrado para el punto de venta " + donde + ".\nRegistre el CAI en «CAI y libro de ventas».";
        for (Map<String, Object> c : todos) {
            if (Db.ent(c.get("activo")) == 0) continue;
            if (Db.ent(c.get("vencido")) == 1)
                return "VENTA BLOQUEADA: el CAI " + Db.str(c.get("cai")) + " del punto de venta " + donde + " venció el "
                        + ((java.sql.Date) c.get("fecha_limite_emision")).toLocalDate().format(FMT) + ".\nRegistre el nuevo CAI autorizado por el SAR para poder facturar.";
            if (Db.ent(c.get("agotado")) == 1)
                return "VENTA BLOQUEADA: se agotó el rango de números del CAI " + Db.str(c.get("cai")) + " (punto de venta " + donde + ").\nRegistre el nuevo CAI autorizado por el SAR.";
        }
        return "VENTA BLOQUEADA: los CAI del punto de venta " + donde + " están desactivados. Active uno o registre un CAI nuevo.";
    }

    /** Aviso (no bloquea) cuando el CAI en uso está por vencer o se está agotando; null si no hace falta. */
    public static String aviso(Integer idPunto) {
        List<Map<String, Object>> v = vigentes(idPunto);
        if (v.isEmpty()) return null;
        Map<String, Object> c = v.get(0);
        long dias = Db.lng(c.get("dias")), disp = Db.lng(c.get("disponibles"));
        StringBuilder sb = new StringBuilder();
        if (dias <= Db.lng(c.get("alerta_dias")))
            sb.append("El CAI ").append(Db.str(c.get("cai"))).append(" vence el ").append(((java.sql.Date) c.get("fecha_limite_emision")).toLocalDate().format(FMT))
              .append(dias == 0 ? " (HOY)." : " (faltan " + dias + " días).");
        if (disp <= Db.lng(c.get("alerta_numeros"))) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Quedan solo ").append(disp).append(" números en el CAI ").append(Db.str(c.get("cai"))).append('.');
        }
        return sb.length() == 0 ? null : sb.append("\nSolicite con tiempo la nueva autorización al SAR.").toString();
    }

    /** Para el tablero y las alarmas: lo que ya venció o se agotó. */
    public static boolean hayCaiVencido() {
        return Db.scalar("SELECT 1 FROM cai_autorizaciones WHERE activo = 1 AND (fecha_limite_emision < CURDATE() OR siguiente_correlativo > rango_final) LIMIT 1") != null;
    }

    public static LocalDate hoy() { return LocalDate.now(); }
}
