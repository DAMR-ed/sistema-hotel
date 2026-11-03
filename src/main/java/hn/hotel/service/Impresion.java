package hn.hotel.service;

import hn.hotel.app.Config;
import hn.hotel.db.Db;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

/** Tickets de 80 mm: se arman como texto y se mandan a una impresora térmica ESC/POS por red (puerto 9100). */
public final class Impresion {
    private Impresion() {}

    private static final Charset CP858 = Charset.forName("Cp858");

    public static int ancho() { return Math.max(24, Config.entero("ticket_caracteres", 42)); }

    /** Devuelve "host:puerto" configurado para ese destino o null si está desactivada. */
    public static String destino(String clave) {
        String v = Config.texto(clave, "NO").trim();
        return v.isEmpty() || v.equalsIgnoreCase("NO") ? null : v;
    }

    // ------------------------------------------------------------------ texto de los tickets
    public static String ticketCocina(TicketService.Ticket t) {
        int w = ancho();
        StringBuilder sb = new StringBuilder();
        sb.append(centrar(t.destino() + "  #" + t.numero(), w)).append('\n');
        sb.append(linea('=', w)).append('\n');
        sb.append(t.donde()).append("   ").append(t.hora()).append('\n');
        if (!t.mesero().isBlank()) sb.append("Mesero: ").append(t.mesero()).append('\n');
        sb.append(linea('-', w)).append('\n');
        for (TicketService.LineaTicket l : t.lineas()) {
            if (l.estado().equals("ANULADO")) continue;
            sb.append(l.cantidad()).append(" x ").append(l.descripcion()).append('\n');
            if (!l.notas().isBlank()) sb.append("    >> ").append(l.notas()).append('\n');
        }
        sb.append(linea('-', w)).append('\n');
        return sb.toString();
    }

    public static String n2(Object o) { return hn.hotel.ui.Ui.numero(o); }

    /** Encabezado común (nombre, RTN, dirección y teléfono del negocio). */
    public static void encabezado(StringBuilder sb, int w) {
        sb.append(centrar(Config.texto("hotel_nombre", "Restaurante"), w)).append('\n');
        String rtn = Config.texto("hotel_rtn", ""), dir = Config.texto("hotel_direccion", ""), tel = Config.texto("hotel_telefono", "");
        if (!rtn.isBlank()) sb.append(centrar("RTN: " + rtn, w)).append('\n');
        if (!dir.isBlank()) sb.append(centrar(dir, w)).append('\n');
        if (!tel.isBlank()) sb.append(centrar("Tel: " + tel, w)).append('\n');
    }

    /** Datos de la habitación cuando el cliente es un huésped (solo si el hotel está activo). */
    private static void datosHabitacion(StringBuilder sb, Integer idEstancia) {
        if (idEstancia == null || "NO".equalsIgnoreCase(Config.texto("modulo_hotel", "SI"))) return;
        Map<String, Object> e = Db.uno("SELECT h.numero, g.nombre, g.numero_documento, DATE_FORMAT(e.checkin_real, '%d/%m/%Y') AS entrada, "
                + "DATE_FORMAT(e.salida_prevista, '%d/%m/%Y') AS salida FROM estancias e JOIN habitaciones h ON h.id_habitacion = e.id_habitacion "
                + "JOIN huespedes g ON g.id_huesped = e.id_huesped WHERE e.id_estancia = ?", idEstancia);
        if (e == null) return;
        sb.append("Habitacion: ").append(Db.str(e.get("numero"))).append('\n');
        sb.append("Huesped: ").append(Db.str(e.get("nombre"))).append('\n');
        sb.append("Estadia: ").append(Db.str(e.get("entrada"))).append(" al ").append(Db.str(e.get("salida"))).append('\n');
    }

    private static void columnas(StringBuilder sb, int w) {
        sb.append(linea('-', w)).append('\n');
        sb.append(alinear("Cant. Descripcion", "", w)).append('\n');
        sb.append(alinear("      Precio    Desc.", "Total", w)).append('\n');
        sb.append(linea('-', w)).append('\n');
    }

    private static void renglon(StringBuilder sb, int w, Object cant, String desc, Object precio, Object descuento, Object total) {
        sb.append(cortar(n2(cant) + "  " + desc, w)).append('\n');
        String mid = "      " + n2(precio) + (Db.dec(descuento).signum() == 0 ? "" : "   -" + n2(descuento));
        sb.append(alinear(mid, n2(total), w)).append('\n');
    }

    private static String cortar(String s, int w) { return s.length() <= w ? s : s.substring(0, w); }

    /** Cuenta (pre-factura) en el mismo formato que la factura, con cada plato con su ISV. */
    public static String preCuenta(int idComanda) {
        int w = ancho();
        Map<String, Object> c = Db.uno("SELECT COALESCE(m.nombre, 'Para llevar') AS donde, u.nombre AS mesero, c.id_estancia, "
                + "DATE_FORMAT(NOW(), '%d/%m/%Y %H:%i') AS ahora FROM comandas c "
                + "LEFT JOIN mesas m ON m.id_mesa = c.id_mesa LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario WHERE c.id_comanda = ?", idComanda);
        StringBuilder sb = new StringBuilder();
        encabezado(sb, w);
        sb.append(centrar("CUENTA (no es factura)", w)).append('\n');
        sb.append(linea('-', w)).append('\n');
        if (c != null) {
            sb.append("Mesero: ").append(Db.str(c.get("mesero"))).append('\n');
            sb.append("Cliente: CONSUMIDOR FINAL").append('\n');
            sb.append("Fecha: ").append(Db.str(c.get("ahora"))).append('\n');
            sb.append("Mesa: ").append(Db.str(c.get("donde"))).append("   Comanda #").append(idComanda).append('\n');
            datosHabitacion(sb, c.get("id_estancia") == null ? null : Db.ent(c.get("id_estancia")));
        }
        columnas(sb, w);
        java.math.BigDecimal sub = java.math.BigDecimal.ZERO, ex = java.math.BigDecimal.ZERO, g15 = java.math.BigDecimal.ZERO,
                g18 = java.math.BigDecimal.ZERO, i15 = java.math.BigDecimal.ZERO, i18 = java.math.BigDecimal.ZERO;
        java.math.BigDecimal general = Config.numero("isv_general", "15");
        for (Map<String, Object> d : Db.query("SELECT cantidad, descripcion, precio_unitario, descuento, tasa_isv, ROUND(cantidad*precio_unitario-descuento,2) AS imp "
                + "FROM comanda_detalle WHERE id_comanda = ? AND estado <> 'ANULADO' ORDER BY num_cuenta, id_detalle", idComanda)) {
            java.math.BigDecimal imp = Db.dec(d.get("imp")), tasa = Db.dec(d.get("tasa_isv"));
            java.math.BigDecimal isv = imp.multiply(tasa).movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
            renglon(sb, w, d.get("cantidad"), Db.str(d.get("descripcion")), d.get("precio_unitario"), d.get("descuento"), imp.add(isv));
            if (tasa.signum() == 0) ex = ex.add(imp);
            else if (tasa.compareTo(general) == 0) { g15 = g15.add(imp); i15 = i15.add(isv); }
            else { g18 = g18.add(imp); i18 = i18.add(isv); }
            sub = sub.add(imp);
        }
        sb.append(linea('-', w)).append('\n');
        sb.append(alinear("Importe exento", n2(ex), w)).append('\n');
        sb.append(alinear("Gravado 15%", n2(g15), w)).append('\n');
        sb.append(alinear("Gravado 18%", n2(g18), w)).append('\n');
        sb.append(alinear("  ISV 15%", n2(i15), w)).append('\n');
        sb.append(alinear("  ISV 18%", n2(i18), w)).append('\n');
        java.math.BigDecimal total = sub.add(i15).add(i18);
        sb.append(alinear("TOTAL A PAGAR", n2(total), w)).append('\n');
        java.math.BigDecimal prop = total.multiply(Config.numero("propina_sugerida", "10")).movePointLeft(2).setScale(2, java.math.RoundingMode.HALF_UP);
        sb.append(alinear("Propina sugerida " + Config.numero("propina_sugerida", "10").stripTrailingZeros().toPlainString() + "%", n2(prop), w)).append('\n');
        sb.append(alinear("Total con propina", n2(total.add(prop)), w)).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append(centrar("GRACIAS POR SU PREFERENCIA", w)).append('\n');
        return sb.toString();
    }

    /** Factura fiscal en ticket de 80 mm (CAI, rango, fecha límite, desglose de ISV y, si es huésped, los datos de la habitación). */
    public static String factura(int idFactura) {
        int w = ancho();
        Map<String, Object> f = Db.uno("SELECT f.*, c.cai, c.rango_inicial, c.rango_final, c.fecha_limite_emision, c.establecimiento, c.punto_emision, c.tipo_documento, "
                + "DATE_FORMAT(f.fecha_emision, '%d/%m/%Y %H:%i') AS fecha_txt, DATE_FORMAT(c.fecha_limite_emision, '%d/%m/%Y') AS limite_txt, u.nombre AS cajero "
                + "FROM facturas f JOIN cai_autorizaciones c ON c.id_cai = f.id_cai LEFT JOIN usuarios u ON u.id_usuario = f.id_usuario WHERE f.id_factura = ?", idFactura);
        if (f == null) throw new IllegalArgumentException("La factura no existe.");
        String pre = Db.str(f.get("establecimiento")) + "-" + Db.str(f.get("punto_emision")) + "-" + Db.str(f.get("tipo_documento")) + "-";
        Map<String, Object> cm = Db.uno("SELECT COALESCE(m.nombre, 'Para llevar') AS donde, u.nombre AS mesero, c.id_estancia FROM comandas c "
                + "LEFT JOIN mesas m ON m.id_mesa = c.id_mesa LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario "
                + "WHERE c.id_factura = ? OR c.id_comanda IN (SELECT id_comanda FROM comanda_cuentas WHERE id_factura = ?) ORDER BY c.id_comanda LIMIT 1", idFactura, idFactura);
        Integer idEstancia = f.get("id_estancia") != null ? Db.ent(f.get("id_estancia")) : cm != null && cm.get("id_estancia") != null ? Db.ent(cm.get("id_estancia")) : null;

        StringBuilder sb = new StringBuilder();
        encabezado(sb, w);
        sb.append(centrar("FACTURA" + ("ANULADA".equals(Db.str(f.get("estado"))) ? " *** ANULADA ***" : ""), w)).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append("CAI: ").append(Db.str(f.get("cai"))).append('\n');
        sb.append("Rango Autorizado:\n ").append(pre).append(String.format("%08d", Db.lng(f.get("rango_inicial"))))
          .append(" a\n ").append(pre).append(String.format("%08d", Db.lng(f.get("rango_final")))).append('\n');
        sb.append("Fecha Limite: ").append(Db.str(f.get("limite_txt"))).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append("Factura: ").append(Db.str(f.get("numero_factura"))).append('\n');
        sb.append("Mesero: ").append(cm != null ? Db.str(cm.get("mesero")) : Db.str(f.get("cajero"))).append('\n');
        sb.append("Cliente: ").append(Db.str(f.get("cliente_nombre"))).append('\n');
        sb.append("RTN: ").append(f.get("cliente_rtn") == null ? "" : Db.str(f.get("cliente_rtn"))).append('\n');
        sb.append("Fecha: ").append(Db.str(f.get("fecha_txt"))).append('\n');
        sb.append("Mesa: ").append(cm == null ? "N/A" : Db.str(cm.get("donde"))).append('\n');
        datosHabitacion(sb, idEstancia);
        if (f.get("constancia_exoneracion") != null) sb.append("No. Constancia: ").append(Db.str(f.get("constancia_exoneracion"))).append('\n');
        if ("CREDITO".equals(Db.str(f.get("condicion_pago")))) sb.append("Condicion: CREDITO\n");
        columnas(sb, w);
        for (Map<String, Object> d : Db.query("SELECT cantidad, descripcion, precio_unitario, descuento, subtotal, isv, tratamiento FROM factura_detalle WHERE id_factura = ? ORDER BY id_detalle", idFactura)) {
            String desc = Db.str(d.get("descripcion")) + ("EXENTO".equals(Db.str(d.get("tratamiento"))) ? " (E)" : "EXONERADO".equals(Db.str(d.get("tratamiento"))) ? " (X)" : "");
            renglon(sb, w, d.get("cantidad"), desc, d.get("precio_unitario"), d.get("descuento"), Db.dec(d.get("subtotal")).add(Db.dec(d.get("isv"))));
        }
        sb.append(linea('-', w)).append('\n');
        sb.append(alinear("Descuentos", n2(f.get("descuento_total")), w)).append('\n');
        sb.append(alinear("Importe exento", n2(f.get("importe_exento")), w)).append('\n');
        sb.append(alinear("Importe exonerado", n2(f.get("importe_exonerado")), w)).append('\n');
        sb.append(alinear("Gravado 15%", n2(f.get("importe_gravado_15")), w)).append('\n');
        sb.append(alinear("Gravado 18%", n2(f.get("importe_gravado_18")), w)).append('\n');
        sb.append(alinear("  ISV 15%", n2(f.get("isv_15")), w)).append('\n');
        sb.append(alinear("  ISV 18%", n2(f.get("isv_18")), w)).append('\n');
        if (Db.dec(f.get("tasa_turistica")).signum() > 0) sb.append(alinear("Tasa turistica (incl.)", n2(f.get("tasa_turistica")), w)).append('\n');
        sb.append(alinear("TOTAL A PAGAR", n2(f.get("total")), w)).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append("Forma de Pago\n");
        for (Map<String, Object> p : Db.query("SELECT metodo, monto FROM pagos WHERE id_factura = ?", idFactura))
            sb.append(alinear(Db.str(p.get("metodo")), n2(p.get("monto")), w)).append('\n');
        sb.append(NumeroALetras.lempiras(Db.dec(f.get("total")))).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append(centrar(Config.texto("leyenda_factura", "La factura es beneficio de todos, exijala"), w)).append('\n');
        sb.append(centrar("GRACIAS POR SU COMPRA", w)).append('\n');
        sb.append(centrar("Original: Cliente  Copia: Emisor", w)).append('\n');
        return sb.toString();
    }

    /** Ticket del corte X o Z (también sirve para la vista previa antes de guardarlo). */
    public static String corte(Map<String, Object> c) {
        int w = ancho();
        boolean z = "Z".equals(Db.str(c.get("tipo")));
        StringBuilder sb = new StringBuilder();
        encabezado(sb, w);
        sb.append(centrar(z ? "CORTE Z - CIERRE DEL DIA" : "CORTE X - CAMBIO DE TURNO", w)).append('\n');
        sb.append(linea('=', w)).append('\n');
        if (c.get("id_corte") != null) sb.append("Corte No.: ").append(Db.str(c.get("id_corte"))).append('\n');
        else sb.append("*** VISTA PREVIA (aun no guardado) ***\n");
        if (c.get("cajero") != null) sb.append(z ? "Hecho por: " : "Cajero: ").append(Db.str(c.get("cajero"))).append('\n');
        if (c.get("desde_txt") != null) sb.append("Desde: ").append(Db.str(c.get("desde_txt"))).append('\n');
        if (c.get("hasta_txt") != null) sb.append("Hasta: ").append(Db.str(c.get("hasta_txt"))).append('\n');
        if (c.get("creado_txt") != null) sb.append("Impreso: ").append(Db.str(c.get("creado_txt"))).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append(alinear("Facturas emitidas", Db.str(c.get("n_facturas")), w)).append('\n');
        if (c.get("desde_factura") != null) {
            sb.append("Primera: ").append(Db.str(c.get("desde_factura"))).append('\n');
            sb.append("Ultima:  ").append(Db.str(c.get("hasta_factura"))).append('\n');
        }
        sb.append(linea('-', w)).append('\n');
        sb.append(centrar("VENTAS", w)).append('\n');
        sb.append(alinear("Importe exento", n2(c.get("ventas_exento")), w)).append('\n');
        sb.append(alinear("Importe exonerado", n2(c.get("ventas_exonerado")), w)).append('\n');
        sb.append(alinear("Gravado 15%", n2(c.get("ventas_grav15")), w)).append('\n');
        sb.append(alinear("Gravado 18%", n2(c.get("ventas_grav18")), w)).append('\n');
        sb.append(alinear("  ISV 15%", n2(c.get("isv15")), w)).append('\n');
        sb.append(alinear("  ISV 18%", n2(c.get("isv18")), w)).append('\n');
        sb.append(alinear("Descuentos otorgados", n2(c.get("descuentos")), w)).append('\n');
        if (Db.dec(c.get("tasa_turistica")).signum() > 0) sb.append(alinear("Tasa turistica (incl.)", n2(c.get("tasa_turistica")), w)).append('\n');
        sb.append(alinear("TOTAL VENTAS", n2(c.get("total_ventas")), w)).append('\n');
        sb.append(alinear("  Al contado", n2(c.get("ventas_contado")), w)).append('\n');
        sb.append(alinear("  A credito", n2(c.get("ventas_credito")), w)).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append(centrar("FORMAS DE PAGO", w)).append('\n');
        sb.append(alinear("Efectivo", n2(c.get("pago_efectivo")), w)).append('\n');
        sb.append(alinear("Tarjeta", n2(c.get("pago_tarjeta")), w)).append('\n');
        sb.append(alinear("Transferencia", n2(c.get("pago_transferencia")), w)).append('\n');
        sb.append(alinear("Cheque", n2(c.get("pago_cheque")), w)).append('\n');
        sb.append(alinear("Otro", n2(c.get("pago_otro")), w)).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append(centrar("CAJA (EFECTIVO)", w)).append('\n');
        sb.append(alinear(z ? "Fondos iniciales" : "Fondo inicial", n2(c.get("fondo_inicial")), w)).append('\n');
        sb.append(alinear("+ Ventas en efectivo", n2(c.get("pago_efectivo")), w)).append('\n');
        sb.append(alinear("= Efectivo esperado", n2(c.get("efectivo_esperado")), w)).append('\n');
        sb.append(alinear("Efectivo contado", n2(c.get("efectivo_contado")), w)).append('\n');
        java.math.BigDecimal dif = Db.dec(c.get("diferencia"));
        sb.append(alinear(dif.signum() == 0 ? "DIFERENCIA (cuadra)" : dif.signum() > 0 ? "SOBRANTE" : "FALTANTE", n2(dif), w)).append('\n');
        sb.append(linea('-', w)).append('\n');
        sb.append(alinear("Propinas (voluntarias)", n2(c.get("propinas")), w)).append('\n');
        sb.append(alinear("Facturas anuladas (" + Db.str(c.get("n_anuladas")) + ")", n2(c.get("anulado_total")), w)).append('\n');
        if (Db.ent(c.get("n_anul_previas")) > 0)
            sb.append(alinear("Anul. de turnos previos (" + Db.str(c.get("n_anul_previas")) + ")", n2(c.get("anul_previas_total")), w)).append('\n');
        sb.append("Cuentas abiertas al corte: ").append(Db.str(c.get("mesas_abiertas"))).append(" (no incluidas)\n");
        if (z && c.get("id_corte") != null) {
            sb.append(linea('-', w)).append('\n');
            sb.append(centrar("CORTES X INCLUIDOS", w)).append('\n');
            for (Map<String, Object> x : CorteService.incluidos(Db.ent(c.get("id_corte"))))
                sb.append(alinear("X#" + x.get("id_corte") + " " + Db.str(x.get("cajero")) + " " + Db.str(x.get("hora")), n2(x.get("total_ventas")), w)).append('\n');
        }
        if (c.get("notas") != null) sb.append("Notas: ").append(Db.str(c.get("notas"))).append('\n');
        sb.append(linea('=', w)).append('\n');
        sb.append("\n\nFirma cajero: ").append("_".repeat(Math.max(8, w - 15))).append('\n');
        if (z) sb.append("\nFirma gerente: ").append("_".repeat(Math.max(8, w - 16))).append('\n');
        return sb.toString();
    }

    public static boolean imprimirCorte(int idCorte) {
        String dest = destino("impresora_cuenta");
        if (dest == null) return false;
        enviar(dest, escpos(corte(CorteService.obtener(idCorte)), null));
        return true;
    }

    public static boolean imprimirFactura(int idFactura) {
        String dest = destino("impresora_cuenta");
        if (dest == null) return false;
        enviar(dest, escpos(factura(idFactura), null));
        return true;
    }

    public static String linea(char c, int w) { return String.valueOf(c).repeat(w); }

    public static String centrar(String s, int w) {
        if (s.length() >= w) return s;
        return " ".repeat((w - s.length()) / 2) + s;
    }

    public static String alinear(String izq, String der, int w) {
        int libre = w - der.length() - 1;
        if (izq.length() > libre) izq = izq.substring(0, Math.max(0, libre));
        return izq + " ".repeat(Math.max(1, w - izq.length() - der.length())) + der;
    }

    // ------------------------------------------------------------------ envío a la impresora
    public static byte[] escpos(String texto, String titulo) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{0x1B, 0x40});                 // inicializar
        o.writeBytes(new byte[]{0x1B, 0x74, 19});             // página de códigos CP858 (acentos)
        if (titulo != null) {
            o.writeBytes(new byte[]{0x1B, 0x45, 1, 0x1D, 0x21, 0x11});   // negrita + doble tamaño
            o.writeBytes((titulo + "\n").getBytes(CP858));
            o.writeBytes(new byte[]{0x1D, 0x21, 0x00, 0x1B, 0x45, 0});
        }
        o.writeBytes(texto.getBytes(CP858));
        o.writeBytes("\n\n\n\n".getBytes(CP858));
        o.writeBytes(new byte[]{0x1D, 0x56, 66, 0});          // corte parcial
        return o.toByteArray();
    }

    /** Manda los bytes a "host:puerto". Lanza IllegalStateException con un mensaje claro si no se puede. */
    public static void enviar(String hostPuerto, byte[] datos) {
        String host = hostPuerto; int puerto = 9100;
        int i = hostPuerto.lastIndexOf(':');
        if (i > 0) {
            host = hostPuerto.substring(0, i);
            try { puerto = Integer.parseInt(hostPuerto.substring(i + 1).trim()); }
            catch (NumberFormatException e) { throw new IllegalStateException("Puerto de impresora no válido: " + hostPuerto); }
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host.trim(), puerto), 2500);
            s.setSoTimeout(3000);
            OutputStream os = s.getOutputStream();
            os.write(datos);
            os.flush();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo imprimir en " + hostPuerto + " (" + e.getMessage() + "). Revise que esté encendida y en la misma red.");
        }
    }

    /** Imprime el ticket en la impresora de su destino. Devuelve false si esa impresora no está configurada. */
    public static boolean imprimirTicket(int idTicket) {
        TicketService.Ticket t = TicketService.uno(idTicket);
        Object imp = Db.scalar("SELECT impresora FROM estaciones WHERE nombre = ?", t.destino());
        String dest = imp == null || imp.toString().isBlank() || imp.toString().equalsIgnoreCase("NO") ? null : imp.toString().trim();
        if (dest == null) return false;
        enviar(dest, escpos(ticketCocina(t), t.destino() + " #" + t.numero()));
        TicketService.marcarImpreso(idTicket);
        return true;
    }

    public static boolean imprimirPreCuenta(int idComanda) {
        String dest = destino("impresora_cuenta");
        if (dest == null) return false;
        enviar(dest, escpos(preCuenta(idComanda), null));
        return true;
    }

    /** Imprime los tickets recién creados sin interrumpir al mesero si falla; devuelve los errores (vacío si todo bien). */
    public static List<String> imprimirAuto(List<Integer> tickets) {
        List<String> errores = new java.util.ArrayList<>();
        if (!"SI".equalsIgnoreCase(Config.texto("imprimir_auto", "SI"))) return errores;
        for (int id : tickets) {
            try {
                Object auto = Db.scalar("SELECT e.imprime_auto FROM tickets_cocina t LEFT JOIN estaciones e ON e.id_estacion = t.id_estacion WHERE t.id_ticket = ?", id);
                if (auto != null && Db.ent(auto) == 0) continue;
                imprimirTicket(id);
            }
            catch (RuntimeException e) { errores.add(e.getMessage()); }
        }
        return errores;
    }
}
