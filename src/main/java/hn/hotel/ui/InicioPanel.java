package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Tablero con lo más importante del día. */
public class InicioPanel extends JPanel implements Actualizable {
    private final JLabel lblLibres = tarjeta(), lblOcupadas = tarjeta(), lblLlegadas = tarjeta(),
            lblSalidas = tarjeta(), lblVentas = tarjeta(), lblComandas = tarjeta();
    private final DefaultTableModel modeloAlertas = Ui.modelo("Tipo", "Prioridad", "Detalle");
    private final JComboBox<String> periodo = new JComboBox<>(new String[]{"Hoy", "Últimos 7 días", "Últimos 30 días", "Este mes", "Mes anterior"});
    private final Graficos.Barras gDias = new Graficos.Barras(false, Graficos.MONEDA, Tema.ACENTO),
            gHoras = new Graficos.Barras(false, Graficos.MONEDA, new Color(0x8B5CF6)),
            gProductos = new Graficos.Barras(true, Graficos.MONEDA, new Color(0x10B981));
    private final Graficos.Dona gTipos = new Graficos.Dona(Graficos.MONEDA), gPagos = new Graficos.Dona(Graficos.MONEDA), gPuntos = new Graficos.Dona(Graficos.MONEDA);
    private final JLabel banner = new JLabel(" ");

    public InicioPanel() {
        setLayout(new BorderLayout(10, 16));
        setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        JLabel saludo = new JLabel("<html><span style='font-size:20px'><b>Resumen del día</b></span><br>"
                + "<span style='color:#64748B'>" + java.time.LocalDate.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", new java.util.Locale("es", "HN")))
                + "</span></html>");
        JPanel norte = new JPanel(new BorderLayout());
        norte.setOpaque(false);
        norte.add(saludo, BorderLayout.WEST);
        JPanel rapidas = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        rapidas.setOpaque(false);
        if (hn.hotel.app.Sesion.puede("reservas"))
            rapidas.add(Ui.botonPrimario("+ Nueva reserva", () -> NuevaReservaDialog.mostrar(this)));
        if (hn.hotel.app.Sesion.puede("estancias"))
            rapidas.add(Ui.boton("Check-in / Check-out", () -> ir("estancias")));
        if (hn.hotel.app.Sesion.puede("pos_rest"))
            rapidas.add(Ui.boton("Punto de venta", () -> ir("pos_rest")));
        norte.add(rapidas, BorderLayout.EAST);
        add(norte, BorderLayout.NORTH);

        JPanel centro = new JPanel();
        centro.setLayout(new BoxLayout(centro, BoxLayout.Y_AXIS));
        centro.setOpaque(false);
        banner.setOpaque(true);
        banner.setBackground(new Color(0xFDE8E8));
        banner.setForeground(new Color(0xB00020));
        banner.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        banner.setAlignmentX(Component.LEFT_ALIGNMENT);
        banner.setVisible(false);
        centro.add(banner);
        centro.add(Box.createVerticalStrut(10));

        JPanel tarjetas = new JPanel(new GridLayout(2, 3, 16, 16));
        tarjetas.setOpaque(false);
        tarjetas.add(envolver("Habitaciones libres", lblLibres, Ui.COLOR_LIBRE));
        tarjetas.add(envolver("Habitaciones ocupadas", lblOcupadas, Ui.COLOR_OCUPADA));
        tarjetas.add(envolver("Llegadas de hoy", lblLlegadas, Ui.COLOR_RESERVADA));
        tarjetas.add(envolver("Salidas pendientes", lblSalidas, Ui.COLOR_LIMPIEZA));
        tarjetas.add(envolver("Facturado hoy", lblVentas, Tema.ACENTO));
        tarjetas.add(envolver("Comandas abiertas", lblComandas, new Color(0x8B5CF6)));
        tarjetas.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        tarjetas.setAlignmentX(Component.LEFT_ALIGNMENT);
        centro.add(tarjetas);
        centro.add(Box.createVerticalStrut(18));

        JPanel cabGraf = new JPanel(new BorderLayout());
        cabGraf.setOpaque(false);
        JLabel tg = new JLabel("Reporte en gráficos");
        tg.setFont(tg.getFont().deriveFont(Font.BOLD, 16f));
        cabGraf.add(tg, BorderLayout.WEST);
        periodo.setSelectedIndex(2);
        periodo.addActionListener(e -> alMostrar());
        JPanel pp = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pp.setOpaque(false);
        pp.add(new JLabel("Período:"));
        pp.add(periodo);
        cabGraf.add(pp, BorderLayout.EAST);
        cabGraf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        cabGraf.setAlignmentX(Component.LEFT_ALIGNMENT);
        centro.add(cabGraf);
        centro.add(Box.createVerticalStrut(8));

        JPanel graficos = new JPanel(new GridLayout(2, 3, 16, 16));
        graficos.setOpaque(false);
        graficos.add(grafico("Ventas por día", gDias));
        graficos.add(grafico("Ventas por tipo", gTipos));
        graficos.add(grafico("Formas de pago", gPagos));
        graficos.add(grafico("Productos más vendidos", gProductos));
        graficos.add(grafico("Ventas por hora del día", gHoras));
        graficos.add(grafico("Ventas por punto de venta (CAI)", gPuntos));
        graficos.setPreferredSize(new Dimension(900, 560));
        graficos.setMaximumSize(new Dimension(Integer.MAX_VALUE, 560));
        graficos.setAlignmentX(Component.LEFT_ALIGNMENT);
        centro.add(graficos);
        centro.add(Box.createVerticalStrut(18));

        Tema.Tarjeta alertas = new Tema.Tarjeta(new BorderLayout(0, 8), null);
        JLabel ta = new JLabel("Alertas activas");
        ta.setFont(ta.getFont().deriveFont(Font.BOLD, 14f));
        alertas.add(ta, BorderLayout.NORTH);
        JTable t = Ui.tabla(modeloAlertas);
        t.getColumnModel().getColumn(0).setMaxWidth(170);
        t.getColumnModel().getColumn(1).setMaxWidth(90);
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createEmptyBorder());
        alertas.add(sp, BorderLayout.CENTER);
        alertas.setPreferredSize(new Dimension(900, 260));
        alertas.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
        alertas.setAlignmentX(Component.LEFT_ALIGNMENT);
        centro.add(alertas);

        JScrollPane sc = new JScrollPane(centro, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sc.setBorder(BorderFactory.createEmptyBorder());
        sc.setOpaque(false);
        sc.getViewport().setOpaque(false);
        sc.getVerticalScrollBar().setUnitIncrement(24);
        add(sc, BorderLayout.CENTER);
    }

    private static JPanel grafico(String titulo, JComponent g) {
        Tema.Tarjeta p = new Tema.Tarjeta(new BorderLayout(0, 6), null);
        JLabel t = new JLabel(titulo);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 13f));
        p.add(t, BorderLayout.NORTH);
        p.add(g, BorderLayout.CENTER);
        return p;
    }

    private void ir(String modulo) {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w instanceof MainFrame mf) mf.mostrar(modulo);
    }

    private static JLabel tarjeta() {
        JLabel l = new JLabel("-");
        l.setFont(l.getFont().deriveFont(Font.BOLD, 32f));
        l.setForeground(Tema.TEXTO);
        return l;
    }

    private static JPanel envolver(String titulo, JLabel valor, Color franja) {
        Tema.Tarjeta p = new Tema.Tarjeta(new BorderLayout(0, 6), franja);
        JLabel t = new JLabel(titulo);
        t.setForeground(Tema.TEXTO_SUAVE);
        p.add(t, BorderLayout.NORTH);
        p.add(valor, BorderLayout.CENTER);
        return p;
    }

    private LocalDate[] rango() {
        LocalDate hoy = LocalDate.now();
        return switch (periodo.getSelectedIndex()) {
            case 0 -> new LocalDate[]{hoy, hoy};
            case 1 -> new LocalDate[]{hoy.minusDays(6), hoy};
            case 3 -> new LocalDate[]{hoy.withDayOfMonth(1), hoy};
            case 4 -> new LocalDate[]{hoy.minusMonths(1).withDayOfMonth(1), hoy.withDayOfMonth(1).minusDays(1)};
            default -> new LocalDate[]{hoy.minusDays(29), hoy};
        };
    }

    @Override public void alMostrar() {
        LocalDate[] rg = rango();
        LocalDate d = rg[0], h = rg[1];
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> {
            Object[] r = new Object[14];
            r[0] = tx.scalar("SELECT COUNT(*) FROM habitaciones WHERE activa = 1 AND estado = 'LIBRE'");
            r[1] = tx.scalar("SELECT COUNT(*) FROM habitaciones WHERE activa = 1 AND estado = 'OCUPADA'");
            r[2] = tx.scalar("SELECT COUNT(*) FROM reservas WHERE estado IN ('PENDIENTE','CONFIRMADA') AND fecha_llegada = CURDATE()");
            r[3] = tx.scalar("SELECT COUNT(*) FROM estancias WHERE estado = 'ACTIVA' AND salida_prevista <= CURDATE()");
            r[4] = tx.scalar("SELECT COALESCE(SUM(total),0) FROM facturas WHERE estado = 'EMITIDA' AND DATE(fecha_emision) = CURDATE()");
            r[5] = tx.scalar("SELECT COUNT(*) FROM comandas WHERE estado IN ('ABIERTA','CERRADA')");
            r[6] = tx.query("SELECT tipo, severidad, mensaje FROM v_alertas ORDER BY FIELD(severidad,'ALTA','MEDIA'), fecha LIMIT 100");
            r[7] = tx.query("SELECT DATE(fecha_emision) AS k, SUM(total) AS v FROM facturas WHERE estado = 'EMITIDA' AND DATE(fecha_emision) BETWEEN ? AND ? GROUP BY DATE(fecha_emision) ORDER BY k", d, h);
            r[8] = tx.query("SELECT fd.tipo AS k, SUM(fd.subtotal) AS v FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura "
                    + "WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN ? AND ? GROUP BY fd.tipo ORDER BY v DESC", d, h);
            r[9] = tx.query("SELECT k, SUM(v) AS v FROM (SELECT p.metodo AS k, p.monto AS v FROM pagos p JOIN facturas f ON f.id_factura = p.id_factura "
                    + "WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN ? AND ? "
                    + "UNION ALL SELECT 'CREDITO', total FROM facturas WHERE estado = 'EMITIDA' AND condicion_pago = 'CREDITO' AND DATE(fecha_emision) BETWEEN ? AND ?) z "
                    + "GROUP BY k ORDER BY v DESC", d, h, d, h);
            r[10] = tx.query("SELECT fd.descripcion AS k, SUM(fd.subtotal) AS v FROM factura_detalle fd JOIN facturas f ON f.id_factura = fd.id_factura "
                    + "WHERE f.estado = 'EMITIDA' AND fd.tipo IN ('ALIMENTOS','BEBIDAS','SERVICIO') AND DATE(f.fecha_emision) BETWEEN ? AND ? "
                    + "GROUP BY fd.descripcion ORDER BY v DESC LIMIT 8", d, h);
            r[11] = tx.query("SELECT HOUR(fecha_emision) AS k, SUM(total) AS v FROM facturas WHERE estado = 'EMITIDA' AND DATE(fecha_emision) BETWEEN ? AND ? GROUP BY HOUR(fecha_emision) ORDER BY k", d, h);
            r[12] = tx.query("SELECT COALESCE(pv.nombre, 'General') AS k, SUM(f.total) AS v FROM facturas f JOIN cai_autorizaciones c ON c.id_cai = f.id_cai "
                    + "LEFT JOIN puntos_venta pv ON pv.id_punto = c.id_punto_venta WHERE f.estado = 'EMITIDA' AND DATE(f.fecha_emision) BETWEEN ? AND ? GROUP BY COALESCE(pv.nombre, 'General') ORDER BY v DESC", d, h);
            r[13] = tx.scalar("SELECT COUNT(*) FROM v_alertas WHERE tipo IN ('CAI_VENCIDO','CAI_AGOTADO','PUNTO_SIN_CAI')");
            return r;
        }), r -> {
            lblLibres.setText(String.valueOf(r[0]));
            lblOcupadas.setText(String.valueOf(r[1]));
            lblLlegadas.setText(String.valueOf(r[2]));
            lblSalidas.setText(String.valueOf(r[3]));
            lblVentas.setText(Ui.lempiras(r[4]));
            lblComandas.setText(String.valueOf(r[5]));
            @SuppressWarnings("unchecked") List<Map<String, Object>> filas = (List<Map<String, Object>>) r[6];
            Ui.llenar(modeloAlertas, filas, "tipo", "severidad", "mensaje");
            long bloqueos = r[13] == null ? 0 : ((Number) r[13]).longValue();
            banner.setVisible(bloqueos > 0);
            banner.setText("<html><b>Atención:</b> hay " + bloqueos + " problema(s) con el CAI. Los puntos de venta sin CAI vigente <b>no pueden facturar</b>. Revise «CAI y libro de ventas».</html>");

            // ventas por día (rellena con 0 los días sin ventas)
            @SuppressWarnings("unchecked") List<Map<String, Object>> dias = (List<Map<String, Object>>) r[7];
            Map<LocalDate, Double> porDia = new java.util.HashMap<>();
            for (Map<String, Object> f : dias) porDia.put(((java.sql.Date) f.get("k")).toLocalDate(), Graficos.aDouble(f.get("v")));
            List<String> et = new ArrayList<>(); List<Double> val = new ArrayList<>();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
            for (LocalDate x = d; !x.isAfter(h); x = x.plusDays(1)) { et.add(x.format(fmt)); val.add(porDia.getOrDefault(x, 0.0)); }
            gDias.datos(et, val);

            gTipos.datos(claves(r[8], false), valores(r[8]));
            gPagos.datos(claves(r[9], false), valores(r[9]));
            gProductos.datos(claves(r[10], false), valores(r[10]));
            // por hora: 0..23 completos sería ruidoso; se muestran las horas con ventas
            gHoras.datos(claves(r[11], true), valores(r[11]));
            gPuntos.datos(claves(r[12], false), valores(r[12]));
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> claves(Object o, boolean hora) {
        List<String> k = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) o) k.add(hora ? String.format("%02d h", ((Number) f.get("k")).intValue()) : Db.str(f.get("k")));
        return k;
    }

    @SuppressWarnings("unchecked")
    private static List<Double> valores(Object o) {
        List<Double> v = new ArrayList<>();
        for (Map<String, Object> f : (List<Map<String, Object>>) o) v.add(Graficos.aDouble(f.get("v")));
        return v;
    }
}
