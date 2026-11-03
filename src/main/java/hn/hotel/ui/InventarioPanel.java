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

/** Existencias de insumos del restaurante con kardex, ajustes y mermas. */
public class InventarioPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Insumo", "Categoría", "Unidad", "Existencia", "Mínimo", "Costo/unidad", "Valor", "Situación", "Proveedor");
    private final JComboBox<AccionesHotel.Opcion> filtroCat = new JComboBox<>();
    private boolean cargandoCats;
    private final DefaultTableModel mMov = Ui.modelo("Fecha", "Insumo", "Tipo", "Cantidad", "Costo unit.", "Referencia", "Notas");
    private final JSpinner movDesde = Campos.fecha(java.time.LocalDate.now().minusDays(7)), movHasta = Campos.fecha(java.time.LocalDate.now());
    private final JComboBox<String> movTipo = new JComboBox<>(new String[]{"Todos", "COMPRA", "SALIDA_VENTA", "DEVOLUCION", "AJUSTE", "MERMA", "INVENTARIO_INICIAL"});
    private final DefaultTableModel mVal = Ui.modelo("Categoría", "Insumos", "Bajos / agotados", "Valor");
    private final DefaultTableModel mCats = Ui.modelo("Id", "Categoría", "Insumos", "Activa");
    private final JTable tCats = Ui.tabla(mCats);
    private final JLabel lblValTotal = new JLabel(" ");
    private final JTable tabla = Ui.tabla(modelo);
    private final JTextField buscar = new JTextField(16);
    private final JCheckBox soloBajos = new JCheckBox("Solo bajos o agotados");
    private final JLabel lblTotal = new JLabel(" ");

    public InventarioPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(new JLabel("<html><span style='font-size:20px'><b>Inventario</b></span></html>"), BorderLayout.NORTH);

        // ---- pestaña Existencias
        JPanel ex = new JPanel(new BorderLayout(8, 8));
        ex.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        filtroCat.addActionListener(e -> { if (!cargandoCats) alMostrar(); });
        ex.add(Ui.fila(new JLabel("Categoría:"), filtroCat, new JLabel("Buscar:"), buscar, soloBajos, Ui.boton("Mostrar", this::alMostrar), lblTotal), BorderLayout.NORTH);
        ex.add(new JScrollPane(tabla), BorderLayout.CENTER);
        tabla.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tabla, 4, 5, 6, 7);
        tabla.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                if (!sel) {
                    String s = String.valueOf(t.getValueAt(r, 8));
                    comp.setForeground(s.equals("AGOTADO") ? new Color(0xB00020) : s.equals("BAJO") ? new Color(0xB36B00) : Color.BLACK);
                }
                return comp;
            }
        });
        buscar.addActionListener(e -> alMostrar());
        ex.add(Ui.fila(Ui.botonPrimario("+ Nuevo insumo", () -> formulario(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tabla); if (id > 0) formulario(id); else aviso(); }),
                Ui.boton("Ajustar existencia", () -> Autorizacion.ejecutar(this, "inventario.ajustar", "ajustar existencias", this::ajustar)),
                Ui.boton("Carga masiva (Excel)...", () -> Autorizacion.ejecutar(this, "inventario.carga_masiva", "cargar el inventario en masa", () -> CargaMasivaInventario.mostrar(this, this::alMostrar))),
                Ui.boton("Kardex del insumo", this::kardex),
                Ui.boton("Vender en el POS...", this::venderEnPos),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, modelo, "inventario_" + java.time.LocalDate.now()))), BorderLayout.SOUTH);

        // ---- pestaña Movimientos
        JPanel mv = new JPanel(new BorderLayout(8, 8));
        mv.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        mv.add(Ui.fila(new JLabel("Desde:"), movDesde, new JLabel("Hasta:"), movHasta, new JLabel("Tipo:"), movTipo,
                Ui.boton("Mostrar", this::cargarMovimientos),
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, mMov, "movimientos_inventario_" + java.time.LocalDate.now()))), BorderLayout.NORTH);
        JTable tMov = Ui.tabla(mMov);
        Ui.alinearDerecha(tMov, 3, 4);
        mv.add(new JScrollPane(tMov), BorderLayout.CENTER);

        // ---- pestaña Valorización
        JPanel va = new JPanel(new BorderLayout(8, 8));
        va.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        JTable tVal = Ui.tabla(mVal);
        Ui.alinearDerecha(tVal, 1, 2, 3);
        va.add(new JScrollPane(tVal), BorderLayout.CENTER);
        lblValTotal.setFont(lblValTotal.getFont().deriveFont(Font.BOLD, 15f));
        va.add(lblValTotal, BorderLayout.SOUTH);

        // ---- pestaña Categorías
        JPanel ca = new JPanel(new BorderLayout(8, 8));
        ca.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        tCats.getColumnModel().getColumn(0).setMaxWidth(45);
        ca.add(new JScrollPane(tCats), BorderLayout.CENTER);
        ca.add(Ui.fila(Ui.botonPrimario("+ Nueva categoría", () -> formCategoria(null)),
                Ui.boton("Renombrar / activar", () -> { int id = Ui.idSeleccionado(tCats); if (id > 0) formCategoria(id); else Ui.info(this, "Seleccione una categoría."); })), BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Existencias", ex);
        tabs.addTab("Movimientos", mv);
        tabs.addTab("Valorización", va);
        tabs.addTab("Categorías", ca);
        tabs.addChangeListener(e -> { if (tabs.getSelectedIndex() == 1) cargarMovimientos(); });
        add(tabs, BorderLayout.CENTER);
    }

    private void aviso() { Ui.info(this, "Seleccione un insumo de la tabla."); }

    @Override public void alMostrar() {
        String q = "%" + buscar.getText().trim() + "%";
        boolean bajos = soloBajos.isSelected();
        int cat = AccionesHotel.idDe(filtroCat);   // 0 = todas, -1 = sin categoría
        Ui.enSegundoPlano(this, () -> {
            String filtro = cat == 0 ? "" : cat == -1 ? "AND i.id_categoria_insumo IS NULL " : "AND i.id_categoria_insumo = " + cat + " ";
            return new Object[]{
                Db.query("SELECT v.id_insumo, v.nombre, COALESCE(c.nombre, 'Sin categoría') AS categoria, v.unidad_base, v.stock_actual, v.stock_minimo, v.costo_promedio, "
                    + "v.valor_inventario, v.situacion, v.proveedor FROM v_stock_insumos v JOIN insumos i ON i.id_insumo = v.id_insumo "
                    + "LEFT JOIN categorias_insumo c ON c.id_categoria_insumo = i.id_categoria_insumo WHERE v.nombre LIKE ? " + filtro
                    + (bajos ? "AND v.situacion <> 'OK' " : "") + "ORDER BY c.nombre, v.nombre", q),
                Db.query("SELECT id_categoria_insumo, nombre FROM categorias_insumo WHERE activa = 1 ORDER BY nombre"),
                Db.query("SELECT COALESCE(c.nombre, 'Sin categoría') AS categoria, COUNT(*) AS n, SUM(v.situacion <> 'OK') AS bajos, COALESCE(SUM(v.valor_inventario),0) AS valor "
                    + "FROM v_stock_insumos v JOIN insumos i ON i.id_insumo = v.id_insumo LEFT JOIN categorias_insumo c ON c.id_categoria_insumo = i.id_categoria_insumo "
                    + "GROUP BY c.nombre ORDER BY valor DESC"),
                Db.query("SELECT c.id_categoria_insumo, c.nombre, (SELECT COUNT(*) FROM insumos i WHERE i.id_categoria_insumo = c.id_categoria_insumo) AS n, IF(c.activa=1,'Sí','No') AS act "
                    + "FROM categorias_insumo c ORDER BY c.nombre")};
        }, r -> {
            @SuppressWarnings("unchecked") List<Map<String, Object>> filas = (List<Map<String, Object>>) r[0], cats = (List<Map<String, Object>>) r[1],
                    val = (List<Map<String, Object>>) r[2], lista = (List<Map<String, Object>>) r[3];
            Ui.llenar(modelo, filas, "id_insumo", "nombre", "categoria", "unidad_base", "stock_actual", "stock_minimo", "costo_promedio", "valor_inventario", "situacion", "proveedor");
            BigDecimal total = BigDecimal.ZERO;
            for (Map<String, Object> f : filas) total = total.add(Db.dec(f.get("valor_inventario")));
            lblTotal.setText("   Valor mostrado: " + Ui.lempiras(total));
            cargandoCats = true;
            int previa = AccionesHotel.idDe(filtroCat);
            filtroCat.removeAllItems();
            filtroCat.addItem(new AccionesHotel.Opcion(0, "Todas"));
            filtroCat.addItem(new AccionesHotel.Opcion(-1, "Sin categoría"));
            for (Map<String, Object> c : cats) filtroCat.addItem(new AccionesHotel.Opcion(Db.ent(c.get("id_categoria_insumo")), Db.str(c.get("nombre"))));
            AccionesHotel.seleccionar(filtroCat, previa);
            cargandoCats = false;
            Ui.llenar(mVal, val, "categoria", "n", "bajos", "valor");
            BigDecimal tv = BigDecimal.ZERO;
            for (Map<String, Object> f : val) tv = tv.add(Db.dec(f.get("valor")));
            lblValTotal.setText("Valor total del inventario: " + Ui.lempiras(tv));
            Ui.llenar(mCats, lista, "id_categoria_insumo", "nombre", "n", "act");
        });
    }

    private void cargarMovimientos() {
        java.time.LocalDate d = Campos.valor(movDesde), h = Campos.valor(movHasta);
        String tipo = (String) movTipo.getSelectedItem();
        Ui.enSegundoPlano(this, () -> Db.query("SELECT m.fecha, i.nombre AS insumo, m.tipo, m.cantidad, m.costo_unitario, m.referencia, m.notas FROM movimientos_inventario m "
                + "JOIN insumos i ON i.id_insumo = m.id_insumo WHERE DATE(m.fecha) BETWEEN ? AND ? AND (? = 'Todos' OR m.tipo = ?) ORDER BY m.id_movimiento DESC LIMIT 1000", d, h, tipo, tipo),
            filas -> Ui.llenar(mMov, filas, "fecha", "insumo", "tipo", "cantidad", "costo_unitario", "referencia", "notas"));
    }

    private void formCategoria(Integer id) {
        Map<String, Object> c = id == null ? null : Db.uno("SELECT * FROM categorias_insumo WHERE id_categoria_insumo = ?", id);
        JTextField nombre = new JTextField(c == null ? "" : Db.str(c.get("nombre")), 20);
        JCheckBox activa = new JCheckBox("Activa", c == null || Db.ent(c.get("activa")) == 1);
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre:", nombre).agregar("", activa)
                .nota("Ejemplos: Carnes, Lácteos, Verduras, Licores, Desechables. Sirven para filtrar y valorar el inventario.");
        Campos.dialogo(this, id == null ? "Nueva categoría de inventario" : "Editar categoría", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            if (id == null) Db.insert("INSERT INTO categorias_insumo (nombre, activa) VALUES (?,?)", nombre.getText().trim(), activa.isSelected() ? 1 : 0);
            else Db.exec("UPDATE categorias_insumo SET nombre=?, activa=? WHERE id_categoria_insumo=?", nombre.getText().trim(), activa.isSelected() ? 1 : 0, id);
            return true;
        });
        alMostrar();
    }

    private void formulario(Integer id) {
        Map<String, Object> r = id == null ? null : Db.uno("SELECT * FROM insumos WHERE id_insumo = ?", id);
        JTextField nombre = new JTextField(r == null ? "" : Db.str(r.get("nombre")), 22);
        JTextField codigo = new JTextField(r == null ? "" : Db.str(r.get("codigo")), 14);
        JComboBox<String> unidad = new JComboBox<>(new String[]{"G", "ML", "UND"});
        JTextField minimo = new JTextField(r == null ? "0" : Db.dec(r.get("stock_minimo")).toPlainString(), 10);
        JComboBox<AccionesHotel.Opcion> prov = AccionesHotel.combo(Db.query("SELECT id_proveedor, nombre FROM proveedores WHERE activo = 1 ORDER BY nombre"), "id_proveedor", "nombre", true);
        JCheckBox activo = new JCheckBox("Activo", r == null || Db.ent(r.get("activo")) == 1);
        JComboBox<AccionesHotel.Opcion> cat = AccionesHotel.combo(Db.query("SELECT id_categoria_insumo, nombre FROM categorias_insumo WHERE activa = 1 ORDER BY nombre"), "id_categoria_insumo", "nombre", true);
        if (r != null && r.get("id_categoria_insumo") != null) AccionesHotel.seleccionar(cat, Db.ent(r.get("id_categoria_insumo")));
        if (r == null && AccionesHotel.idDe(filtroCat) > 0) AccionesHotel.seleccionar(cat, AccionesHotel.idDe(filtroCat));
        if (r != null) {
            unidad.setSelectedItem(Db.str(r.get("unidad_base")));
            unidad.setEnabled(false);   // cambiar la unidad dañaría recetas e historial
            if (r.get("id_proveedor") != null) AccionesHotel.seleccionar(prov, Db.ent(r.get("id_proveedor")));
        }
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Código:", codigo).agregar("Nombre:", nombre).agregar("Categoría:", cat).agregar("Unidad base:", unidad).agregar("Existencia mínima (unidad base):", minimo)
         .agregar("Proveedor habitual:", prov).agregar("", activo)
         .nota("G = gramos, ML = mililitros, UND = unidades. Las compras y recetas se convierten a esta unidad.");
        Campos.dialogo(this, id == null ? "Nuevo insumo" : "Editar insumo", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            BigDecimal mn = Ui.leerDinero(minimo.getText(), "mínimo");
            if (mn.signum() < 0) throw new IllegalArgumentException("El mínimo no puede ser negativo.");
            int p = AccionesHotel.idDe(prov);
            int ct = AccionesHotel.idDe(cat);
            Object idCat = ct > 0 ? ct : null;
            if (id == null) {
                long nid = Db.insert("INSERT INTO insumos (nombre, unidad_base, stock_minimo, id_proveedor, id_categoria_insumo, activo, codigo) VALUES (?,?,?,?,?,?,?)",
                        nombre.getText().trim(), unidad.getSelectedItem(), mn, p == 0 ? null : p, idCat, activo.isSelected() ? 1 : 0, codigo.getText().isBlank() ? null : codigo.getText().trim());
                Bitacora.registrar("CREAR", "insumo", nid, nombre.getText().trim());
            } else {
                Db.exec("UPDATE insumos SET nombre=?, stock_minimo=?, id_proveedor=?, id_categoria_insumo=?, activo=?, codigo=? WHERE id_insumo=?",
                        nombre.getText().trim(), mn, p == 0 ? null : p, idCat, activo.isSelected() ? 1 : 0, codigo.getText().isBlank() ? null : codigo.getText().trim(), id);
            }
            return true;
        });
        alMostrar();
    }

    /** Publica el insumo (en unidades) como producto del POS, para que se venda tal cual y descuente su propio stock. */
    private void venderEnPos() {
        int id = Ui.idSeleccionado(tabla);
        if (id <= 0) { aviso(); return; }
        var cats = Db.query("SELECT id_categoria, nombre FROM categorias_menu WHERE activa = 1 ORDER BY orden, nombre");
        if (cats.isEmpty()) { Ui.info(this, "Primero cree una categoría en «Menú y recetas»."); return; }
        JComboBox<AccionesHotel.Opcion> cat = AccionesHotel.combo(cats, "id_categoria", "nombre", false);
        JTextField precio = new JTextField(10), barras = new JTextField(16);
        JComboBox<AccionesHotel.Opcion> trat = new JComboBox<>();
        java.util.Map<Integer, java.math.BigDecimal> tasaDe = new java.util.HashMap<>();
        for (var t : Db.query("SELECT id_tasa, nombre, tasa FROM tasas_isv WHERE activa = 1 ORDER BY tasa")) {
            trat.addItem(new AccionesHotel.Opcion(Db.ent(t.get("id_tasa")), Db.str(t.get("nombre"))));
            tasaDe.put(Db.ent(t.get("id_tasa")), Db.dec(t.get("tasa")));
        }
        for (int k = 0; k < trat.getItemCount(); k++) if (tasaDe.get(trat.getItemAt(k).id()).compareTo(java.math.BigDecimal.valueOf(15)) == 0) trat.setSelectedIndex(k);
        Campos.Formulario f = new Campos.Formulario().agregar("Categoría del menú:", cat).agregar("Precio de venta (L, SIN ISV):", precio)
                .agregar("Impuesto (ISV):", trat).agregar("Código de barras (opcional):", barras)
                .nota("Solo para insumos en UNIDADES (botella, lata, paquete). Cada venta descuenta 1 unidad de este inventario.");
        Campos.dialogo(this, "Vender en el POS", f, () -> {
            if (trat.getSelectedItem() == null) throw new IllegalArgumentException("No hay tasas de ISV configuradas.");
            hn.hotel.service.ReventaService.venderEnPos(id, AccionesHotel.idDe(cat), Ui.leerDinero(precio.getText(), "precio"),
                    tasaDe.get(AccionesHotel.idDe(trat)), barras.getText());
            Ui.info(this, "Listo: el producto ya aparece en el POS.");
            return true;
        });
    }

    private void ajustar() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { aviso(); return; }
        Map<String, Object> ins = Db.uno("SELECT nombre, unidad_base, stock_actual FROM insumos WHERE id_insumo = ?", id);
        String base = Db.str(ins.get("unidad_base"));
        BigDecimal actual = Db.dec(ins.get("stock_actual"));
        JComboBox<String> tipo = new JComboBox<>(new String[]{"Conteo físico (existencia real)", "Merma / desperdicio / daño (resta)", "Entrada manual (suma)"});
        JTextField cant = new JTextField("0", 10);
        JComboBox<Unidades.Un> un = new JComboBox<>(Unidades.para(base).toArray(new Unidades.Un[0]));
        JTextField nota = new JTextField(24);
        JComboBox<AccionesHotel.Opcion> alm = AccionesHotel.combo(Db.query("SELECT id_almacen, nombre FROM almacenes WHERE activo = 1 ORDER BY es_principal DESC, nombre"), "id_almacen", "nombre", false);
        Campos.Formulario f = new Campos.Formulario();
        f.nota("Existencia total de " + ins.get("nombre") + ": " + actual.stripTrailingZeros().toPlainString() + " " + base + " (todos los almacenes)")
         .agregar("Almacén:", alm).nota("El conteo físico se compara contra la existencia de ese almacén.")
         .agregar("Tipo de ajuste:", tipo).agregar("Cantidad:", cant).agregar("Unidad:", un).agregar("Motivo / nota:", nota);
        Campos.dialogo(this, "Ajustar existencia", f, () -> {
            BigDecimal c = Ui.leerDinero(cant.getText(), "cantidad").multiply(((Unidades.Un) un.getSelectedItem()).factor());
            if (c.signum() < 0) throw new IllegalArgumentException("Escriba la cantidad en positivo.");
            if (nota.getText().trim().length() < 3) throw new IllegalArgumentException("Escriba el motivo del ajuste.");
            int t = tipo.getSelectedIndex();
            int idAlm = AccionesHotel.idDe(alm);
            BigDecimal enAlmacen = Db.dec(Db.scalar("SELECT COALESCE((SELECT stock FROM existencias WHERE id_almacen = ? AND id_insumo = ?), 0)", idAlm, id));
            BigDecimal diferencia = t == 0 ? c.subtract(enAlmacen) : t == 1 ? c.negate() : c;
            if (diferencia.signum() == 0) throw new IllegalArgumentException("No hay diferencia que registrar.");
            Db.sin(tx -> { Almacenes.usar(tx, idAlm); tx.call("{CALL sp_ajustar_inventario(?,?,?,?,?)}", id, diferencia.setScale(3, RoundingMode.HALF_UP),
                    t == 1 ? "MERMA" : "AJUSTE", nota.getText().trim(), Sesion.idUsuario); return null; });
            Bitacora.registrar("AJUSTE_INV", "insumo", id, diferencia.toPlainString() + " " + base + " " + nota.getText().trim());
            return true;
        });
        alMostrar();
    }

    private void kardex() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { aviso(); return; }
        Map<String, Object> ins = Db.uno("SELECT nombre, unidad_base FROM insumos WHERE id_insumo = ?", id);
        JComboBox<AccionesHotel.Opcion> alm = new JComboBox<>();
        alm.addItem(new AccionesHotel.Opcion(0, "(todos los almacenes)"));
        for (Map<String, Object> a : Db.query("SELECT id_almacen, nombre FROM almacenes ORDER BY es_principal DESC, nombre"))
            alm.addItem(new AccionesHotel.Opcion(Db.ent(a.get("id_almacen")), Db.str(a.get("nombre"))));
        DefaultTableModel m = Ui.modelo("Fecha", "Almacén", "Tipo", "Cantidad", "Costo unit.", "Saldo", "Referencia", "Notas");
        Runnable cargar = () -> {
            int a = AccionesHotel.idDe(alm);
            // El saldo se calcula hacia atrás desde la existencia actual (del almacén elegido o total).
            BigDecimal saldo = a == 0 ? Db.dec(Db.scalar("SELECT stock_actual FROM insumos WHERE id_insumo = ?", id))
                    : Db.dec(Db.scalar("SELECT COALESCE((SELECT stock FROM existencias WHERE id_almacen = ? AND id_insumo = ?), 0)", a, id));
            List<Map<String, Object>> filas = Db.query("SELECT m.fecha, al.nombre AS almacen, m.tipo, m.cantidad, m.costo_unitario, m.referencia, m.notas FROM movimientos_inventario m "
                    + "LEFT JOIN almacenes al ON al.id_almacen = m.id_almacen WHERE m.id_insumo = ? AND (? = 0 OR m.id_almacen = ?) ORDER BY m.id_movimiento DESC LIMIT 500", id, a, a);
            m.setRowCount(0);
            for (Map<String, Object> f : filas) {
                m.addRow(new Object[]{f.get("fecha"), f.get("almacen"), f.get("tipo"), Ui.numero(f.get("cantidad")), Ui.numero(f.get("costo_unitario")), Ui.numero(saldo), f.get("referencia"), f.get("notas")});
                saldo = saldo.subtract(Db.dec(f.get("cantidad")));
            }
        };
        alm.addActionListener(e -> cargar.run());
        cargar.run();
        JTable t = Ui.tabla(m);
        Ui.alinearDerecha(t, 3, 4, 5);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(900, 380));
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(Ui.fila(new JLabel(ins.get("nombre") + " (" + ins.get("unidad_base") + ")   Almacén:"), alm,
                Ui.boton("Exportar (CSV)", () -> Exportar.csv(this, m, "kardex_" + ins.get("nombre")))), BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        JOptionPane.showMessageDialog(this, p, "Kardex (últimos 500 movimientos, con saldo)", JOptionPane.PLAIN_MESSAGE);
    }
}
