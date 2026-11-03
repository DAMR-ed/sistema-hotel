package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Catálogo de clientes con filtros: ficha, límite de crédito, exoneración, estado de cuenta, análisis y tablero. */
public class ClientesPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Código", "Cliente", "RTN", "Tipo", "Zona", "Teléfono", "Límite", "Saldo", "Disponible", "Exento", "Estado");
    private final JTable tabla = Ui.tabla(modelo);
    private final JTextField buscar = new JTextField(18);
    private final JComboBox<AccionesHotel.Opcion> cmbZona = new JComboBox<>();
    private final JComboBox<String> cmbEstado = new JComboBox<>(new String[]{"Activos", "Inactivos", "Todos"});
    private final JCheckBox soloSaldo = new JCheckBox("Solo con saldo");
    private final JCheckBox soloExentos = new JCheckBox("Solo exentos");
    private final JLabel lblResumen = new JLabel(" ");
    private boolean cargando = false;

    public ClientesPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel norte = new JPanel(new BorderLayout());
        norte.add(Ui.titulo("Clientes"), BorderLayout.NORTH);
        buscar.putClientProperty("JTextField.placeholderText", "Buscar por nombre, código, RTN o teléfono");
        norte.add(Ui.fila(buscar, new JLabel("Zona:"), cmbZona, new JLabel("Estado:"), cmbEstado, soloSaldo, soloExentos), BorderLayout.CENTER);
        add(norte, BorderLayout.NORTH);
        tabla.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tabla, 7, 8, 9);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        JPanel sur = new JPanel(new BorderLayout());
        sur.add(Ui.fila(Ui.botonPrimario("Nuevo cliente", () -> { if (ClienteForm.editar(this, null)) alMostrar(); }),
                Ui.boton("Ficha / editar", this::editar), Ui.boton("Estado de cuenta", this::estadoCuenta), Ui.boton("Análisis del cliente", this::analisis),
                Ui.boton("Tablero de clientes", () -> ClientesTablero.mostrar(this)),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, modelo, "clientes_" + LocalDate.now()))), BorderLayout.CENTER);
        sur.add(lblResumen, BorderLayout.SOUTH);
        add(sur, BorderLayout.SOUTH);

        buscar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { recargar(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { recargar(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { recargar(); }
        });
        cmbZona.addActionListener(e -> recargar());
        cmbEstado.addActionListener(e -> recargar());
        soloSaldo.addActionListener(e -> recargar());
        soloExentos.addActionListener(e -> recargar());
        tabla.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) editar(); }
        });
    }

    @Override public void alMostrar() {
        cargando = true;
        int antes = cmbZona.getItemCount() == 0 ? 0 : AccionesHotel.idDe(cmbZona);
        cmbZona.removeAllItems();
        cmbZona.addItem(new AccionesHotel.Opcion(0, "(todas)"));
        for (var z : Db.query("SELECT id_zona, nombre FROM zonas ORDER BY nombre")) cmbZona.addItem(new AccionesHotel.Opcion(Db.ent(z.get("id_zona")), Db.str(z.get("nombre"))));
        AccionesHotel.seleccionar(cmbZona, antes);
        cargando = false;
        recargar();
    }

    private void recargar() {
        if (cargando) return;
        List<Object> p = new ArrayList<>();
        StringBuilder w = new StringBuilder(" WHERE 1 = 1");
        String q = buscar.getText().trim();
        if (!q.isEmpty()) {
            w.append(" AND (c.nombre LIKE ? OR c.codigo LIKE ? OR c.rtn LIKE ? OR c.telefono LIKE ? OR c.celular LIKE ?)");
            for (int i = 0; i < 5; i++) p.add("%" + q + "%");
        }
        int z = cmbZona.getItemCount() == 0 ? 0 : AccionesHotel.idDe(cmbZona);
        if (z > 0) { w.append(" AND c.id_zona = ?"); p.add(z); }
        if (cmbEstado.getSelectedIndex() == 0) w.append(" AND c.activo = 1"); else if (cmbEstado.getSelectedIndex() == 1) w.append(" AND c.activo = 0");
        if (soloExentos.isSelected()) w.append(" AND c.es_exento = 1");
        String having = soloSaldo.isSelected() ? " HAVING saldo > 0" : "";
        String sql = "SELECT c.id_cliente_credito, c.codigo, c.nombre, c.rtn, c.tipo_cliente, COALESCE(z.nombre, '') AS zona, COALESCE(c.telefono, c.celular, '') AS tel, c.limite_credito, "
                + "COALESCE((SELECT SUM(x.saldo) FROM cuentas_por_cobrar x WHERE x.id_cliente_credito = c.id_cliente_credito AND x.estado IN ('PENDIENTE','PARCIAL')), 0) AS saldo, "
                + "IF(c.es_exento = 1, 'Sí', '') AS exento, IF(c.activo = 1, 'Activo', 'Inactivo') AS estado FROM clientes_credito c LEFT JOIN zonas z ON z.id_zona = c.id_zona"
                + w + having + " ORDER BY c.nombre LIMIT 2000";
        Ui.enSegundoPlano(this, () -> Db.query(sql, p.toArray()), filas -> {
            modelo.setRowCount(0);
            BigDecimal tl = BigDecimal.ZERO, ts = BigDecimal.ZERO;
            for (Map<String, Object> f : filas) {
                BigDecimal lim = Db.dec(f.get("limite_credito")), sal = Db.dec(f.get("saldo"));
                tl = tl.add(lim); ts = ts.add(sal);
                modelo.addRow(new Object[]{f.get("id_cliente_credito"), f.get("codigo") == null ? "" : f.get("codigo"), f.get("nombre"), f.get("rtn") == null ? "" : f.get("rtn"),
                        f.get("tipo_cliente"), f.get("zona"), f.get("tel"), Ui.numero(lim), Ui.numero(sal), Ui.numero(lim.subtract(sal)), f.get("exento"), f.get("estado")});
            }
            lblResumen.setText(filas.size() + " cliente(s)   |   Crédito otorgado: " + Ui.lempiras(tl) + "   |   Saldo por cobrar: " + Ui.lempiras(ts));
        });
    }

    private int seleccionado() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) Ui.info(this, "Seleccione un cliente.");
        return id;
    }

    private void editar() {
        int id = seleccionado();
        if (id > 0 && ClienteForm.editar(this, id)) alMostrar();
    }

    private void estadoCuenta() {
        int id = seleccionado();
        if (id > 0) ReporteDialog.mostrar(this, "Estado de cuenta de cliente", Map.of("cliente", id));
    }

    private void analisis() {
        int id = seleccionado();
        if (id < 0) return;
        Map<String, Object> c = Db.uno("SELECT nombre, limite_credito, dias_credito FROM clientes_credito WHERE id_cliente_credito = ?", id);
        Map<String, Object> v = Db.uno("SELECT COUNT(*) AS n, COALESCE(SUM(total),0) AS total, COALESCE(AVG(total),0) AS ticket, MIN(DATE(fecha_emision)) AS primera, MAX(DATE(fecha_emision)) AS ultima "
                + "FROM facturas WHERE id_cliente_credito = ? AND estado = 'EMITIDA'", id);
        Map<String, Object> s = Db.uno("SELECT COALESCE(SUM(saldo),0) AS saldo, COALESCE(SUM(IF(fecha_vencimiento < CURDATE(), saldo, 0)),0) AS vencido FROM cuentas_por_cobrar "
                + "WHERE id_cliente_credito = ? AND estado IN ('PENDIENTE','PARCIAL')", id);
        Object diasPago = Db.scalar("SELECT ROUND(AVG(DATEDIFF(t.ultimo, t.emision)), 0) FROM (SELECT x.fecha_emision AS emision, MAX(DATE(a.fecha)) AS ultimo FROM cuentas_por_cobrar x "
                + "JOIN abonos a ON a.id_cxc = x.id_cxc WHERE x.id_cliente_credito = ? AND x.estado = 'PAGADA' GROUP BY x.id_cxc, x.fecha_emision) t", id);
        Object rank = Db.scalar("SELECT COUNT(*) + 1 FROM (SELECT id_cliente_credito, SUM(total) AS t FROM facturas WHERE estado = 'EMITIDA' AND id_cliente_credito IS NOT NULL "
                + "GROUP BY id_cliente_credito) q WHERE q.t > (SELECT COALESCE(SUM(total),0) FROM facturas WHERE estado = 'EMITIDA' AND id_cliente_credito = ?)", id);
        Object total = Db.scalar("SELECT COUNT(DISTINCT id_cliente_credito) FROM facturas WHERE estado = 'EMITIDA' AND id_cliente_credito IS NOT NULL");
        StringBuilder top = new StringBuilder();
        for (var t : Db.query("SELECT d.descripcion, ROUND(SUM(d.subtotal), 2) AS importe FROM factura_detalle d JOIN facturas f ON f.id_factura = d.id_factura "
                + "WHERE f.id_cliente_credito = ? AND f.estado = 'EMITIDA' GROUP BY d.descripcion ORDER BY SUM(d.subtotal) DESC LIMIT 5", id))
            top.append("<li>").append(Db.str(t.get("descripcion"))).append(" — ").append(Ui.lempiras(Db.dec(t.get("importe")))).append("</li>");
        int n = Db.ent(v.get("n"));
        String html = "<html><body style='width:430px'><h3>" + Db.str(c.get("nombre")) + "</h3>"
                + "<b>Compras:</b> " + n + " factura(s) por " + Ui.lempiras(Db.dec(v.get("total"))) + "<br>"
                + "<b>Ticket promedio:</b> " + Ui.lempiras(Db.dec(v.get("ticket"))) + "<br>"
                + "<b>Primera / última compra:</b> " + (v.get("primera") == null ? "-" : Ui.texto(Ui.fecha(v.get("primera")))) + " / " + (v.get("ultima") == null ? "-" : Ui.texto(Ui.fecha(v.get("ultima")))) + "<br>"
                + (n > 0 && rank != null && total != null ? "<b>Lugar entre los clientes:</b> " + Db.ent(rank) + " de " + Db.ent(total) + " (por monto comprado)<br>" : "")
                + "<b>Crédito:</b> límite " + Ui.lempiras(Db.dec(c.get("limite_credito"))) + " a " + Db.ent(c.get("dias_credito")) + " días<br>"
                + "<b>Saldo por cobrar:</b> " + Ui.lempiras(Db.dec(s.get("saldo"))) + " (vencido " + Ui.lempiras(Db.dec(s.get("vencido"))) + ")<br>"
                + "<b>Días promedio que tarda en pagar:</b> " + (diasPago == null ? "sin cuentas pagadas todavía" : Db.ent(diasPago)) + "<br>"
                + (top.length() > 0 ? "<br><b>Lo que más compra:</b><ul>" + top + "</ul>" : "")
                + "<i>Solo cuentan las facturas hechas con este cliente seleccionado en el cobro.</i></body></html>";
        JOptionPane.showMessageDialog(this, new JLabel(html), "Análisis del cliente", JOptionPane.PLAIN_MESSAGE);
    }
}
