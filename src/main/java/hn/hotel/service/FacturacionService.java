package hn.hotel.service;

import hn.hotel.app.Config;
import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.db.Tx;
import hn.hotel.ui.Ui;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Facturación: arma las líneas de la cuenta, calcula descuento, ISV y tasa turística,
 * y emite la factura (con número del CAI) en una sola transacción.
 *
 * LOS PORCENTAJES VIENEN DE LA TABLA "configuracion" y deben validarse con un contador / el SAR.
 */
public final class FacturacionService {
    private FacturacionService() {}

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /** Una línea antes de impuestos. */
    public record Linea(String tipo, String descripcion, BigDecimal cantidad, BigDecimal precio, String tratamiento,
                        boolean aplicaDescuento, boolean alojamiento, BigDecimal descuentoFijo, Integer idConsumo, BigDecimal tasaIsv) {
        public Linea(String tipo, String descripcion, BigDecimal cantidad, BigDecimal precio, String tratamiento,
                     boolean aplicaDescuento, boolean alojamiento, BigDecimal descuentoFijo, Integer idConsumo) {
            this(tipo, descripcion, cantidad, precio, tratamiento, aplicaDescuento, alojamiento, descuentoFijo, idConsumo, null);
        }
        public Linea(String tipo, String descripcion, BigDecimal cantidad, BigDecimal precio, String tratamiento,
                     boolean aplicaDescuento, boolean alojamiento) {
            this(tipo, descripcion, cantidad, precio, tratamiento, aplicaDescuento, alojamiento, BigDecimal.ZERO, null, null);
        }
    }

    /** Línea ya calculada, tal como se guarda en factura_detalle. */
    public record Detalle(String tipo, String descripcion, BigDecimal cantidad, BigDecimal precio, BigDecimal descuento,
                          String tratamiento, BigDecimal tasaIsv, BigDecimal subtotal, BigDecimal isv) {}

    public static final class Totales {
        public final List<Detalle> detalles = new ArrayList<>();
        public BigDecimal gravado15 = BigDecimal.ZERO, gravado18 = BigDecimal.ZERO, isv18 = BigDecimal.ZERO, exento = BigDecimal.ZERO, exonerado = BigDecimal.ZERO,
                descuento = BigDecimal.ZERO, isv15 = BigDecimal.ZERO, tasaTuristica = BigDecimal.ZERO, total = BigDecimal.ZERO;
        public BigDecimal pctDescuento = BigDecimal.ZERO;
    }

    public record Pago(String metodo, BigDecimal monto, String referencia) {}

    public record Opciones(Integer idHuesped, String nombre, String rtn, boolean credito, Integer idClienteCredito,
                           List<Pago> pagos, String constanciaExoneracion, boolean aplicarDescuentoAdultoMayor) {}

    // ------------------------------------------------------------------ descuento adulto mayor
    /** Porcentaje que le corresponde al huésped por edad (0 si no aplica). Solo residentes con fecha de nacimiento. */
    public static BigDecimal porcentajeAdultoMayor(Tx tx, Integer idHuesped, LocalDate hoy) throws Exception {
        if (idHuesped == null) return BigDecimal.ZERO;
        Map<String, Object> h = tx.uno("SELECT fecha_nacimiento, es_residente FROM huespedes WHERE id_huesped = ?", idHuesped);
        if (h == null || h.get("fecha_nacimiento") == null || Db.ent(h.get("es_residente")) == 0) return BigDecimal.ZERO;
        int edad = Period.between(Ui.fecha(h.get("fecha_nacimiento")), hoy).getYears();
        if (edad >= configEntero(tx, "edad_cuarta", 80)) return configNum(tx, "desc_cuarta_edad", "35");
        if (edad >= configEntero(tx, "edad_tercera", 60)) return configNum(tx, "desc_tercera_edad", "25");
        return BigDecimal.ZERO;
    }

    private static BigDecimal configNum(Tx tx, String clave, String def) throws Exception {
        Object v = tx.scalar("SELECT valor FROM configuracion WHERE clave = ?", clave);
        try { return new BigDecimal(v == null ? def : v.toString().trim()); } catch (NumberFormatException e) { return new BigDecimal(def); }
    }

    private static int configEntero(Tx tx, String clave, int def) throws Exception {
        return configNum(tx, clave, String.valueOf(def)).intValue();
    }

    private static String configTexto(Tx tx, String clave, String def) throws Exception {
        Object v = tx.scalar("SELECT valor FROM configuracion WHERE clave = ?", clave);
        return v == null ? def : v.toString().trim();
    }

    // ------------------------------------------------------------------ cálculo
    private static BigDecimal redondear(BigDecimal b) { return b.setScale(2, RoundingMode.HALF_UP); }

    /**
     * Calcula descuento, ISV y tasa turística.
     * @param pctDescuento porcentaje de adulto mayor ya decidido (0 si no aplica)
     * @param descuentoSoloAlojamiento true si el descuento solo aplica al hospedaje
     */
    public static Totales calcular(List<Linea> lineas, BigDecimal pctDescuento, boolean exonerado,
                                   BigDecimal pctIsv, BigDecimal pctTasa, boolean tasaIncluyeRecargo) {
        Totales t = new Totales();
        t.pctDescuento = pctDescuento;
        BigDecimal baseTasa = BigDecimal.ZERO;
        for (Linea l : lineas) {
            BigDecimal bruto = redondear(l.cantidad().multiply(l.precio()));
            BigDecimal desc = l.descuentoFijo() == null ? BigDecimal.ZERO : redondear(l.descuentoFijo());
            BigDecimal tras = bruto.subtract(desc);
            if (l.aplicaDescuento() && pctDescuento.signum() > 0)
                desc = desc.add(redondear(tras.multiply(pctDescuento).divide(CIEN, 4, RoundingMode.HALF_UP)));
            BigDecimal neto = bruto.subtract(desc);
            if (neto.signum() < 0) neto = BigDecimal.ZERO;

            String trat = l.tratamiento();
            if (exonerado && trat.equals("GRAVADO")) trat = "EXONERADO";
            BigDecimal isv = BigDecimal.ZERO, tasaIsv = BigDecimal.ZERO;
            switch (trat) {
                case "GRAVADO" -> {
                    BigDecimal tasa = l.tasaIsv() == null ? pctIsv : l.tasaIsv();
                    if (tasa.signum() == 0) { trat = "EXENTO"; t.exento = t.exento.add(neto); }
                    else {
                        tasaIsv = tasa;
                        isv = redondear(neto.multiply(tasa).divide(CIEN, 4, RoundingMode.HALF_UP));
                        if (tasa.compareTo(pctIsv) == 0) { t.gravado15 = t.gravado15.add(neto); t.isv15 = t.isv15.add(isv); }
                        else { t.gravado18 = t.gravado18.add(neto); t.isv18 = t.isv18.add(isv); }   // tasa distinta de la general (18 %, selectivo)
                    }
                }
                case "EXENTO" -> t.exento = t.exento.add(neto);
                default -> t.exonerado = t.exonerado.add(neto);
            }
            t.descuento = t.descuento.add(desc);
            if (l.alojamiento() && (l.tipo().equals("HOSPEDAJE") || tasaIncluyeRecargo)) baseTasa = baseTasa.add(neto);
            // el precio guardado en el detalle es el unitario; el descuento queda aparte para que se vea
            t.detalles.add(new Detalle(l.tipo(), l.descripcion(), l.cantidad(), l.precio(), desc, trat, tasaIsv, neto, isv));
        }
        if (pctTasa.signum() > 0 && baseTasa.signum() > 0) {
            t.tasaTuristica = redondear(baseTasa.multiply(pctTasa).divide(CIEN, 4, RoundingMode.HALF_UP));
            t.detalles.add(new Detalle("TASA_TURISTICA", "Tasa turística " + pctTasa.stripTrailingZeros().toPlainString() + " %",
                    BigDecimal.ONE, t.tasaTuristica, BigDecimal.ZERO, "EXENTO", BigDecimal.ZERO, t.tasaTuristica, BigDecimal.ZERO));
        }
        t.total = t.gravado15.add(t.gravado18).add(t.exento).add(t.exonerado).add(t.isv15).add(t.isv18).add(t.tasaTuristica);
        return t;
    }

    public static Totales calcularConConfig(Tx tx, List<Linea> lineas, BigDecimal pctDescuento, boolean exonerado) throws Exception {
        return calcular(lineas, pctDescuento, exonerado, configNum(tx, "isv_general", "15"), configNum(tx, "tasa_turistica", "4"),
                !"NO".equalsIgnoreCase(configTexto(tx, "tasa_turistica_incluye_recargo", "SI")));
    }

    // ------------------------------------------------------------------ armar cuentas
    public record CuentaEstancia(int idEstancia, int idHuesped, String huesped, String rtnHuesped, int habitacion, LocalDate ingreso,
                                 long noches, BigDecimal anticipo, List<Linea> lineas, Integer idClienteCreditoReserva) {}

    public static CuentaEstancia cuentaEstancia(Tx tx, int idEstancia, boolean bloquear) throws Exception {
        Map<String, Object> e = tx.uno("SELECT e.*, h.numero, t.capacidad, hu.nombre, hu.rtn, r.anticipo, r.id_cliente_credito "
                + "FROM estancias e JOIN habitaciones h ON h.id_habitacion = e.id_habitacion "
                + "JOIN tipos_habitacion t ON t.id_tipo = h.id_tipo JOIN huespedes hu ON hu.id_huesped = e.id_huesped "
                + "LEFT JOIN reservas r ON r.id_reserva = e.id_reserva WHERE e.id_estancia = ?" + (bloquear ? " FOR UPDATE" : ""), idEstancia);
        if (e == null) throw new IllegalArgumentException("La estadía no existe.");
        if (!"ACTIVA".equals(Db.str(e.get("estado")))) throw new IllegalArgumentException("La estadía ya fue cerrada.");
        LocalDate ingreso = Ui.fecha(e.get("checkin_real"));
        long noches = Math.max(1, ChronoUnit.DAYS.between(ingreso, LocalDate.now()));
        List<Linea> ls = new ArrayList<>();
        BigDecimal precio = Db.dec(e.get("precio_noche"));
        ls.add(new Linea("HOSPEDAJE", "Hospedaje hab. " + e.get("numero") + " (" + noches + " noche" + (noches == 1 ? "" : "s") + ")",
                BigDecimal.valueOf(noches), precio, "GRAVADO", true, true));
        int extra = Math.max(0, Db.ent(e.get("num_personas")) - Db.ent(e.get("capacidad")));
        BigDecimal recargo = configNum(tx, "recargo_persona_extra", "0");
        if (extra > 0 && recargo.signum() > 0)
            ls.add(new Linea("RECARGO", "Recargo por " + extra + " persona(s) extra", BigDecimal.valueOf(extra * noches), recargo, "GRAVADO", true, true));
        boolean descTodo = "TODO".equalsIgnoreCase(configTexto(tx, "desc_adulto_mayor_aplica", "HOSPEDAJE"));
        for (Map<String, Object> c : tx.query("SELECT id_consumo, descripcion, cantidad, precio_unitario, tratamiento_fiscal, tasa_isv, id_comanda FROM consumos "
                + "WHERE id_estancia = ? AND facturado = 0 ORDER BY fecha" + (bloquear ? " FOR UPDATE" : ""), idEstancia)) {
            ls.add(new Linea(c.get("id_comanda") != null ? "ALIMENTOS" : "SERVICIO", Db.str(c.get("descripcion")), Db.dec(c.get("cantidad")),
                    Db.dec(c.get("precio_unitario")), Db.str(c.get("tratamiento_fiscal")), descTodo, false, BigDecimal.ZERO, Db.ent(c.get("id_consumo")),
                    c.get("tasa_isv") == null ? null : Db.dec(c.get("tasa_isv"))));
        }
        return new CuentaEstancia(idEstancia, Db.ent(e.get("id_huesped")), Db.str(e.get("nombre")), Db.str(e.get("rtn")), Db.ent(e.get("numero")),
                ingreso, noches, e.get("anticipo") == null ? BigDecimal.ZERO : Db.dec(e.get("anticipo")), ls,
                e.get("id_cliente_credito") == null ? null : Db.ent(e.get("id_cliente_credito")));
    }

    public record CuentaComanda(int idComanda, String donde, List<Linea> lineas, BigDecimal propina) {}

    public static CuentaComanda cuentaComanda(Tx tx, int idComanda, boolean bloquear) throws Exception {
        return cuentaComanda(tx, idComanda, bloquear, null);
    }

    /** numCuenta == null: todo lo que aún no se ha cobrado; si no, solo esa subcuenta. */
    public static CuentaComanda cuentaComanda(Tx tx, int idComanda, boolean bloquear, Integer numCuenta) throws Exception {
        Map<String, Object> c = tx.uno("SELECT c.estado, c.propina, c.tipo, m.nombre AS mesa FROM comandas c LEFT JOIN mesas m ON m.id_mesa = c.id_mesa WHERE c.id_comanda = ?"
                + (bloquear ? " FOR UPDATE" : ""), idComanda);
        if (c == null) throw new IllegalArgumentException("La comanda no existe.");
        String est = Db.str(c.get("estado"));
        if (!est.equals("ABIERTA") && !est.equals("CERRADA")) throw new IllegalArgumentException("La comanda ya está " + est + ".");
        boolean descTodo = "TODO".equalsIgnoreCase(configTexto(tx, "desc_adulto_mayor_aplica", "HOSPEDAJE"));
        List<Linea> ls = new ArrayList<>();
        String filtro = numCuenta == null
                ? "AND num_cuenta NOT IN (SELECT num_cuenta FROM comanda_cuentas WHERE id_comanda = ? AND id_factura IS NOT NULL) "
                : "AND num_cuenta = ? ";
        if (numCuenta != null && tx.scalar("SELECT 1 FROM comanda_cuentas WHERE id_comanda = ? AND num_cuenta = ? AND id_factura IS NOT NULL", idComanda, numCuenta) != null)
            throw new IllegalArgumentException("La cuenta " + numCuenta + " ya fue cobrada.");
        for (Map<String, Object> d : tx.query("SELECT descripcion, cantidad, precio_unitario, descuento, tratamiento_fiscal, tasa_isv, tipo_linea FROM comanda_detalle "
                + "WHERE id_comanda = ? AND estado <> 'ANULADO' " + filtro + "ORDER BY id_detalle", idComanda, numCuenta == null ? idComanda : numCuenta))
            ls.add(new Linea(Db.str(d.get("tipo_linea")), Db.str(d.get("descripcion")), Db.dec(d.get("cantidad")), Db.dec(d.get("precio_unitario")),
                    Db.str(d.get("tratamiento_fiscal")), descTodo, false, Db.dec(d.get("descuento")), null,
                    d.get("tasa_isv") == null ? null : Db.dec(d.get("tasa_isv"))));
        if (ls.isEmpty()) throw new IllegalArgumentException("La comanda no tiene platos para facturar.");
        String donde = c.get("mesa") != null ? Db.str(c.get("mesa")) : (Db.str(c.get("tipo")).equals("LLEVAR") ? "Para llevar" : "Habitación");
        return new CuentaComanda(idComanda, donde, ls, Db.dec(c.get("propina")));
    }

    // ------------------------------------------------------------------ emitir
    private static long emitir(Tx tx, Totales t, Opciones o, String origen, Integer idEstancia, BigDecimal anticipoAplicable) throws Exception {
        String nombre = o.nombre() == null || o.nombre().isBlank() ? "Consumidor Final" : o.nombre().trim();
        String rtn = o.rtn() == null || o.rtn().isBlank() ? null : o.rtn().trim();
        if (rtn != null && !rtn.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
        if (t.total.signum() <= 0) throw new IllegalArgumentException("El total de la factura es cero; no hay nada que facturar.");

        java.sql.Date vence = null;
        Integer credito = null;
        if (o.credito()) {
            if (o.idClienteCredito() == null) throw new IllegalArgumentException("Elija el cliente de crédito.");
            Map<String, Object> cc = tx.uno("SELECT dias_credito FROM clientes_credito WHERE id_cliente_credito = ? AND activo = 1", o.idClienteCredito());
            if (cc == null) throw new IllegalArgumentException("El cliente de crédito no existe o está inactivo.");
            credito = o.idClienteCredito();
            vence = java.sql.Date.valueOf(LocalDate.now().plusDays(Db.ent(cc.get("dias_credito"))));
        } else {
            BigDecimal pagado = o.pagos().stream().map(Pago::monto).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal falta = t.total.subtract(anticipoAplicable);
            if (pagado.compareTo(falta) < 0)
                throw new IllegalArgumentException("El pago (" + Ui.lempiras(pagado) + ") no cubre el total pendiente (" + Ui.lempiras(falta) + ").");
        }

        tx.call("{CALL sp_siguiente_numero_factura(?, @p_cai, @p_num)}", "01");
        Map<String, Object> num = tx.uno("SELECT @p_cai AS cai, @p_num AS numero");
        long idFactura = tx.insert("INSERT INTO facturas (id_cai, numero_factura, id_estancia, id_huesped, cliente_nombre, cliente_rtn, "
                + "importe_gravado_15, importe_gravado_18, importe_exento, importe_exonerado, descuento_total, isv_15, isv_18, tasa_turistica, total, "
                + "constancia_exoneracion, origen, condicion_pago, id_cliente_credito, fecha_vencimiento, id_usuario) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                num.get("cai"), num.get("numero"), idEstancia, o.idHuesped(), nombre, rtn,
                t.gravado15, t.gravado18, t.exento, t.exonerado, t.descuento, t.isv15, t.isv18, t.tasaTuristica, t.total,
                o.constanciaExoneracion() == null || o.constanciaExoneracion().isBlank() ? null : o.constanciaExoneracion().trim(),
                origen, o.credito() ? "CREDITO" : "CONTADO", credito, vence, Sesion.idUsuario);
        for (Detalle d : t.detalles)
            tx.exec("INSERT INTO factura_detalle (id_factura, tipo, descripcion, cantidad, precio_unitario, descuento, tratamiento, tasa_isv, subtotal, isv) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    idFactura, d.tipo(), d.descripcion().length() > 150 ? d.descripcion().substring(0, 150) : d.descripcion(), d.cantidad(), d.precio(),
                    d.descuento(), d.tratamiento(), d.tasaIsv(), d.subtotal(), d.isv());

        if (o.credito()) {
            if (anticipoAplicable.signum() > 0) {
                Object idCxc = tx.scalar("SELECT id_cxc FROM cuentas_por_cobrar WHERE id_factura = ?", idFactura);
                tx.exec("INSERT INTO abonos (id_cxc, metodo, monto, referencia, id_usuario) VALUES (?,'OTRO',?,?,?)", idCxc, anticipoAplicable, "Anticipo de reserva", Sesion.idUsuario);
            }
        } else {
            if (anticipoAplicable.signum() > 0)
                tx.exec("INSERT INTO pagos (id_factura, metodo, monto, referencia, id_usuario) VALUES (?,'OTRO',?,?,?)", idFactura, anticipoAplicable, "Anticipo de reserva", Sesion.idUsuario);
            BigDecimal restante = t.total.subtract(anticipoAplicable);
            // si pagaron de más en efectivo, se registra solo lo que corresponde (el resto es el cambio)
            for (Pago p : o.pagos()) {
                if (restante.signum() <= 0) break;
                BigDecimal monto = p.monto().min(restante);
                if (monto.signum() <= 0) continue;
                tx.exec("INSERT INTO pagos (id_factura, metodo, monto, referencia, id_usuario) VALUES (?,?,?,?,?)", idFactura, p.metodo(), monto, p.referencia(), Sesion.idUsuario);
                restante = restante.subtract(monto);
            }
            if (restante.signum() > 0) throw new IllegalArgumentException("Los pagos no cubren el total.");
        }
        Bitacora.registrar("FACTURAR", "factura", idFactura, num.get("numero") + " total " + t.total);
        return idFactura;
    }

    /** Check-out: factura hospedaje + consumos pendientes, cierra la estadía y deja la habitación en limpieza. */
    public static long facturarEstancia(int idEstancia, Opciones o) {
        return Db.transaccion(tx -> {
            CuentaEstancia c = cuentaEstancia(tx, idEstancia, true);
            BigDecimal pct = o.aplicarDescuentoAdultoMayor() ? porcentajeAdultoMayor(tx, c.idHuesped(), LocalDate.now()) : BigDecimal.ZERO;
            Totales t = calcularConConfig(tx, c.lineas(), pct, o.constanciaExoneracion() != null && !o.constanciaExoneracion().isBlank());
            BigDecimal anticipo = c.anticipo().min(t.total);
            Opciones op = o.idHuesped() == null ? new Opciones(c.idHuesped(), o.nombre(), o.rtn(), o.credito(), o.idClienteCredito(), o.pagos(), o.constanciaExoneracion(), o.aplicarDescuentoAdultoMayor()) : o;
            long idFactura = emitir(tx, t, op, "HOSPEDAJE", idEstancia, anticipo);

            for (Linea l : c.lineas()) if (l.idConsumo() != null) tx.exec("UPDATE consumos SET facturado = 1 WHERE id_consumo = ?", l.idConsumo());
            tx.exec("UPDATE comandas SET estado = 'FACTURADA', id_factura = ? WHERE id_estancia = ? AND estado = 'CARGADA_HABITACION' AND id_factura IS NULL", idFactura, idEstancia);
            Map<String, Object> e = tx.uno("SELECT id_habitacion, id_reserva FROM estancias WHERE id_estancia = ?", idEstancia);
            tx.exec("UPDATE estancias SET estado = 'CERRADA', checkout_real = NOW() WHERE id_estancia = ?", idEstancia);
            tx.exec("UPDATE habitaciones SET estado = 'LIMPIEZA' WHERE id_habitacion = ?", e.get("id_habitacion"));
            if (e.get("id_reserva") != null) tx.exec("UPDATE reservas SET estado = 'FINALIZADA' WHERE id_reserva = ?", e.get("id_reserva"));
            Bitacora.registrar("CHECKOUT", "estancia", idEstancia, "Factura " + idFactura);
            return idFactura;
        });
    }

    /** Cobro de una cuenta del restaurante (mesa o para llevar). */
    public static long facturarComanda(int idComanda, Opciones o) {
        return facturarCuenta(idComanda, null, o);
    }

    /** Cobra y factura toda la mesa (numCuenta null) o una subcuenta (cuentas divididas). */
    public static long facturarCuenta(int idComanda, Integer numCuenta, Opciones o) {
        if (!RestauranteService.puedeCobrar()) throw new IllegalArgumentException("Su usuario no puede cobrar. Pida a un cajero o supervisor.");
        return Db.transaccion(tx -> {
            CuentaComanda c = cuentaComanda(tx, idComanda, true, numCuenta);
            BigDecimal pct = o.aplicarDescuentoAdultoMayor() ? porcentajeAdultoMayor(tx, o.idHuesped(), LocalDate.now()) : BigDecimal.ZERO;
            Totales t = calcularConConfig(tx, c.lineas(), pct, o.constanciaExoneracion() != null && !o.constanciaExoneracion().isBlank());
            tx.call("{CALL sp_descontar_inventario_comanda(?)}", idComanda);
            long idFactura = emitir(tx, t, o, "RESTAURANTE", null, BigDecimal.ZERO);
            if (numCuenta != null)
                tx.exec("INSERT INTO comanda_cuentas (id_comanda, num_cuenta, id_factura) VALUES (?,?,?) ON DUPLICATE KEY UPDATE id_factura = VALUES(id_factura)",
                        idComanda, numCuenta, idFactura);
            boolean quedan = tx.scalar("SELECT 1 FROM comanda_detalle d WHERE d.id_comanda = ? AND d.estado <> 'ANULADO' AND d.num_cuenta NOT IN "
                    + "(SELECT num_cuenta FROM comanda_cuentas WHERE id_comanda = ? AND id_factura IS NOT NULL) LIMIT 1", idComanda, idComanda) != null;
            if (!quedan) {
                tx.exec("UPDATE comandas SET estado = 'FACTURADA', id_factura = ?, cerrada_en = COALESCE(cerrada_en, NOW()) WHERE id_comanda = ?", idFactura, idComanda);
                RestauranteService.liberarMesaSiCorresponde(tx, idComanda);
            }
            return idFactura;
        });
    }

    // ------------------------------------------------------------------ anular / re-emitir
    public static void anular(int idFactura, String motivo) {
        if (motivo == null || motivo.trim().length() < 5) throw new IllegalArgumentException("Escriba el motivo de la anulación (mínimo 5 letras).");
        if (!Sesion.esAdmin() && !"CAJERO".equals(Sesion.rol) && !"CONTADOR".equals(Sesion.rol))
            throw new IllegalArgumentException("Su usuario no puede anular facturas.");
        Db.transaccion(tx -> {
            int n = tx.exec("UPDATE facturas SET estado = 'ANULADA', motivo_anulacion = ?, id_usuario_anula = ? WHERE id_factura = ? AND estado = 'EMITIDA'",
                    motivo.trim(), Sesion.idUsuario, idFactura);
            if (n == 0) throw new IllegalArgumentException("La factura no existe o ya estaba anulada.");
            Bitacora.registrar("ANULAR", "factura", idFactura, motivo.trim());
            return null;
        });
    }

    /** Corrige una factura anulada (nombre/RTN): emite una nueva con el siguiente número y las mismas líneas e importes. */
    public static long reemitir(int idFacturaAnulada, String nombre, String rtn) {
        return Db.transaccion(tx -> {
            Map<String, Object> f = tx.uno("SELECT * FROM facturas WHERE id_factura = ? FOR UPDATE", idFacturaAnulada);
            if (f == null || !"ANULADA".equals(Db.str(f.get("estado")))) throw new IllegalArgumentException("Solo se re-emiten facturas anuladas.");
            if (tx.scalar("SELECT 1 FROM bitacora WHERE accion = 'REEMITIR' AND detalle = ? LIMIT 1", "re-emitida de #" + idFacturaAnulada) != null)
                throw new IllegalArgumentException("Esta factura ya fue re-emitida.");
            String r = rtn == null || rtn.isBlank() ? null : rtn.trim();
            if (r != null && !r.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
            tx.call("{CALL sp_siguiente_numero_factura(?, @p_cai, @p_num)}", "01");
            Map<String, Object> num = tx.uno("SELECT @p_cai AS cai, @p_num AS numero");
            long nid = tx.insert("INSERT INTO facturas (id_cai, numero_factura, id_estancia, id_huesped, cliente_nombre, cliente_rtn, importe_gravado_15, importe_gravado_18, "
                    + "importe_exento, importe_exonerado, descuento_total, isv_15, isv_18, tasa_turistica, total, constancia_exoneracion, origen, condicion_pago, "
                    + "id_cliente_credito, fecha_vencimiento, id_usuario) SELECT ?,?,id_estancia,id_huesped,?,?,importe_gravado_15,importe_gravado_18,importe_exento,"
                    + "importe_exonerado,descuento_total,isv_15,isv_18,tasa_turistica,total,constancia_exoneracion,origen,condicion_pago,id_cliente_credito,fecha_vencimiento,? "
                    + "FROM facturas WHERE id_factura = ?", num.get("cai"), num.get("numero"), nombre.isBlank() ? f.get("cliente_nombre") : nombre.trim(), r, Sesion.idUsuario, idFacturaAnulada);
            tx.exec("INSERT INTO factura_detalle (id_factura, tipo, descripcion, cantidad, precio_unitario, descuento, tratamiento, tasa_isv, subtotal, isv) "
                  + "SELECT ?, tipo, descripcion, cantidad, precio_unitario, descuento, tratamiento, tasa_isv, subtotal, isv FROM factura_detalle WHERE id_factura = ?", nid, idFacturaAnulada);
            if ("CONTADO".equals(Db.str(f.get("condicion_pago"))))
                tx.exec("INSERT INTO pagos (id_factura, metodo, monto, referencia, fecha_pago, id_usuario) SELECT ?, metodo, monto, referencia, fecha_pago, ? FROM pagos WHERE id_factura = ?", nid, Sesion.idUsuario, idFacturaAnulada);
            Bitacora.registrar("REEMITIR", "factura", nid, "re-emitida de #" + idFacturaAnulada);
            return nid;
        });
    }
}
