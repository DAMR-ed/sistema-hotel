package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Almacenes;
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
    private final DefaultTableModel mCompras = Ui.modelo("Id", "Fecha", "Proveedor", "Almacén", "Documento", "Estado", "Pago", "Vence", "Total");
    private final JTable tCompras = Ui.tabla(mCompras);
    private final DefaultTableModel mProv = Ui.modelo("Id", "Proveedor", "RTN", "Contacto", "Teléfono", "Días crédito", "Activo");
    private final JTable tProv = Ui.tabla(mProv);
    private final DefaultTableModel mCxp = Ui.modelo("Id", "Proveedor", "Factura", "Emisión", "Vence", "Estado", "Monto", "Saldo");
    private final JTable tCxp = Ui.tabla(mCxp);
    private final JCheckBox soloPendientes = new JCheckBox("Solo pendientes", true);
    private final JLabel lblCxp = new JLabel(" ");
    private List<Map<String, Object>> cxpFilas = List.of();

    public ComprasPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Compras y proveedores"), BorderLayout.NORTH);

        JPanel compras = new JPanel(new BorderLayout(6, 6));
        tCompras.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tCompras, 8);
        compras.add(new JScrollPane(tCompras), BorderLayout.CENTER);
        compras.add(Ui.fila(Ui.boton("Nueva compra", this::nuevaCompra), Ui.boton("Ver detalle", this::verDetalle),
                Ui.boton("Recibir (borrador)", this::recibir), Ui.boton("Anular borrador", this::anularBorrador)), BorderLayout.SOUTH);

        JPanel prov = new JPanel(new BorderLayout(6, 6));
        tProv.getColumnModel().getColumn(0).setMaxWidth(50);
        prov.add(new JScrollPane(tProv), BorderLayout.CENTER);
        prov.add(Ui.fila(Ui.boton("Nuevo proveedor", () -> formProveedor(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tProv); if (id > 0) formProveedor(id); else Ui.info(this, "Seleccione un proveedor."); })), BorderLayout.SOUTH);

        JPanel cxp = new JPanel(new BorderLayout(6, 6));
        tCxp.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tCxp, 6, 7);
        cxp.add(new JScrollPane(tCxp), BorderLayout.CENTER);
        soloPendientes.addActionListener(e -> repintarCxp());
        JPanel pieCxp = Ui.fila(Ui.boton("Registrar pago...", this::pagarCxp), Ui.boton("Ver pagos", this::verPagos),
                Ui.boton("Exportar CSV", () -> Exportar.csv(this, mCxp, "cuentas_por_pagar")), soloPendientes, lblCxp);
        cxp.add(pieCxp, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Compras", compras);
        tabs.addTab("Cuentas por pagar", cxp);
        tabs.addTab("Proveedores", prov);
        add(tabs, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> new Object[]{
                tx.query("SELECT c.id_compra, c.fecha, p.nombre, al.nombre AS almacen, c.numero_documento, c.estado, IF(c.condicion_pago='CREDITO','Crédito',CONCAT('Contado ',COALESCE(c.metodo_pago,''))) AS pago, c.fecha_vencimiento, c.total FROM compras c JOIN proveedores p ON p.id_proveedor = c.id_proveedor JOIN almacenes al ON al.id_almacen = c.id_almacen ORDER BY c.id_compra DESC LIMIT 300"),
                tx.query("SELECT id_proveedor, nombre, rtn, contacto, telefono, dias_credito, IF(activo=1,'Sí','No') AS act FROM proveedores ORDER BY nombre"),
                tx.query("SELECT x.id_cxp, p.nombre, c.numero_documento, x.fecha_emision, x.fecha_vencimiento, "
                        + "DATEDIFF(CURRENT_DATE, x.fecha_vencimiento) AS dias_vencida, x.monto, x.saldo, x.estado "
                        + "FROM cuentas_por_pagar x JOIN proveedores p ON p.id_proveedor = x.id_proveedor JOIN compras c ON c.id_compra = x.id_compra "
                        + "ORDER BY x.estado, x.fecha_vencimiento LIMIT 500")}),
            r -> {
                @SuppressWarnings("unchecked") var a = (List<Map<String, Object>>) r[0];
                @SuppressWarnings("unchecked") var b = (List<Map<String, Object>>) r[1];
                Ui.llenar(mCompras, a, "id_compra", "fecha", "nombre", "almacen", "numero_documento", "estado", "pago", "fecha_vencimiento", "total");
                Ui.llenar(mProv, b, "id_proveedor", "nombre", "rtn", "contacto", "telefono", "dias_credito", "act");
                @SuppressWarnings("unchecked") var c = (List<Map<String, Object>>) r[2];
                cxpFilas = c;
                repintarCxp();
            });
    }

    private void formProveedor(Integer id) {
        Map<String, Object> p = id == null ? null : Db.uno("SELECT * FROM proveedores WHERE id_proveedor = ?", id);
        JTextField nombre = new JTextField(p == null ? "" : Db.str(p.get("nombre")), 24), rtn = new JTextField(p == null ? "" : Db.str(p.get("rtn")), 14),
                contacto = new JTextField(p == null ? "" : Db.str(p.get("contacto")), 18), tel = new JTextField(p == null ? "" : Db.str(p.get("telefono")), 14),
                email = new JTextField(p == null ? "" : Db.str(p.get("email")), 22), dir = new JTextField(p == null ? "" : Db.str(p.get("direccion")), 24);
        JCheckBox activo = new JCheckBox("Activo", p == null || Db.ent(p.get("activo")) == 1);
        JTextField dias = new JTextField(p == null ? "0" : Db.str(p.get("dias_credito")), 6);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("RTN (14 dígitos):", rtn).agregar("Contacto:", contacto).agregar("Teléfono:", tel)
         .agregar("Correo:", email).agregar("Dirección:", dir).agregar("Días de crédito:", dias)
         .nota("Si compra a crédito, el vencimiento se calcula con estos días (0 = contado).").agregar("", activo);
        Campos.dialogo(this, id == null ? "Nuevo proveedor" : "Editar proveedor", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            String r = rtn.getText().trim();
            if (!r.isEmpty() && !r.matches("\\d{14}")) throw new IllegalArgumentException("El RTN debe tener 14 dígitos.");
            if (!dias.getText().trim().matches("\\d{1,3}")) throw new IllegalArgumentException("Los días de crédito deben ser un número (0 a 999).");
            int dc = Integer.parseInt(dias.getText().trim());
            Object[] v = {nombre.getText().trim(), r.isEmpty() ? null : r, nv(contacto), nv(tel), nv(email), nv(dir), activo.isSelected() ? 1 : 0, dc};
            if (id == null) Db.insert("INSERT INTO proveedores (nombre, rtn, contacto, telefono, email, direccion, activo, dias_credito) VALUES (?,?,?,?,?,?,?,?)", v);
            else Db.exec("UPDATE proveedores SET nombre=?, rtn=?, contacto=?, telefono=?, email=?, direccion=?, activo=?, dias_credito=? WHERE id_proveedor=?",
                    v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], id);
            return true;
        });
        alMostrar();
    }

    private static String nv(JTextField t) { return t.getText().isBlank() ? null : t.getText().trim(); }

    // ------------------------------------------------------------ compras
    private record Linea(int idInsumo, String nombre, String base, BigDecimal cantidadBase, BigDecimal total, Integer idProducto, BigDecimal precioVentaNuevo) {
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
        JComboBox<AccionesHotel.Opcion> almacen = AccionesHotel.combo(Db.query("SELECT id_almacen, nombre FROM almacenes WHERE activo = 1 ORDER BY es_principal DESC, nombre"), "id_almacen", "nombre", false);
        JComboBox<String> condicion = new JComboBox<>(new String[]{"CONTADO", "CREDITO"});
        JComboBox<String> metodo = new JComboBox<>(new String[]{"EFECTIVO", "TARJETA", "TRANSFERENCIA", "CHEQUE"});
        JTextField refPago = new JTextField(16);
        JSpinner vence = Campos.fecha(LocalDate.now());
        Runnable ajustarPago = () -> {
            boolean cred = "CREDITO".equals(condicion.getSelectedItem());
            metodo.setEnabled(!cred); refPago.setEnabled(!cred); vence.setEnabled(cred);
            if (cred) {
                Object dc = Db.scalar("SELECT dias_credito FROM proveedores WHERE id_proveedor = ?", AccionesHotel.idDe(prov));
                int d = dc == null ? 0 : Db.ent(dc);
                Campos.poner(vence, Campos.valor(fecha).plusDays(d > 0 ? d : 30));
            }
        };
        condicion.addActionListener(e -> ajustarPago.run());
        ajustarPago.run();
        prov.addActionListener(e -> ajustarPago.run());
        fecha.addChangeListener(e -> ajustarPago.run());
        Map<Integer, Linea> lineas = new LinkedHashMap<>();
        DefaultTableModel m = Ui.modelo("Insumo", "Cantidad (base)", "Unidad", "Total línea", "Costo/unidad base", "Nuevo precio venta");
        JTable t = Ui.tabla(m);
        JLabel lblTotal = new JLabel("Total: L 0.00");
        Runnable repintar = () -> {
            m.setRowCount(0);
            BigDecimal tot = BigDecimal.ZERO;
            for (Linea l : lineas.values()) {
                m.addRow(new Object[]{l.nombre(), Ui.numero(l.cantidadBase()), l.base(), Ui.numero(l.total()), l.costoUnitario().stripTrailingZeros().toPlainString(),
                        l.precioVentaNuevo() == null ? "" : Ui.numero(l.precioVentaNuevo())});
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
            JTextField cant = new JTextField("1", 8), unit = new JTextField(10), tot = new JTextField(10), nuevoPrecio = new JTextField(10);
            JLabel info = new JLabel(" ");
            Object[] prod = new Object[1];   // producto de reventa ligado a este insumo (si existe)
            Runnable cargarInfo = () -> {
                int idIns = AccionesHotel.idDe(ins);
                Map<String, Object> i = Db.uno("SELECT stock_actual, costo_promedio, unidad_base FROM insumos WHERE id_insumo = ?", idIns);
                Map<String, Object> pr = Db.uno("SELECT p.id_producto, p.nombre, p.precio FROM productos_menu p JOIN receta_detalle r ON r.id_producto = p.id_producto "
                        + "WHERE r.id_insumo = ? AND r.cantidad = 1 AND p.area_preparacion = 'NINGUNA' LIMIT 1", idIns);
                prod[0] = pr;
                StringBuilder sb = new StringBuilder("<html>");
                if (i != null) sb.append("Existencia: ").append(Ui.numero(i.get("stock_actual"))).append(' ').append(Db.str(i.get("unidad_base")))
                        .append(" · costo promedio: ").append(Ui.lempiras(i.get("costo_promedio"))).append("<br>");
                if (pr != null) sb.append("Se vende en el POS a ").append(Ui.lempiras(pr.get("precio"))).append(" (antes de ISV)");
                else sb.append("No se vende directo en el POS (se usa en recetas).");
                info.setText(sb.append("</html>").toString());
                nuevoPrecio.setEnabled(pr != null);
                nuevoPrecio.setText("");
            };
            ins.addActionListener(e -> cargarInfo.run());
            cargarInfo.run();
            Campos.Formulario f = new Campos.Formulario();
            f.agregar("Insumo:", ins).agregar("", info).agregar("Cantidad comprada:", cant).agregar("Unidad de la factura:", un)
             .agregar("Precio unitario (L):", unit).agregar("Total de la línea (L):", tot)
             .nota("Escriba el precio unitario o el total de la factura; si llena los dos, manda el total.")
             .agregar("Nuevo precio de venta (L):", nuevoPrecio)
             .nota("Opcional, solo para productos de reventa: cambia el precio del POS al guardar la compra.");
            Campos.dialogo(this, "Línea de compra", f, () -> {
                BigDecimal c = Ui.leerDinero(cant.getText(), "cantidad");
                if (c.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
                BigDecimal tl;
                if (!tot.getText().isBlank()) tl = Ui.leerDinero(tot.getText(), "total");
                else if (!unit.getText().isBlank()) tl = Ui.leerDinero(unit.getText(), "precio unitario").multiply(c).setScale(2, RoundingMode.HALF_UP);
                else throw new IllegalArgumentException("Escriba el precio unitario o el total de la línea.");
                if (tl.signum() < 0) throw new IllegalArgumentException("El importe no puede ser negativo.");
                BigDecimal pv = null;
                Integer idProd = null;
                @SuppressWarnings("unchecked") Map<String, Object> pr = (Map<String, Object>) prod[0];
                if (pr != null) idProd = Db.ent(pr.get("id_producto"));
                if (!nuevoPrecio.getText().isBlank()) {
                    if (pr == null) throw new IllegalArgumentException("Este insumo no se vende directo en el POS; no tiene precio de venta.");
                    pv = Ui.leerDinero(nuevoPrecio.getText(), "nuevo precio de venta");
                    if (pv.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
                }
                int idIns = AccionesHotel.idDe(ins);
                String base = "", nombre = "";
                for (Map<String, Object> x : insumos) if (Db.ent(x.get("id_insumo")) == idIns) { base = Db.str(x.get("unidad_base")); nombre = Db.str(x.get("nombre")); }
                BigDecimal cb = c.multiply(((Unidades.Un) un.getSelectedItem()).factor());
                Linea previa = lineas.get(idIns);
                if (previa != null) { cb = cb.add(previa.cantidadBase()); tl = tl.add(previa.total()); if (pv == null) pv = previa.precioVentaNuevo(); }
                lineas.put(idIns, new Linea(idIns, nombre, base, cb, tl, idProd, pv));
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
        cab.agregar("Proveedor:", prov).agregar("Almacén destino:", almacen).agregar("Fecha de la factura:", fecha).agregar("N.º de factura del proveedor:", doc)
           .agregar("Condición de pago:", condicion).agregar("Método de pago:", metodo).agregar("Referencia (cheque, transf.):", refPago)
           .agregar("Vence el:", vence);
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
            boolean cred = "CREDITO".equals(condicion.getSelectedItem());
            if (cred && Campos.valor(vence).isBefore(Campos.valor(fecha)))
                throw new IllegalArgumentException("El vencimiento no puede ser antes de la fecha de la factura.");
            Db.transaccion(tx -> {
                long idc = tx.insert("INSERT INTO compras (id_proveedor, fecha, numero_documento, id_usuario, condicion_pago, metodo_pago, referencia_pago, fecha_vencimiento, id_almacen) VALUES (?,?,?,?,?,?,?,?,?)",
                        AccionesHotel.idDe(prov), Campos.valor(fecha), doc.getText().isBlank() ? null : doc.getText().trim(), Sesion.idUsuario,
                        cred ? "CREDITO" : "CONTADO", cred ? null : metodo.getSelectedItem(), cred || refPago.getText().isBlank() ? null : refPago.getText().trim(),
                        cred ? Campos.valor(vence) : null, AccionesHotel.idDe(almacen));
                for (Linea l : lineas.values())
                    tx.exec("INSERT INTO compra_detalle (id_compra, id_insumo, cantidad, costo_unitario) VALUES (?,?,?,?)",
                            idc, l.idInsumo(), l.cantidadBase(), l.costoUnitario());
                for (Linea l : lineas.values()) if (l.precioVentaNuevo() != null && l.idProducto() != null) {
                    Object ant = tx.scalar("SELECT precio FROM productos_menu WHERE id_producto = ?", l.idProducto());
                    tx.exec("UPDATE productos_menu SET precio = ? WHERE id_producto = ?", l.precioVentaNuevo(), l.idProducto());
                    Bitacora.registrar("PRECIO", "producto", l.idProducto(), "de " + ant + " a " + l.precioVentaNuevo() + " (compra #" + idc + ")");
                }
                if (recibirYa.isSelected()) { Almacenes.usar(tx, AccionesHotel.idDe(almacen)); tx.call("{CALL sp_recibir_compra(?,?)}", idc, Sesion.idUsuario); }
                Bitacora.registrar("CREAR", "compra", idc, (recibirYa.isSelected() ? "recibida" : "borrador") + (cred ? ", a crédito" : ", contado"));
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
        Map<String, Object> c = Db.uno("SELECT condicion_pago, metodo_pago, referencia_pago, fecha_vencimiento, total FROM compras WHERE id_compra = ?", id);
        String pago = c == null ? "" : "CREDITO".equals(Db.str(c.get("condicion_pago")))
                ? "A crédito, vence el " + c.get("fecha_vencimiento")
                : "Contado (" + Db.str(c.get("metodo_pago")) + (Db.str(c.get("referencia_pago")).isEmpty() ? "" : ", ref. " + Db.str(c.get("referencia_pago"))) + ")";
        JPanel pn = new JPanel(new BorderLayout(4, 4));
        pn.add(new JLabel("Pago: " + pago), BorderLayout.NORTH);
        pn.add(sp, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, pn, "Compra #" + id, JOptionPane.PLAIN_MESSAGE);
    }

    private void recibir() {
        int id = Ui.idSeleccionado(tCompras);
        if (id < 0) { Ui.info(this, "Seleccione una compra."); return; }
        if (!Ui.confirmar(this, "¿Recibir la compra #" + id + " y sumarla al inventario?")) return;
        Ui.seguro(this, () -> {
            Db.sin(tx -> {
                Object alm = tx.scalar("SELECT id_almacen FROM compras WHERE id_compra = ?", id);
                Almacenes.usar(tx, alm == null ? null : Db.ent(alm));
                tx.call("{CALL sp_recibir_compra(?,?)}", id, Sesion.idUsuario);
                return null;
            });
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

    // ------------------------------------------------------------ cuentas por pagar
    private void repintarCxp() {
        mCxp.setRowCount(0);
        BigDecimal saldo = BigDecimal.ZERO, vencido = BigDecimal.ZERO;
        for (Map<String, Object> f : cxpFilas) {
            boolean pend = "PENDIENTE".equals(Db.str(f.get("estado")));
            if (soloPendientes.isSelected() && !pend) continue;
            long dv = Db.lng(f.get("dias_vencida"));
            BigDecimal sl = (BigDecimal) f.get("saldo");
            String est = !pend ? "Pagada" : dv > 0 ? "Vencida (" + dv + " d)" : dv == 0 ? "Vence hoy" : "Al día";
            if (pend) { saldo = saldo.add(sl); if (dv > 0) vencido = vencido.add(sl); }
            mCxp.addRow(new Object[]{f.get("id_cxp"), f.get("nombre"), f.get("numero_documento"), f.get("fecha_emision"), f.get("fecha_vencimiento"),
                    est, Ui.numero(f.get("monto")), Ui.numero(sl)});
        }
        lblCxp.setText("Por pagar: " + Ui.lempiras(saldo) + "   ·   Vencido: " + Ui.lempiras(vencido));
    }

    private void pagarCxp() {
        int id = Ui.idSeleccionado(tCxp);
        if (id < 0) { Ui.info(this, "Seleccione una cuenta por pagar."); return; }
        Map<String, Object> x = Db.uno("SELECT x.saldo, x.estado, p.nombre FROM cuentas_por_pagar x JOIN proveedores p ON p.id_proveedor = x.id_proveedor WHERE x.id_cxp = ?", id);
        if (x == null || !"PENDIENTE".equals(Db.str(x.get("estado")))) { Ui.info(this, "Esa cuenta ya está pagada."); return; }
        BigDecimal saldo = (BigDecimal) x.get("saldo");
        JTextField monto = new JTextField(saldo.toPlainString(), 10), ref = new JTextField(16);
        JComboBox<String> metodo = new JComboBox<>(new String[]{"EFECTIVO", "TRANSFERENCIA", "CHEQUE", "TARJETA"});
        JSpinner fecha = Campos.fecha(LocalDate.now());
        Campos.Formulario f = new Campos.Formulario();
        f.nota(Db.str(x.get("nombre")) + " · saldo " + Ui.lempiras(saldo)).agregar("Monto a pagar (L):", monto).agregar("Método:", metodo)
         .agregar("Referencia:", ref).agregar("Fecha del pago:", fecha).nota("Puede abonar menos del saldo; la cuenta queda pendiente por la diferencia.");
        Autorizacion.ejecutar(this, "compras.pagar", "pagar a proveedores", () -> {
            if (!Campos.dialogo(this, "Registrar pago a proveedor", f, () -> {
                BigDecimal m = Ui.leerDinero(monto.getText(), "monto");
                if (m.signum() <= 0) throw new IllegalArgumentException("El monto debe ser mayor que cero.");
                Db.transaccion(tx -> {
                    Map<String, Object> c = tx.uno("SELECT saldo, estado FROM cuentas_por_pagar WHERE id_cxp = ? FOR UPDATE", id);
                    BigDecimal sl = (BigDecimal) c.get("saldo");
                    if (!"PENDIENTE".equals(Db.str(c.get("estado")))) throw new IllegalArgumentException("Esa cuenta ya fue pagada.");
                    if (m.compareTo(sl) > 0) throw new IllegalArgumentException("El pago (" + Ui.lempiras(m) + ") es mayor que el saldo (" + Ui.lempiras(sl) + ").");
                    tx.exec("INSERT INTO pagos_proveedor (id_cxp, fecha, monto, metodo, referencia, id_usuario) VALUES (?,?,?,?,?,?)",
                            id, Campos.valor(fecha), m, metodo.getSelectedItem(), ref.getText().isBlank() ? null : ref.getText().trim(), Sesion.idUsuario);
                    BigDecimal nuevo = sl.subtract(m);
                    tx.exec("UPDATE cuentas_por_pagar SET saldo = ?, estado = ? WHERE id_cxp = ?", nuevo, nuevo.signum() == 0 ? "PAGADA" : "PENDIENTE", id);
                    Bitacora.registrar("PAGO", "cxp", id, Ui.lempiras(m) + " " + metodo.getSelectedItem());
                    return null;
                });
                return true;
            })) return;
            alMostrar();
        });
    }

    private void verPagos() {
        int id = Ui.idSeleccionado(tCxp);
        if (id < 0) { Ui.info(this, "Seleccione una cuenta por pagar."); return; }
        DefaultTableModel m = Ui.modelo("Fecha", "Monto", "Método", "Referencia", "Registró");
        Ui.llenar(m, Db.query("SELECT g.fecha, g.monto, g.metodo, g.referencia, u.username FROM pagos_proveedor g LEFT JOIN usuarios u ON u.id_usuario = g.id_usuario WHERE g.id_cxp = ? ORDER BY g.id_pago", id),
                "fecha", "monto", "metodo", "referencia", "username");
        JTable t = Ui.tabla(m);
        Ui.alinearDerecha(t, 1);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(520, 200));
        JOptionPane.showMessageDialog(this, sp, "Pagos de la cuenta #" + id, JOptionPane.PLAIN_MESSAGE);
    }
}
