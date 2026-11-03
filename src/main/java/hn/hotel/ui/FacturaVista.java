package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.db.Db;
import hn.hotel.service.NumeroALetras;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Muestra una factura emitida y permite imprimirla (en el cuadro de impresión de macOS también se guarda como PDF). */
public final class FacturaVista {
    private FacturaVista() {}

    private static String esc(Object o) {
        return o == null ? "" : o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String html(int idFactura) {
        Map<String, Object> f = Db.uno("SELECT f.*, c.cai, c.rango_inicial, c.rango_final, c.fecha_limite_emision, c.establecimiento, c.punto_emision, c.tipo_documento "
                + "FROM facturas f JOIN cai_autorizaciones c ON c.id_cai = f.id_cai WHERE f.id_factura = ?", idFactura);
        List<Map<String, Object>> det = Db.query("SELECT tipo, descripcion, cantidad, precio_unitario, descuento, tratamiento, subtotal, isv FROM factura_detalle WHERE id_factura = ? ORDER BY id_detalle", idFactura);
        List<Map<String, Object>> pagos = Db.query("SELECT metodo, monto FROM pagos WHERE id_factura = ?", idFactura);
        String est = Db.str(f.get("establecimiento")), pto = Db.str(f.get("punto_emision")), td = Db.str(f.get("tipo_documento"));
        String ini = est + "-" + pto + "-" + td + "-" + String.format("%08d", Db.lng(f.get("rango_inicial")));
        String fin = est + "-" + pto + "-" + td + "-" + String.format("%08d", Db.lng(f.get("rango_final")));
        boolean anulada = "ANULADA".equals(Db.str(f.get("estado")));

        StringBuilder h = new StringBuilder("<html><body style='font-family:SansSerif;font-size:11px'>");
        h.append("<center><b><font size=5>").append(esc(Config.texto("hotel_nombre", "Hotel"))).append("</font></b><br>");
        h.append("RTN: ").append(esc(Config.texto("hotel_rtn", ""))).append("<br>");
        h.append(esc(Config.texto("hotel_direccion", ""))).append("<br>Tel: ").append(esc(Config.texto("hotel_telefono", ""))).append("<br><br>");
        h.append("<b>FACTURA</b>").append(anulada ? " <font color='red'><b>*** ANULADA ***</b></font>" : "").append("<br>");
        h.append("<b>N.º ").append(esc(f.get("numero_factura"))).append("</b></center><br>");
        h.append("CAI: ").append(esc(f.get("cai"))).append("<br>");
        h.append("Fecha de emisión: ").append(Ui.texto(Ui.fechaHora(f.get("fecha_emision")))).append("<br>");
        h.append("Cliente: <b>").append(esc(f.get("cliente_nombre"))).append("</b><br>");
        h.append("RTN cliente: ").append(f.get("cliente_rtn") == null ? "C/F" : esc(f.get("cliente_rtn"))).append("<br>");
        if (f.get("constancia_exoneracion") != null) h.append("Constancia de exoneración: ").append(esc(f.get("constancia_exoneracion"))).append("<br>");
        h.append("Condición de pago: <b>").append(Db.str(f.get("condicion_pago")).equals("CREDITO") ? "CRÉDITO" : "CONTADO").append("</b>");
        if (f.get("fecha_vencimiento") != null) h.append("  (vence ").append(Ui.texto(Ui.fecha(f.get("fecha_vencimiento")))).append(")");
        h.append("<br><br><table width='100%' border='0' cellspacing='0' cellpadding='2'>");
        h.append("<tr bgcolor='#DDDDDD'><td><b>Cant.</b></td><td><b>Descripción</b></td><td align='right'><b>P. unit.</b></td><td align='right'><b>Desc.</b></td><td align='right'><b>Importe</b></td></tr>");
        for (Map<String, Object> d : det) {
            h.append("<tr><td>").append(Ui.numero(d.get("cantidad"))).append("</td><td>").append(esc(d.get("descripcion")));
            if ("EXENTO".equals(Db.str(d.get("tratamiento"))) && !"TASA_TURISTICA".equals(Db.str(d.get("tipo")))) h.append(" (E)");
            if ("EXONERADO".equals(Db.str(d.get("tratamiento")))) h.append(" (X)");
            h.append("</td><td align='right'>").append(Ui.numero(d.get("precio_unitario"))).append("</td><td align='right'>")
             .append(Db.dec(d.get("descuento")).signum() == 0 ? "" : Ui.numero(d.get("descuento"))).append("</td><td align='right'>").append(Ui.numero(d.get("subtotal"))).append("</td></tr>");
        }
        h.append("</table><hr><table width='100%'>");
        fila(h, "Importe exonerado", f.get("importe_exonerado"), false);
        fila(h, "Importe exento", f.get("importe_exento"), false);
        fila(h, "Importe gravado 15 %", f.get("importe_gravado_15"), false);
        if (Db.dec(f.get("importe_gravado_18")).signum() > 0) fila(h, "Importe gravado 18 %", f.get("importe_gravado_18"), false);
        fila(h, "Descuentos y rebajas otorgados", f.get("descuento_total"), false);
        fila(h, "ISV 15 %", f.get("isv_15"), false);
        if (Db.dec(f.get("isv_18")).signum() > 0) fila(h, "ISV 18 %", f.get("isv_18"), false);
        if (Db.dec(f.get("tasa_turistica")).signum() > 0) fila(h, "Tasa turística (incluida en detalle)", f.get("tasa_turistica"), false);
        fila(h, "TOTAL A PAGAR", f.get("total"), true);
        h.append("</table><br>Son: <b>").append(NumeroALetras.lempiras(Db.dec(f.get("total")))).append("</b><br>");
        if (!pagos.isEmpty()) {
            h.append("Pagos: ");
            for (Map<String, Object> p : pagos) h.append(esc(p.get("metodo"))).append(" ").append(Ui.lempiras(p.get("monto"))).append("  ");
            h.append("<br>");
        }
        h.append("<br><font size=2>Rango autorizado: ").append(ini).append(" a ").append(fin).append("<br>");
        h.append("Fecha límite de emisión: ").append(Ui.texto(Ui.fecha(f.get("fecha_limite_emision")))).append("</font><br><br>");
        h.append("<center><b>").append(esc(Config.texto("leyenda_factura", "La factura es beneficio de todos, ¡exíjala!"))).append("</b><br>");
        h.append("<font size=2>Original: Cliente &nbsp;&nbsp; Copia: Emisor</font></center></body></html>");
        return h.toString();
    }

    private static void fila(StringBuilder h, String etiqueta, Object valor, boolean fuerte) {
        BigDecimal v = Db.dec(valor);
        h.append("<tr><td align='right' width='75%'>").append(fuerte ? "<b>" : "").append(etiqueta).append(fuerte ? "</b>" : "")
         .append("</td><td align='right'>").append(fuerte ? "<b>" : "").append(Ui.numero(v)).append(fuerte ? "</b>" : "").append("</td></tr>");
    }

    public static void mostrar(Component padre, int idFactura) {
        JEditorPane ep = new JEditorPane("text/html", html(idFactura));
        ep.setEditable(false);
        ep.setCaretPosition(0);
        JScrollPane sp = new JScrollPane(ep);
        sp.setPreferredSize(new Dimension(560, 640));
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(w, "Factura", Dialog.ModalityType.APPLICATION_MODAL);
        JButton imprimir = Ui.boton("Imprimir / Guardar PDF", () -> {
            try { ep.print(); } catch (Exception e) { Ui.error(d, "No se pudo imprimir: " + e.getMessage()); }
        });
        JButton ticket = Ui.boton("Imprimir ticket 80 mm", () -> {
            try {
                if (hn.hotel.service.Impresion.imprimirFactura(idFactura)) Ui.info(d, "Factura enviada a la impresora de caja.");
                else {
                    JTextArea ta = new JTextArea(hn.hotel.service.Impresion.factura(idFactura), 28, hn.hotel.service.Impresion.ancho() + 4);
                    ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                    ta.setEditable(false);
                    JOptionPane.showMessageDialog(d, new JScrollPane(ta), "Vista del ticket (no hay impresora configurada en «impresora_cuenta»)", JOptionPane.PLAIN_MESSAGE);
                }
            } catch (RuntimeException e) { Ui.error(d, e.getMessage()); }
        });
        JButton cerrar = Ui.boton("Cerrar", d::dispose);
        JPanel sur = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        sur.add(ticket); sur.add(imprimir); sur.add(cerrar);
        d.setLayout(new BorderLayout());
        d.add(sp, BorderLayout.CENTER);
        d.add(sur, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }
}
