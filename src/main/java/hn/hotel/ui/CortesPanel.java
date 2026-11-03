package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.CorteService;
import hn.hotel.service.Impresion;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Cortes de caja: X al cambiar de turno, Z al cerrar el día. */
public class CortesPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Tipo", "Fecha", "Hecho por", "Facturas", "Total ventas", "Efectivo contado", "Diferencia", "En Z #");
    private final JTable tabla = Ui.tabla(modelo);
    private final JLabel lblPendiente = new JLabel(" ");
    private final JSpinner desde = Campos.fecha(LocalDate.now().minusDays(30));
    private final JSpinner hasta = Campos.fecha(LocalDate.now());

    public CortesPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel norte = new JPanel(new BorderLayout(0, 6));
        norte.add(Ui.titulo("Cortes de caja"), BorderLayout.NORTH);
        norte.add(new JLabel("<html><b>Corte X</b> = cambio de turno: cierra SUS ventas y deja su caja en blanco (las mesas abiertas siguen abiertas). "
                + "<b>Corte Z</b> = cierre del día: suma todos los X.</html>"), BorderLayout.CENTER);
        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        acciones.add(Ui.botonPrimario("Hacer corte X (mi turno)", this::corteX));
        acciones.add(Ui.boton("Hacer corte Z (cierre del día)", this::corteZ));
        acciones.add(lblPendiente);
        JPanel sur2 = new JPanel(new BorderLayout());
        sur2.add(acciones, BorderLayout.NORTH);
        sur2.add(Ui.fila(new JLabel("Cortes desde:"), desde, new JLabel("hasta:"), hasta, Ui.boton("Mostrar", this::alMostrar)), BorderLayout.SOUTH);
        norte.add(sur2, BorderLayout.SOUTH);
        add(norte, BorderLayout.NORTH);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        tabla.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tabla, 5);
        Ui.alinearDerecha(tabla, 6);
        Ui.alinearDerecha(tabla, 7);
        add(Ui.fila(Ui.boton("Ver / imprimir / guardar", this::ver)), BorderLayout.SOUTH);
        tabla.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) ver(); }
        });
    }

    @Override public void alMostrar() {
        Ui.llenar(modelo, Db.query("SELECT c.id_corte, c.tipo, DATE_FORMAT(c.creado_en, '%d/%m/%Y %H:%i') AS fecha, u.nombre AS quien, c.n_facturas, "
                + "FORMAT(c.total_ventas, 2) AS total, FORMAT(c.efectivo_contado, 2) AS contado, FORMAT(c.diferencia, 2) AS dif, c.id_z "
                + "FROM cortes c LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario WHERE DATE(c.creado_en) BETWEEN ? AND ? ORDER BY c.id_corte DESC",
                java.sql.Date.valueOf(Campos.valor(desde)), java.sql.Date.valueOf(Campos.valor(hasta))),
                "id_corte", "tipo", "fecha", "quien", "n_facturas", "total", "contado", "dif", "id_z");
        try {
            Map<String, Object> p = CorteService.vistaPreviaX(Sesion.idUsuario);
            lblPendiente.setText("<html>Sin cortar (suyo): <b>" + p.get("n_facturas") + "</b> factura(s), total <b>" + Ui.lempiras(p.get("total_ventas"))
                    + "</b>, efectivo " + Ui.lempiras(p.get("pago_efectivo")) + "</html>");
        } catch (RuntimeException e) { lblPendiente.setText("Ejecute el script 13_cortes_caja.sql"); }
    }

    private void ver() {
        int id = Ui.idSeleccionado(tabla);
        if (id <= 0) { Ui.info(this, "Seleccione un corte."); return; }
        Ui.seguro(this, () -> CorteVista.mostrar(this, id));
    }

    private static BigDecimal monto(JTextField t, String que) {
        try { BigDecimal v = new BigDecimal(t.getText().trim().replace(",", "")); if (v.signum() < 0) throw new NumberFormatException(); return v; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Escriba un monto válido en «" + que + "»."); }
    }

    private void corteX() {
        if (!Sesion.permite("pos.corte_x")) { Autorizacion.ejecutar(this, "pos.corte_x", "Hacer corte X", this::corteX); return; }
        Ui.seguro(this, () -> {
            Map<String, Object> p = CorteService.vistaPreviaX(Sesion.idUsuario);
            JTextField fondo = new JTextField("0.00", 10), contado = new JTextField(10);
            JLabel esperado = new JLabel();
            Runnable calc = () -> {
                try { esperado.setText("Efectivo esperado en caja: " + Ui.lempiras(monto(fondo, "Fondo inicial").add(Db.dec(p.get("pago_efectivo"))))); }
                catch (RuntimeException e) { esperado.setText(" "); }
            };
            fondo.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { calc.run(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { calc.run(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { calc.run(); }
            });
            calc.run();
            JTextField notas = new JTextField(24);
            Campos.Formulario f = new Campos.Formulario()
                    .nota("Facturas del turno: " + p.get("n_facturas") + " · Total ventas: " + Ui.lempiras(p.get("total_ventas"))
                            + " · Efectivo: " + Ui.lempiras(p.get("pago_efectivo")) + " · Tarjeta: " + Ui.lempiras(p.get("pago_tarjeta")))
                    .nota("Cuentas abiertas ahora: " + p.get("mesas_abiertas") + " (no se incluyen: se cobran en el siguiente turno)")
                    .agregar("Fondo de caja con que empezó el turno:", fondo)
                    .agregar("Efectivo que cuenta ahora en caja:", contado)
                    .agregar("", esperado)
                    .agregar("Notas (opcional):", notas);
            int[] idCorte = {0};
            boolean ok = Campos.dialogo(this, "Corte X - cambio de turno", f, () -> {
                BigDecimal fo = monto(fondo, "Fondo inicial"), co = monto(contado, "Efectivo contado");
                idCorte[0] = CorteService.hacerX(fo, co, notas.getText());
                return true;
            });
            if (ok && idCorte[0] > 0) { alMostrar(); CorteVista.mostrar(this, idCorte[0]); }
        });
    }

    private void corteZ() {
        if (!Sesion.permite("pos.corte_z")) { Autorizacion.ejecutar(this, "pos.corte_z", "Hacer corte Z (cierre del día)", this::corteZ); return; }
        Ui.seguro(this, () -> {
            Map<String, Object> p = CorteService.vistaPreviaZ();
            if (Db.ent(p.get("cortes_x")) == 0) { Ui.info(this, "No hay cortes X pendientes. Cada cajero debe hacer su corte X antes del Z."); return; }
            if (!CorteService.pendientesSinCorte().isEmpty()) {
                StringBuilder sb = new StringBuilder("Estos cajeros todavía tienen ventas sin corte X:\n");
                for (Map<String, Object> q : CorteService.pendientesSinCorte()) sb.append("  • ").append(Db.str(q.get("nombre"))).append(": ").append(q.get("n")).append(" factura(s)\n");
                Ui.info(this, sb.toString());
                return;
            }
            JTextField notas = new JTextField(24);
            Campos.Formulario f = new Campos.Formulario()
                    .nota("Se cerrará el día con " + p.get("cortes_x") + " corte(s) X. Total ventas: " + Ui.lempiras(p.get("total_ventas"))
                            + " · Diferencia de caja: " + Ui.lempiras(p.get("diferencia")))
                    .nota("Cuentas abiertas ahora: " + p.get("mesas_abiertas") + " (pasan al día siguiente)")
                    .agregar("Notas (opcional):", notas);
            int[] idCorte = {0};
            boolean ok = Campos.dialogo(this, "Corte Z - cierre del día", f, () -> { idCorte[0] = CorteService.hacerZ(notas.getText()); return true; });
            if (ok && idCorte[0] > 0) { alMostrar(); CorteVista.mostrar(this, idCorte[0]); }
        });
    }
}
