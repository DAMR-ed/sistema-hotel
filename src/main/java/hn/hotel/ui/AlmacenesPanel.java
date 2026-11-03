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
import java.util.List;
import java.util.Map;

/** Almacenes, existencias por almacén, traspasos y puntos de venta (cada punto de venta descarga de un almacén). */
public class AlmacenesPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mAlm = Ui.modelo("Id", "Almacén", "Descripción", "Principal", "Activo", "Insumos con existencia");
    private final JTable tAlm = Ui.tabla(mAlm);
    private final DefaultTableModel mEx = Ui.modelo("Insumo", "Unidad", "Almacén", "Existencia");
    private final JTable tEx = Ui.tabla(mEx);
    private final JComboBox<AccionesHotel.Opcion> filtroAlm = new JComboBox<>();
    private final JTextField buscar = new JTextField(14);
    private boolean cargando = false;
    private final DefaultTableModel mTras = Ui.modelo("Fecha", "Insumo", "Cantidad", "Desde", "Hacia", "Registró", "Nota");
    private final DefaultTableModel mPv = Ui.modelo("Id", "Punto de venta", "Tipo", "Almacén", "Activo", "Esta computadora");
    private final JTable tPv = Ui.tabla(mPv);
    private final JLabel lblLocal = new JLabel(" ");

    public AlmacenesPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Almacenes y puntos de venta"), BorderLayout.NORTH);

        JPanel alm = new JPanel(new BorderLayout(6, 6));
        tAlm.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tAlm, 5);
        alm.add(new JLabel("<html><span style='color:#64748B'>La existencia de cada insumo se lleva por almacén. Las compras, ajustes y producciones se dirigen a un almacén; "
                + "las ventas descargan del almacén del punto de venta. Lo que ya existía quedó en el almacén principal.</span></html>"), BorderLayout.NORTH);
        alm.add(new JScrollPane(tAlm), BorderLayout.CENTER);
        alm.add(Ui.fila(Ui.botonPrimario("+ Nuevo almacén", () -> formAlmacen(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tAlm); if (id > 0) formAlmacen(id); else Ui.info(this, "Seleccione un almacén."); })), BorderLayout.SOUTH);

        JPanel ex = new JPanel(new BorderLayout(6, 6));
        filtroAlm.addActionListener(e -> { if (!cargando) cargarExistencias(); });
        buscar.addActionListener(e -> cargarExistencias());
        Ui.alinearDerecha(tEx, 3);
        ex.add(Ui.fila(new JLabel("Almacén:"), filtroAlm, new JLabel("Insumo:"), buscar, Ui.boton("Mostrar", this::cargarExistencias),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, mEx, "existencias_por_almacen_" + java.time.LocalDate.now()))), BorderLayout.NORTH);
        ex.add(new JScrollPane(tEx), BorderLayout.CENTER);

        JPanel tr = new JPanel(new BorderLayout(6, 6));
        JTable tT = Ui.tabla(mTras);
        Ui.alinearDerecha(tT, 2);
        tr.add(new JScrollPane(tT), BorderLayout.CENTER);
        tr.add(Ui.fila(Ui.botonPrimario("Nuevo traspaso...", this::traspaso),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, mTras, "traspasos_" + java.time.LocalDate.now()))), BorderLayout.SOUTH);

        JPanel pv = new JPanel(new BorderLayout(6, 6));
        tPv.getColumnModel().getColumn(0).setMaxWidth(45);
        pv.add(new JLabel("<html><span style='color:#64748B'>Cree los puntos de venta (restaurante, tienda, bar...) y elija de qué almacén descarga cada uno. "
                + "En cada computadora, seleccione su punto de venta y pulse «Usar en esta computadora».</span></html>"), BorderLayout.NORTH);
        pv.add(new JScrollPane(tPv), BorderLayout.CENTER);
        JPanel pie = Ui.fila(Ui.botonPrimario("+ Nuevo punto de venta", () -> formPunto(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tPv); if (id > 0) formPunto(id); else Ui.info(this, "Seleccione un punto de venta."); }),
                Ui.boton("Usar en esta computadora", this::usarLocal), Ui.boton("Quitar de esta computadora", this::quitarLocal), lblLocal);
        pv.add(pie, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Almacenes", alm);
        tabs.addTab("Existencias por almacén", ex);
        tabs.addTab("Traspasos", tr);
        tabs.addTab("Puntos de venta", pv);
        add(tabs, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> new Object[]{
                tx.query("SELECT a.id_almacen, a.nombre, a.descripcion, IF(a.es_principal=1,'Sí','') AS princ, IF(a.activo=1,'Sí','No') AS act, "
                        + "(SELECT COUNT(*) FROM existencias e WHERE e.id_almacen = a.id_almacen AND e.stock <> 0) AS n FROM almacenes a ORDER BY a.es_principal DESC, a.nombre"),
                tx.query("SELECT p.id_punto, p.nombre, p.tipo, a.nombre AS almacen, IF(p.activo=1,'Sí','No') AS act FROM puntos_venta p JOIN almacenes a ON a.id_almacen = p.id_almacen ORDER BY p.nombre"),
                tx.query("SELECT t.fecha, i.nombre AS insumo, i.unidad_base, -t.cantidad AS cant, a.nombre AS desde, t.referencia, COALESCE(u.username,'-') AS usuario, t.notas "
                        + "FROM movimientos_inventario t JOIN insumos i ON i.id_insumo = t.id_insumo JOIN almacenes a ON a.id_almacen = t.id_almacen LEFT JOIN usuarios u ON u.id_usuario = t.id_usuario "
                        + "WHERE t.tipo = 'TRASPASO_SALIDA' ORDER BY t.id_movimiento DESC LIMIT 300"),
                tx.query("SELECT id_almacen, nombre FROM almacenes WHERE activo = 1 ORDER BY es_principal DESC, nombre")}),
            r -> {
                @SuppressWarnings("unchecked") var a = (List<Map<String, Object>>) r[0];
                @SuppressWarnings("unchecked") var p = (List<Map<String, Object>>) r[1];
                @SuppressWarnings("unchecked") var t = (List<Map<String, Object>>) r[2];
                @SuppressWarnings("unchecked") var al = (List<Map<String, Object>>) r[3];
                Ui.llenar(mAlm, a, "id_almacen", "nombre", "descripcion", "princ", "act", "n");
                Integer local = Almacenes.puntoLocal();
                mPv.setRowCount(0);
                String nombreLocal = null;
                for (Map<String, Object> f : p) {
                    boolean es = local != null && local == Db.ent(f.get("id_punto"));
                    if (es) nombreLocal = Db.str(f.get("nombre"));
                    mPv.addRow(new Object[]{f.get("id_punto"), f.get("nombre"), f.get("tipo"), f.get("almacen"), f.get("act"), es ? "✔ esta PC" : ""});
                }
                lblLocal.setText(nombreLocal == null ? "   Esta computadora no tiene punto de venta (descarga del almacén principal)." : "   Esta computadora: " + nombreLocal);
                mTras.setRowCount(0);
                for (Map<String, Object> f : t)
                    mTras.addRow(new Object[]{f.get("fecha"), f.get("insumo"), Ui.numero(f.get("cant")) + " " + f.get("unidad_base"), f.get("desde"),
                            Db.str(f.get("referencia")).replaceFirst("^Traspaso a ", ""), f.get("usuario"), f.get("notas")});
                cargando = true;
                filtroAlm.removeAllItems();
                filtroAlm.addItem(new AccionesHotel.Opcion(0, "(todos)"));
                for (Map<String, Object> f : al) filtroAlm.addItem(new AccionesHotel.Opcion(Db.ent(f.get("id_almacen")), Db.str(f.get("nombre"))));
                cargando = false;
                cargarExistencias();
            });
    }

    private void cargarExistencias() {
        int a = filtroAlm.getItemCount() == 0 ? 0 : AccionesHotel.idDe(filtroAlm);
        String q = "%" + buscar.getText().trim() + "%";
        Ui.llenar(mEx, Db.query("SELECT i.nombre, i.unidad_base, al.nombre AS almacen, e.stock FROM existencias e JOIN insumos i ON i.id_insumo = e.id_insumo "
                + "JOIN almacenes al ON al.id_almacen = e.id_almacen WHERE (? = 0 OR e.id_almacen = ?) AND i.nombre LIKE ? AND i.activo = 1 ORDER BY i.nombre, al.nombre", a, a, q),
                "nombre", "unidad_base", "almacen", "stock");
    }

    // ------------------------------------------------------------ almacenes
    private void formAlmacen(Integer id) {
        Map<String, Object> a = id == null ? null : Db.uno("SELECT * FROM almacenes WHERE id_almacen = ?", id);
        JTextField nombre = new JTextField(a == null ? "" : Db.str(a.get("nombre")), 22), desc = new JTextField(a == null ? "" : Db.str(a.get("descripcion")), 26);
        JCheckBox principal = new JCheckBox("Es el almacén principal (recibe lo que no tenga otro destino)", a != null && Db.ent(a.get("es_principal")) == 1);
        JCheckBox activo = new JCheckBox("Activo", a == null || Db.ent(a.get("activo")) == 1);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("Descripción:", desc).agregar("", principal).agregar("", activo);
        Campos.dialogo(this, id == null ? "Nuevo almacén" : "Editar almacén", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            boolean eraPrincipal = a != null && Db.ent(a.get("es_principal")) == 1;
            if (eraPrincipal && !principal.isSelected()) throw new IllegalArgumentException("Debe haber un almacén principal. Marque otro como principal para cambiarlo.");
            if (principal.isSelected() && !activo.isSelected()) throw new IllegalArgumentException("El almacén principal debe estar activo.");
            Db.transaccion(tx -> {
                if (!activo.isSelected() && id != null && tx.scalar("SELECT 1 FROM existencias WHERE id_almacen = ? AND stock <> 0 LIMIT 1", id) != null)
                    throw new IllegalArgumentException("Ese almacén todavía tiene existencias. Traspáselas a otro almacén antes de desactivarlo.");
                long idn;
                if (id == null) idn = tx.insert("INSERT INTO almacenes (nombre, descripcion, es_principal, activo) VALUES (?,?,0,?)",
                        nombre.getText().trim(), desc.getText().isBlank() ? null : desc.getText().trim(), activo.isSelected() ? 1 : 0);
                else {
                    idn = id;
                    tx.exec("UPDATE almacenes SET nombre = ?, descripcion = ?, activo = ? WHERE id_almacen = ?",
                            nombre.getText().trim(), desc.getText().isBlank() ? null : desc.getText().trim(), activo.isSelected() ? 1 : 0, id);
                }
                if (principal.isSelected()) {
                    tx.exec("UPDATE almacenes SET es_principal = 0 WHERE id_almacen <> ?", idn);
                    tx.exec("UPDATE almacenes SET es_principal = 1 WHERE id_almacen = ?", idn);
                }
                Bitacora.registrar(id == null ? "CREAR" : "EDITAR", "almacen", idn, nombre.getText().trim());
                return null;
            });
            return true;
        });
        alMostrar();
    }

    // ------------------------------------------------------------ traspasos
    private void traspaso() {
        var alms = Db.query("SELECT id_almacen, nombre FROM almacenes WHERE activo = 1 ORDER BY es_principal DESC, nombre");
        var insumos = Db.query("SELECT id_insumo, nombre, unidad_base FROM insumos WHERE activo = 1 ORDER BY nombre");
        if (alms.size() < 2) { Ui.info(this, "Necesita al menos dos almacenes activos para hacer un traspaso."); return; }
        JComboBox<AccionesHotel.Opcion> ins = AccionesHotel.combo(insumos, "id_insumo", "nombre", false);
        JComboBox<AccionesHotel.Opcion> origen = AccionesHotel.combo(alms, "id_almacen", "nombre", false), destino = AccionesHotel.combo(alms, "id_almacen", "nombre", false);
        destino.setSelectedIndex(1);
        JComboBox<Unidades.Un> un = new JComboBox<>();
        JLabel disp = new JLabel(" ");
        JTextField cant = new JTextField("1", 8), nota = new JTextField(22);
        Runnable refrescar = () -> {
            int idIns = AccionesHotel.idDe(ins);
            un.removeAllItems();
            String base = "";
            for (Map<String, Object> x : insumos) if (Db.ent(x.get("id_insumo")) == idIns) { base = Db.str(x.get("unidad_base")); for (Unidades.Un u : Unidades.para(base)) un.addItem(u); }
            BigDecimal d = Db.dec(Db.scalar("SELECT COALESCE((SELECT stock FROM existencias WHERE id_almacen = ? AND id_insumo = ?), 0)", AccionesHotel.idDe(origen), idIns));
            disp.setText("Disponible en el almacén de origen: " + d.stripTrailingZeros().toPlainString() + " " + base);
        };
        ins.addActionListener(e -> refrescar.run());
        origen.addActionListener(e -> refrescar.run());
        refrescar.run();
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Insumo:", ins).agregar("Desde el almacén:", origen).agregar("", disp).agregar("Hacia el almacén:", destino)
         .agregar("Cantidad:", cant).agregar("Unidad:", un).agregar("Nota (opcional):", nota);
        Autorizacion.ejecutar(this, "pos.traspasos", "hacer traspasos entre almacenes", () -> {
            if (!Campos.dialogo(this, "Traspaso entre almacenes", f, () -> {
                BigDecimal c = Ui.leerDinero(cant.getText(), "cantidad").multiply(((Unidades.Un) un.getSelectedItem()).factor()).setScale(3, RoundingMode.HALF_UP);
                if (c.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
                int o = AccionesHotel.idDe(origen), d = AccionesHotel.idDe(destino), idIns = AccionesHotel.idDe(ins);
                Db.sin(tx -> { tx.call("{CALL sp_traspasar(?,?,?,?,?,?)}", idIns, o, d, c, Sesion.idUsuario, nota.getText().isBlank() ? null : nota.getText().trim()); return null; });
                Bitacora.registrar("TRASPASO", "insumo", idIns, c.toPlainString() + " de almacén " + o + " a " + d);
                return true;
            })) return;
            alMostrar();
        });
    }

    // ------------------------------------------------------------ puntos de venta
    private void formPunto(Integer id) {
        Map<String, Object> p = id == null ? null : Db.uno("SELECT * FROM puntos_venta WHERE id_punto = ?", id);
        JTextField nombre = new JTextField(p == null ? "" : Db.str(p.get("nombre")), 22);
        JComboBox<String> tipo = new JComboBox<>(new String[]{"RESTAURANTE", "TIENDA", "BAR", "OTRO"});
        if (p != null) tipo.setSelectedItem(Db.str(p.get("tipo")));
        JComboBox<AccionesHotel.Opcion> alm = AccionesHotel.combo(Db.query("SELECT id_almacen, nombre FROM almacenes WHERE activo = 1 ORDER BY es_principal DESC, nombre"), "id_almacen", "nombre", false);
        if (p != null) AccionesHotel.seleccionar(alm, Db.ent(p.get("id_almacen")));
        JCheckBox activo = new JCheckBox("Activo", p == null || Db.ent(p.get("activo")) == 1);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("Tipo:", tipo).agregar("Descarga del almacén:", alm).agregar("", activo);
        Campos.dialogo(this, id == null ? "Nuevo punto de venta" : "Editar punto de venta", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            if (id == null) Db.insert("INSERT INTO puntos_venta (nombre, tipo, id_almacen, activo) VALUES (?,?,?,?)",
                    nombre.getText().trim(), tipo.getSelectedItem(), AccionesHotel.idDe(alm), activo.isSelected() ? 1 : 0);
            else Db.exec("UPDATE puntos_venta SET nombre = ?, tipo = ?, id_almacen = ?, activo = ? WHERE id_punto = ?",
                    nombre.getText().trim(), tipo.getSelectedItem(), AccionesHotel.idDe(alm), activo.isSelected() ? 1 : 0, id);
            Bitacora.registrar(id == null ? "CREAR" : "EDITAR", "punto_venta", id == null ? 0 : id, nombre.getText().trim());
            return true;
        });
        alMostrar();
    }

    private void usarLocal() {
        int id = Ui.idSeleccionado(tPv);
        if (id < 0) { Ui.info(this, "Seleccione un punto de venta."); return; }
        Ui.seguro(this, () -> { Almacenes.guardarPuntoLocal(id); alMostrar(); });
    }

    private void quitarLocal() {
        Ui.seguro(this, () -> { Almacenes.guardarPuntoLocal(null); alMostrar(); });
    }
}
