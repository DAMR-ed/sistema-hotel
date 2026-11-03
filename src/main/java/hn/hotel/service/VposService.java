package hn.hotel.service;

import hn.hotel.app.Config;
import hn.hotel.app.Sesion;
import hn.hotel.db.Db;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cobros con tarjeta por el vPOS del banco (PinPad). El sistema no toca el PinPad directamente:
 * le habla al "puente" que corre en la PC de caja (carpeta vpos-puente).
 * Cada operación queda registrada en vpos_transacciones aunque falle, para poder auditarla.
 */
public final class VposService {
    private VposService() {}

    public record Resultado(long idVpos, boolean aprobada, String rsp, String tarjeta, String autorizacion,
                            String lote, String referencia, String raw, BigDecimal monto, String mensaje) {}

    public record Cierre(long idCierre, boolean exito, int ventas, BigDecimal montoVentas, int anuladas, BigDecimal montoAnuladas, String raw) {}

    public record Pendientes(int ventas, BigDecimal montoVentas, int anuladas, BigDecimal montoAnuladas, int conError) {}

    // ------------------------------------------------------------------ conexión con el puente
    /** El PinPad se activa en Configuración (vpos_activo = SI). */
    public static boolean activo() { return "SI".equalsIgnoreCase(Config.texto("vpos_activo", "NO").trim()); }

    /** SIMULADOR (pruebas), LOCAL (el DLL está en esta misma PC con Windows) o PUENTE (el DLL está en otra PC de caja). */
    public static String modo() { return Config.texto("vpos_modo", "SIMULADOR").trim().toUpperCase(Locale.ROOT); }

    private static String url() {
        String u = Config.texto("vpos_url", "").trim();
        return u.isEmpty() ? null : (u.endsWith("/") ? u.substring(0, u.length() - 1) : u);
    }

    private static hn.hotel.vpos.Terminal terminalLocal;
    private static String terminalLocalClave = "";

    /** Terminal que corre dentro de este mismo programa (modos SIMULADOR y LOCAL). */
    private static synchronized hn.hotel.vpos.Terminal terminal() {
        String m = modo(), ruta = Config.texto("vpos_dll_ruta", "C:\\EDPvPOS\\EDPvPOSLink.dll").trim();
        String clave = m + "|" + ruta;
        if (terminalLocal != null && clave.equals(terminalLocalClave)) return terminalLocal;
        if (m.equals("SIMULADOR")) terminalLocal = new hn.hotel.vpos.Simulador();
        else {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                throw new IllegalStateException("El modo LOCAL solo funciona en Windows. En este equipo use el modo PUENTE (PC de caja) o SIMULADOR (pruebas).");
            if (!new java.io.File(ruta).isFile())
                throw new IllegalStateException("No se encontró el DLL del banco en: " + ruta + "\nRevise la ruta en Configuración (vpos_dll_ruta).");
            try { terminalLocal = new hn.hotel.vpos.DllEdp(ruta); }
            catch (Throwable e) {
                throw new IllegalStateException("No se pudo cargar el DLL (" + ruta + "): " + e.getMessage()
                        + "\nSi el DLL es de 32 bits, el sistema debe correr con Java de 32 bits.");
            }
        }
        terminalLocalClave = clave;
        return terminalLocal;
    }

    /** Respuesta del puente: ok indica si la función del DLL devolvió verdadero; salida es el texto del DLL. */
    record Respuesta(boolean ok, String salida) {}

    private static Respuesta llamar(String ruta, String cuerpo, int segundos) {
        if (!activo()) throw new IllegalStateException("El cobro con PinPad está desactivado. Actívelo en Configuración (vpos_activo = SI).");
        String modo = modo();
        if (!modo.equals("PUENTE")) {
            hn.hotel.vpos.Terminal t = terminal();
            if (ruta.equals("/estado")) return new Respuesta(true, t.nombre() + " (modo " + modo + ")");
            if (ruta.equals("/reporte")) return new Respuesta(t.imprimirReporte(cuerpo), "");
            hn.hotel.vpos.Terminal.Resultado r = t.autorizar(cuerpo);
            return new Respuesta(r.ok(), r.salida());
        }
        String base = url();
        if (base == null) throw new IllegalStateException("Falta la dirección del puente. Escríbala en Configuración (vpos_url).");
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).timeout(Duration.ofSeconds(segundos))
                    .header("X-Token", Config.texto("vpos_token", ""));
            HttpRequest rq = cuerpo == null ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build();
            HttpResponse<String> r = c.send(rq, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401) throw new IllegalStateException("El puente rechazó la clave (vpos_token no coincide).");
            if (r.statusCode() != 200) throw new IllegalStateException("El puente respondió con error: " + r.body());
            String t = r.body();
            int i = t.indexOf('|');
            return new Respuesta(t.startsWith("OK"), i < 0 ? t : t.substring(i + 1));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IllegalStateException("TIEMPO_AGOTADO");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo contactar con el puente vPOS en " + base + ". ¿Está encendida la PC de caja y abierto el puente?");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Operación interrumpida.");
        }
    }

    /** Prueba de conexión: devuelve el modo del puente ("DLL EDPvPOSLink" o "SIMULADOR"). */
    public static String probar() { return llamar("/estado", null, 5).salida(); }

    /** Convierte "RSP=00;CRD=VISA;AUT=123;" en un mapa clave→valor (igual que el Split de VB.NET). */
    static Map<String, String> parsear(String salida) {
        Map<String, String> m = new LinkedHashMap<>();
        if (salida == null) return m;
        for (String par : salida.split(";")) {
            int i = par.indexOf('=');
            if (i > 0) m.put(par.substring(0, i).trim(), par.substring(i + 1).trim());
        }
        return m;
    }

    private static String dinero(BigDecimal b) { return String.format(Locale.US, "%.2f", b); }

    private static String recortar(String s, int max) { return s == null ? null : (s.length() > max ? s.substring(0, max) : s); }

    // ------------------------------------------------------------------ cobrar (total o parcial)
    /**
     * Cobra "montoTotal" (lo que paga el cliente, con ISV) en el PinPad. Puede ser un pago parcial.
     * El ISV se reparte en proporción: isvCuenta * montoTotal / totalCuenta.
     */
    public static Resultado cobrar(BigDecimal montoTotal, BigDecimal totalCuenta, BigDecimal isvCuenta,
                                   String origen, Integer idComanda, Integer idEstancia) {
        if (montoTotal == null || montoTotal.signum() <= 0) throw new IllegalArgumentException("El monto a cobrar debe ser mayor que cero.");
        if (totalCuenta != null && montoTotal.compareTo(totalCuenta) > 0) throw new IllegalArgumentException("No puede cobrar más que el total de la cuenta.");
        if (!RestauranteService.puedeCobrar()) throw new IllegalArgumentException("Su usuario no puede cobrar con tarjeta.");
        final BigDecimal monto = montoTotal.setScale(2, RoundingMode.HALF_UP);

        BigDecimal tax = BigDecimal.ZERO;
        if (totalCuenta != null && totalCuenta.signum() > 0 && isvCuenta != null)
            tax = isvCuenta.multiply(monto).divide(totalCuenta, 2, RoundingMode.HALF_UP);
        boolean conIsv = "CON_ISV".equalsIgnoreCase(Config.texto("vpos_modo_tot", "SIN_ISV"));
        BigDecimal tot = conIsv ? monto : monto.subtract(tax);

        long id = Db.insert("INSERT INTO vpos_transacciones (tipo, id_usuario, origen, id_comanda, id_estancia, monto_total, monto_tot, monto_tax, estado) "
                + "VALUES ('VENTA',?,?,?,?,?,?,?, 'PENDIENTE')", Sesion.idUsuario, origen, idComanda, idEstancia, monto, tot, tax);
        String entrada = "PID=" + Config.texto("vpos_pid_venta", "VPSALE0002") + ";TAX=" + dinero(tax) + ";TOT=" + dinero(tot) + ";";
        Respuesta r;
        try {
            r = llamar("/operar", entrada, Config.entero("vpos_timeout_seg", 150));
        } catch (IllegalStateException e) {
            String msg = "TIEMPO_AGOTADO".equals(e.getMessage())
                    ? "El PinPad no respondió a tiempo. Revise en el PinPad si el cobro SÍ se hizo antes de intentar de nuevo." : e.getMessage();
            Db.exec("UPDATE vpos_transacciones SET estado = 'ERROR', detalle_error = ? WHERE id_vpos = ?", recortar(msg, 250), id);
            throw new IllegalStateException(msg);
        }
        Map<String, String> c = parsear(r.salida());
        String rsp = c.get("RSP");
        boolean aprobada = r.ok() && "00".equals(rsp);
        Db.exec("UPDATE vpos_transacciones SET rsp = ?, tarjeta = ?, autorizacion = ?, fecha_lote = ?, lote = ?, hora_lote = ?, referencia = ?, "
                + "estado = ?, respuesta_raw = ?, detalle_error = ? WHERE id_vpos = ?",
                rsp, c.get("CRD"), c.get("AUT"), c.get("LTD"), c.get("BAT"), c.get("LTT"), c.get("SYS"),
                aprobada ? "APROBADA" : "RECHAZADA", recortar(r.salida(), 490),
                aprobada ? null : recortar(c.getOrDefault("MSG", "Respuesta " + rsp), 250), id);
        Bitacora.registrar(aprobada ? "VPOS_COBRO" : "VPOS_RECHAZO", "vpos_transacciones", id, dinero(monto) + " " + (rsp == null ? "" : rsp));
        String msg = aprobada ? "Aprobada" : c.getOrDefault("MSG", r.ok() ? "Rechazada (código " + rsp + ")" : "El PinPad no pudo completar la operación");
        return new Resultado(id, aprobada, rsp, c.get("CRD"), c.get("AUT"), c.get("BAT"), c.get("SYS"), r.salida(), monto, msg);
    }

    /** Cuando se emite la factura, los cobros con tarjeta aprobados de esa cuenta quedan ligados a ella. */
    public static void vincularFactura(List<Long> ids, long idFactura) {
        for (long id : ids) Db.exec("UPDATE vpos_transacciones SET id_factura = ? WHERE id_vpos = ?", idFactura, id);
    }

    // ------------------------------------------------------------------ anulación
    private static void exigirCajero() {
        if (!(Sesion.esAdmin() || "SUPERVISOR".equals(Sesion.rol) || "CAJERO".equals(Sesion.rol)))
            throw new IllegalArgumentException("Solo un cajero o supervisor puede anular cobros con tarjeta.");
    }

    /** Registra la intención de anular (idOriginal puede ser null si el cajero anula algo no registrado aquí). */
    public static long iniciarAnulacion(Long idOriginal) {
        exigirCajero();
        return Db.insert("INSERT INTO vpos_transacciones (tipo, id_usuario, id_original, estado) VALUES ('ANULACION', ?, ?, 'PENDIENTE')", Sesion.idUsuario, idOriginal);
    }

    /** Abre la pantalla de anulación del PinPad (el cajero digita ahí la referencia). Devuelve el texto del DLL. */
    public static String ejecutarAnulacion(long idAnulacion) {
        Respuesta r;
        try { r = llamar("/operar", "PID=" + Config.texto("vpos_pid_anulacion", "VPREVER002") + ";", Config.entero("vpos_timeout_seg", 150)); }
        catch (IllegalStateException e) {
            Db.exec("UPDATE vpos_transacciones SET estado = 'ERROR', detalle_error = ? WHERE id_vpos = ?", recortar(e.getMessage(), 250), idAnulacion);
            throw e;
        }
        Db.exec("UPDATE vpos_transacciones SET respuesta_raw = ?, rsp = ? WHERE id_vpos = ?", recortar(r.salida(), 490), parsear(r.salida()).get("RSP"), idAnulacion);
        return (r.ok() ? "OK|" : "FAIL|") + r.salida();
    }

    /** El cajero confirma (viendo el PinPad) si la anulación se realizó. */
    public static void confirmarAnulacion(long idAnulacion, boolean seAnulo) {
        Db.transaccion(tx -> {
            Map<String, Object> a = tx.uno("SELECT id_original FROM vpos_transacciones WHERE id_vpos = ? AND tipo = 'ANULACION' FOR UPDATE", idAnulacion);
            if (a == null) throw new IllegalArgumentException("La anulación no existe.");
            if (!seAnulo) {
                tx.exec("UPDATE vpos_transacciones SET estado = 'RECHAZADA', detalle_error = 'El cajero indicó que el PinPad no anuló' WHERE id_vpos = ?", idAnulacion);
                return null;
            }
            tx.exec("UPDATE vpos_transacciones SET estado = 'APROBADA' WHERE id_vpos = ?", idAnulacion);
            if (a.get("id_original") != null)
                tx.exec("UPDATE vpos_transacciones SET estado = 'ANULADA' WHERE id_vpos = ? AND tipo = 'VENTA'", a.get("id_original"));
            Bitacora.registrar("VPOS_ANULACION", "vpos_transacciones", idAnulacion, "original " + a.get("id_original"));
            return null;
        });
    }

    // ------------------------------------------------------------------ cierre de lote
    public static Pendientes pendientesDeCierre() {
        Map<String, Object> r = Db.uno("SELECT "
                + "COALESCE(SUM(tipo = 'VENTA' AND estado = 'APROBADA'),0) AS nv, COALESCE(SUM(CASE WHEN tipo = 'VENTA' AND estado = 'APROBADA' THEN monto_total END),0) AS mv, "
                + "COALESCE(SUM(tipo = 'VENTA' AND estado = 'ANULADA'),0) AS na, COALESCE(SUM(CASE WHEN tipo = 'VENTA' AND estado = 'ANULADA' THEN monto_total END),0) AS ma, "
                + "COALESCE(SUM(estado IN ('ERROR','PENDIENTE')),0) AS ne FROM vpos_transacciones WHERE id_cierre IS NULL");
        return new Pendientes(Db.ent(r.get("nv")), Db.dec(r.get("mv")), Db.ent(r.get("na")), Db.dec(r.get("ma")), Db.ent(r.get("ne")));
    }

    /** Cierre de lote (settlement). Si el banco lo acepta, las operaciones pendientes quedan ligadas a ese cierre. */
    public static Cierre cerrar() {
        exigirCajero();
        Pendientes p = pendientesDeCierre();
        Respuesta r = llamar("/operar", "PID=" + Config.texto("vpos_pid_cierre", "VPSETTL001") + ";", Config.entero("vpos_timeout_seg", 150));
        Map<String, String> c = parsear(r.salida());
        boolean exito = r.ok() && (c.get("RSP") == null || "00".equals(c.get("RSP")));
        long id = Db.transaccion(tx -> {
            long idc = tx.insert("INSERT INTO vpos_cierres (id_usuario, num_ventas, monto_ventas, num_anuladas, monto_anuladas, exito, respuesta_raw) VALUES (?,?,?,?,?,?,?)",
                    Sesion.idUsuario, p.ventas(), p.montoVentas(), p.anuladas(), p.montoAnuladas(), exito ? 1 : 0, recortar(r.salida(), 490));
            if (exito)
                tx.exec("UPDATE vpos_transacciones SET id_cierre = ? WHERE id_cierre IS NULL AND estado IN ('APROBADA','ANULADA','RECHAZADA')", idc);
            Bitacora.registrar("VPOS_CIERRE", "vpos_cierres", idc, exito ? "exitoso" : "fallido");
            return idc;
        });
        return new Cierre(id, exito, p.ventas(), p.montoVentas(), p.anuladas(), p.montoAnuladas(), r.salida());
    }

    /** Pide al PinPad que imprima su reporte: "DET" (detallado) o "TOT" (totales). */
    public static boolean reporteTerminal(String tipo) {
        if (!tipo.equals("DET") && !tipo.equals("TOT")) throw new IllegalArgumentException("Reporte no válido.");
        return llamar("/reporte", "REP=" + tipo, 60).ok();
    }

    // ------------------------------------------------------------------ consultas para reportes
    public static List<Map<String, Object>> operaciones(LocalDate desde, LocalDate hasta, String estado, String tipo) {
        return Db.query("SELECT v.id_vpos, DATE_FORMAT(v.fecha, '%d/%m/%Y %H:%i') AS fecha, v.tipo, v.origen, "
                + "COALESCE(CONCAT('Comanda #', v.id_comanda), CONCAT('Estancia #', v.id_estancia), '') AS origen_ref, "
                + "COALESCE(f.numero_factura, '') AS factura, v.monto_total, COALESCE(v.tarjeta, '') AS tarjeta, COALESCE(v.autorizacion, '') AS autorizacion, "
                + "COALESCE(v.lote, '') AS lote, v.estado, COALESCE(u.nombre, '') AS cajero, COALESCE(v.id_cierre, '') AS id_cierre, COALESCE(v.detalle_error, '') AS detalle "
                + "FROM vpos_transacciones v LEFT JOIN facturas f ON f.id_factura = v.id_factura LEFT JOIN usuarios u ON u.id_usuario = v.id_usuario "
                + "WHERE DATE(v.fecha) BETWEEN ? AND ? AND (? = 'TODOS' OR v.estado = ?) AND (? = 'TODOS' OR v.tipo = ?) ORDER BY v.id_vpos DESC",
                java.sql.Date.valueOf(desde), java.sql.Date.valueOf(hasta), estado, estado, tipo, tipo);
    }

    public static List<Map<String, Object>> resumenPorTarjeta(LocalDate desde, LocalDate hasta) {
        return Db.query("SELECT COALESCE(NULLIF(SUBSTRING_INDEX(tarjeta, ' ', 1), ''), '(sin dato)') AS marca, COUNT(*) AS operaciones, SUM(monto_total) AS monto "
                + "FROM vpos_transacciones WHERE tipo = 'VENTA' AND estado = 'APROBADA' AND DATE(fecha) BETWEEN ? AND ? GROUP BY marca ORDER BY monto DESC",
                java.sql.Date.valueOf(desde), java.sql.Date.valueOf(hasta));
    }

    public static List<Map<String, Object>> resumenPorCajero(LocalDate desde, LocalDate hasta) {
        return Db.query("SELECT COALESCE(u.nombre, '(sin usuario)') AS cajero, COUNT(*) AS operaciones, SUM(v.monto_total) AS monto FROM vpos_transacciones v "
                + "LEFT JOIN usuarios u ON u.id_usuario = v.id_usuario WHERE v.tipo = 'VENTA' AND v.estado = 'APROBADA' AND DATE(v.fecha) BETWEEN ? AND ? "
                + "GROUP BY cajero ORDER BY monto DESC", java.sql.Date.valueOf(desde), java.sql.Date.valueOf(hasta));
    }

    public static List<Map<String, Object>> cierres(LocalDate desde, LocalDate hasta) {
        return Db.query("SELECT c.id_cierre, DATE_FORMAT(c.fecha, '%d/%m/%Y %H:%i') AS fecha, COALESCE(u.nombre,'') AS cajero, c.num_ventas, c.monto_ventas, "
                + "c.num_anuladas, c.monto_anuladas, IF(c.exito = 1, 'EXITOSO', 'FALLIDO') AS resultado FROM vpos_cierres c LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario "
                + "WHERE DATE(c.fecha) BETWEEN ? AND ? ORDER BY c.id_cierre DESC", java.sql.Date.valueOf(desde), java.sql.Date.valueOf(hasta));
    }
}
