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

/** Catálogo de proveedores con filtros, ficha, límite de crédito, estado de cuenta y antigüedad de saldos. */
public class ProveedoresPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Código", "Proveedor", "RTN", "Zona", "Teléfono", "Días crédito", "Límite", "Saldo por pagar", "Exento", "Estado");
    private final JTable tabla = Ui.tabla(modelo);
    private final JTextField buscar = new JTextField(18);
    private final JComboBox<AccionesHotel.Opcion> cmbZona = new JComboBox<>();
    private final JComboBox<String> cmbEstado = new JComboBox<>(new String[]{"Activos", "Inactivos", "Todos"});
    private final JCheckBox soloSaldo = new JCheckBox("Solo con saldo");
    private final JLabel lblResumen = new JLabel(" ");
    private boolean cargando = false;

    public ProveedoresPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel norte = new JPanel(new BorderLayout());
        norte.add(Ui.titulo("Proveedores"), BorderLayout.NORTH);
        buscar.putClientProperty("JTextField.placeholderText", "Buscar por nombre, código, RTN o teléfono");
        norte.add(Ui.fila(buscar, new JLabel("Zona:"), cmbZona, new JLabel("Estado:"), cmbEstado, soloSaldo), BorderLayout.CENTER);
        add(norte, BorderLayout.NORTH);
        tabla.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tabla, 6, 7, 8);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        JPanel sur = new JPanel(new BorderLayout());
        sur.add(Ui.fila(Ui.botonPrimario("Nuevo proveedor", () -> { if (ProveedorForm.editar(this, null)) alMostrar(); }),
                Ui.boton("Ficha / editar", this::editar), Ui.boton("Estado de cuenta", this::estadoCuenta),
                Ui.boton("Antigüedad de saldos", () -> ReporteDialog.mostrar(this, "Antigüedad de saldos por pagar (análisis de vencimiento)", null)),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, modelo, "proveedores_" + LocalDate.now()))), BorderLayout.CENTER);
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
            w.append(" AND (p.nombre LIKE ? OR p.codigo LIKE ? OR p.rtn LIKE ? OR p.telefono LIKE ? OR p.celular LIKE ?)");
            for (int i = 0; i < 5; i++) p.add("%" + q + "%");
        }
        int z = cmbZona.getItemCount() == 0 ? 0 : AccionesHotel.idDe(cmbZona);
        if (z > 0) { w.append(" AND p.id_zona = ?"); p.add(z); }
        if (cmbEstado.getSelectedIndex() == 0) w.append(" AND p.activo = 1"); else if (cmbEstado.getSelectedIndex() == 1) w.append(" AND p.activo = 0");
        String having = soloSaldo.isSelected() ? " HAVING saldo > 0" : "";
        String sql = "SELECT p.id_proveedor, p.codigo, p.nombre, p.rtn, COALESCE(z.nombre, '') AS zona, COALESCE(p.telefono, p.celular, '') AS tel, p.dias_credito, p.limite_credito, "
                + "COALESCE((SELECT SUM(x.saldo) FROM cuentas_por_pagar x WHERE x.id_proveedor = p.id_proveedor AND x.estado = 'PENDIENTE'), 0) AS saldo, "
                + "IF(p.es_exento = 1, 'Sí', '') AS exento, IF(p.activo = 1, 'Activo', 'Inactivo') AS estado FROM proveedores p LEFT JOIN zonas z ON z.id_zona = p.id_zona"
                + w + having + " ORDER BY p.nombre LIMIT 2000";
        Ui.enSegundoPlano(this, () -> Db.query(sql, p.toArray()), filas -> {
            modelo.setRowCount(0);
            BigDecimal ts = BigDecimal.ZERO;
            for (Map<String, Object> f : filas) {
                BigDecimal lim = Db.dec(f.get("limite_credito")), sal = Db.dec(f.get("saldo"));
                ts = ts.add(sal);
                modelo.addRow(new Object[]{f.get("id_proveedor"), f.get("codigo") == null ? "" : f.get("codigo"), f.get("nombre"), f.get("rtn") == null ? "" : f.get("rtn"), f.get("zona"),
                        f.get("tel"), f.get("dias_credito"), lim.signum() == 0 ? "sin límite" : Ui.numero(lim), Ui.numero(sal), f.get("exento"), f.get("estado")});
            }
            lblResumen.setText(filas.size() + " proveedor(es)   |   Saldo por pagar: " + Ui.lempiras(ts));
        });
    }

    private int seleccionado() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) Ui.info(this, "Seleccione un proveedor.");
        return id;
    }

    private void editar() {
        int id = seleccionado();
        if (id > 0 && ProveedorForm.editar(this, id)) alMostrar();
    }

    private void estadoCuenta() {
        int id = seleccionado();
        if (id > 0) ReporteDialog.mostrar(this, "Estado de cuenta de proveedor", Map.of("proveedor", id));
    }
}
