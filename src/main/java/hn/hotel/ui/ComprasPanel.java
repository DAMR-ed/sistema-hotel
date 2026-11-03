package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compras a proveedores (suben el inventario y el costo promedio) y catálogo de proveedores. */
public class ComprasPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mCompras = Ui.modelo("Id", "Fecha", "Proveedor", "Documento", "Estado", "Total");
    private final JTable tCompras = Ui.tabla(mCompras);
    private final DefaultTableModel mProv = Ui.modelo("Id", "Proveedor", "RTN", "Contacto", "Teléfono", "Activo");
    private final JTable tProv = Ui.tabla(mProv);

    public ComprasPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Compras y proveedores"), BorderLayout.NORTH);

        JPanel compras = new JPanel(new BorderLayout(6, 6));
        tCompras.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tCompras, 5);
        compras.add(new JScrollPane(tCompras), BorderLayout.CENTER);
        compras.add(Ui.fila(Ui.boton("Nueva compra", this::nuevaCompra), Ui.boton("Ver detalle", this::verDetalle),
                Ui.boton("Recibir (borrador)", this::recibir), Ui.boton("Anular borrador", this::anularBorrador)), BorderLayout.SOUTH);

        JPanel prov = new JPanel(new BorderLayout(6, 6));
        tProv.getColumnModel().getColumn(0).setMaxWidth(50);
        prov.add(new JScrollPane(tProv), BorderLayout.CENTER);
        prov.add(Ui.fila(Ui.boton("Nuevo proveedor", () -> formProveedor(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tProv); if (id > 0) formProveedor(id); else Ui.info(this, "Seleccione un proveedor."); })), BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Compras", compras);
        tabs.addTab("Proveedores", prov);
        add(tabs, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> new Object[]{
                tx.query("SELECT c.id_compra, c.fecha, p.nombre, c.numero_documento, c.estado, c.total FROM compras c JOIN proveedores p ON p.id_proveedor = c.id_proveedor ORDER BY c.id_compra DESC LIMIT 300"),
                tx.query("SELECT id_proveedor, nombre, rtn, contacto, telefono, IF(activo=1,'Sí','No') AS act FROM proveedores ORDER BY nombre")}),
            r -> {
                @SuppressWarnings("unchecked") var a = (List<Map<String, Object>>) r[0];
                @SuppressWarnings("unchecked") var b = (List<Map<String, Object>>) r[1];
                Ui.llenar(mCompras, a, "id_compra", "fecha", "nombre", "numero_documento", "estado", "total");
                Ui.llenar(mProv, b, "id_proveedor", "nombre", "rtn", "contacto", "telefono", "act");
            });
    }

    private void formProveedor(Integer id) {
        Map<String, Object> p = id == null ? null : Db.uno("SELECT * FROM proveedores WHERE id_proveedor = ?", id);
        JTextField nombre = new JTextField(p == null ? "" : Db.str(p.get("nombre")), 24), rtn = new JTextField(p == null ? "" : Db.str(p.get("rtn")), 14),
                contacto = new JTextField(p == null ? "" : Db.str(p.get("contacto")), 18), tel = new JTextField(p == null ? "" : Db.str(p.get("telefono")), 14),
                email = new JTextField(p == null ? "" : Db.str(p.get("email")), 22), dir = new JTextField(p == null ? "" : Db.str(p.get("direccion")), 24);
        JCheckBox activo = new JCheckBox("Activo", p == null || Db.ent(p.get("activo")) == 1);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("RTN (14 dígitos):", rtn).agregar("Contacto:", contacto).agregar("Teléfono:", tel)
         .agregar("Correo:", email).agregar("Dirección:", dir).agregar("", activo);
        Campos.dialogo(this, id == null ? "Nuevo proveedor" : "Editar proveedor", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            String r = rtn.getText().trim();
            if (!r.isEmpty() && !r.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
            Object[] v = {nombre.getText().trim(), r.isEmpty() ? null : r, nv(contacto), nv(tel), nv(email), nv(dir), activo.isSelected() ? 1 : 0};
            if (id == null) Db.insert("INSERT INTO proveedores (nombre, rtn, contacto, telefono, email, direccion, activo) VALUES (?,?,?,?,?,?,?)", v);
            else Db.exec("UPDATE proveedores SET nombre=?, rtn=?, contacto=?, telefono=?, email=?, direccion=?, activo=? WHERE id_proveedor=?",
                    v[0], v[1], v[2], v[3], v[4], v[5], v[6], id);
            return true;
        });
        alMostrar();
    }

    private static String nv(JTextField t) { return t.getText().isBlank() ? null : t.getText().trim(); }

    // ------------------------------------------------------------ compras
    private record Linea(int idInsumo, String nombre, String base, BigDecimal cantidadBase, BigDecimal total) {
        BigDecimal costoUnitario() { return total.divide(cantidadBase, 6, RoundingMode.HALF_UP); }
    }

    private void nuevaCompra() {
        var provs = Db.query("SELECT id_proveedor, nombre FROM proveedores WHERE activo = 1 ORDER BY nombre");
        var insumos = Db.query("SELECT id_insumo, nombre, unidad_base FROM insumos WHERE activo = 1 ORDER BY nombre");
        if (provs.isEmpty()) { Ui.info(this, "Primero registre un proveedor (pestaña Proveedores)."); return; }
        if (insumos.isEmpty()) { Ui.info(this, "Primero registre insumos en el módulo Inventario."); return; }

        JComboBox<AccionesHotel.Opcion> prov = AccionesHotel.combo(provs, "id_proveedor", "nombre", false);
        JSpinner fecha = Campos.fecha(LocalDate.now());
        JTextField doc = new JTextField(16);
        Map<Integer, Linea> lineas = new LinkedHashMap<>();
        DefaultTableModel m = Ui.modelo("Insumo", "Cantidad (base)", "Unidad", "Total línea", "Costo/unidad base");
        JTable t = Ui.tabla(m);
        JLabel lblTotal = new JLabel("Total: L 0.00");
        Runnable repintar = () -> {
            m.setRowCount(0);
            BigDecimal tot = BigDecimal.ZERO;
            for (Linea l : lineas.values()) {
                m.addRow(new Object[]{l.nombre(), Ui.numero(l.cantidadBase()), l.base(), Ui.numero(l.total()), l.costoUnitario().stripTrailingZeros().toPlainString()});
                tot = tot.add(l.total());
            }
            lblTotal.setText("Total: " + Ui.lempiras(tot));
        };
        JButton agregar = Ui.boton("Agregar línea...", () -> {
            JComboBox<AccionesHotel.Opcion> ins = AccionesHotel.combo(insumos, "id_insumo", "nombre", false);
            JComboBox<Unidades.Un> un = new JComboBox<>();
            Runnable cargarUn = () -> {
                un.removeAllItems();
                int idIns = AccionesHotel.idDe(ins);
                for (Map<String, Object> x : insumos) if (Db.ent(x.get("id_insumo")) == idIns)
                    for (Unidades.Un u : Unidades.para(Db.str(x.get("unidad_base")))) un.addItem(u);
            };
            ins.addActionListener(e -> cargarUn.run());
            cargarUn.run();
            JTextField cant = new JTextField("1", 8), tot = new JTextField(10);
            Campos.Formulario f = new Campos.Formulario();
            f.agregar("Insumo:", ins).agregar("Cantidad comprada:", cant).agregar("Unidad de la factura:", un)
             .agregar("Total de la línea (L):", tot).nota("Use el total que dice la factura del proveedor para esa línea.");
            Campos.dialogo(this, "Línea de compra", f, () -> {
                BigDecimal c = Ui.leerDinero(cant.getText(), "cantidad"), tl = Ui.leerDinero(tot.getText(), "total");
                if (c.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
                if (tl.signum() < 0) throw new IllegalArgumentException("El total no puede ser negativo.");
                int idIns = AccionesHotel.idDe(ins);
                String base = "", nombre = "";
                for (Map<String, Object> x : insumos) if (Db.ent(x.get("id_insumo")) == idIns) { base = Db.str(x.get("unidad_base")); nombre = Db.str(x.get("nombre")); }
                BigDecimal cb = c.multiply(((Unidades.Un) un.getSelectedItem()).factor());
                Linea previa = lineas.get(idIns);
                if (previa != null) { cb = cb.add(previa.cantidadBase()); tl = tl.add(previa.total()); }
                lineas.put(idIns, new Linea(idIns, nombre, base, cb, tl));
                return true;
            });
            repintar.run();
        });
        JButton quitar = Ui.boton("Quitar línea", () -> {
            int fila = t.getSelectedRow();
            if (fila < 0) return;
            String nombre = m.getValueAt(t.convertRowIndexToModel(fila), 0).toString();
            lineas.values().removeIf(l -> l.nombre().equals(nombre));
            repintar.run();
        });

        JPanel p = new JPanel(new BorderLayout(6, 6));
        Campos.Formulario cab = new Campos.Formulario();
        cab.agregar("Proveedor:", prov).agregar("Fecha:", fecha).agregar("N.º de factura del proveedor:", doc);
        p.add(cab, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(640, 220));
        p.add(sp, BorderLayout.CENTER);
        JPanel pie = Ui.fila(agregar, quitar, lblTotal);
        JCheckBox recibirYa = new JCheckBox("Recibir ahora (sumar al inventario)", true);
        pie.add(recibirYa);
        p.add(pie, BorderLayout.SOUTH);

        boolean ok = Campos.dialogo(this, "Nueva compra", p, () -> {
            if (lineas.isEmpty()) throw new IllegalArgumentException("Agregue al menos una línea.");
            Db.transaccion(tx -> {
                long idc = tx.insert("INSERT INTO compras (id_proveedor, fecha, numero_documento, id_usuario) VALUES (?,?,?,?)",
                        AccionesHotel.idDe(prov), Campos.valor(fecha), doc.getText().isBlank() ? null : doc.getText().trim(), Sesion.idUsuario);
                for (Linea l : lineas.values())
                    tx.exec("INSERT INTO compra_detalle (id_compra, id_insumo, cantidad, costo_unitario) VALUES (?,?,?,?)",
                            idc, l.idInsumo(), l.cantidadBase(), l.costoUnitario());
                if (recibirYa.isSelected()) tx.call("{CALL sp_recibir_compra(?,?)}", idc, Sesion.idUsuario);
                Bitacora.registrar("CREAR", "compra", idc, recibirYa.isSelected() ? "recibida" : "borrador");
                return null;
            });
            return true;
        });
        if (ok) alMostrar();
    }

    private void verDetalle() {
        int id = Ui.idSeleccionado(tCompras);
        if (id < 0) { Ui.info(this, "Seleccione una compra."); return; }
        DefaultTableModel m = Ui.modelo("Insumo", "Cantidad", "Unidad", "Costo unit.", "Importe");
        Ui.llenar(m, Db.query("SELECT i.nombre, d.cantidad, i.unidad_base, d.costo_unitario, ROUND(d.cantidad*d.costo_unitario,2) AS importe "
                + "FROM compra_detalle d JOIN insumos i ON i.id_insumo = d.id_insumo WHERE d.id_compra = ?", id), "nombre", "cantidad", "unidad_base", "costo_unitario", "importe");
        JTable t = Ui.tabla(m);
        Ui.alinearDerecha(t, 1, 3, 4);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(600, 240));
        JOptionPane.showMessageDialog(this, sp, "Compra #" + id, JOptionPane.PLAIN_MESSAGE);
    }

    private void recibir() {
        int id = Ui.idSeleccionado(tCompras);
        if (id < 0) { Ui.info(this, "Seleccione una compra."); return; }
        if (!Ui.confirmar(this, "¿Recibir la compra #" + id + " y sumarla al inventario?")) return;
        Ui.seguro(this, () -> {
            Db.sin(tx -> { tx.call("{CALL sp_recibir_compra(?,?)}", id, Sesion.idUsuario); return null; });
            Bitacora.registrar("RECIBIR", "compra", id, null);
            alMostrar();
        });
    }

    private void anularBorrador() {
        int id = Ui.idSeleccionado(tCompras);
        if (id < 0) { Ui.info(this, "Seleccione una compra."); return; }
        if (!Ui.confirmar(this, "¿Anular el borrador de compra #" + id + "?")) return;
        Ui.seguro(this, () -> {
            if (Db.exec("UPDATE compras SET estado = 'ANULADA' WHERE id_compra = ? AND estado = 'BORRADOR'", id) == 0)
                throw new IllegalArgumentException("Solo se anulan compras en borrador. Una compra recibida se corrige con un ajuste de inventario.");
            alMostrar();
        });
    }
}
