package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** Menú del restaurante (categorías y platos) y la receta de cada plato. */
public class MenuPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mProd = Ui.modelo("Id", "Categoría", "Plato", "Precio", "Costo", "Margen", "Área", "Activo");
    private final JTable tProd = Ui.tabla(mProd);
    private final DefaultTableModel mReceta = Ui.modelo("Id insumo", "Ingrediente", "Cantidad", "Unidad", "Merma %", "Costo");
    private final JTable tReceta = Ui.tabla(mReceta);
    private final JLabel lblReceta = new JLabel("Seleccione un plato para ver su receta");

    public MenuPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Menú y recetas"), BorderLayout.NORTH);

        JPanel izq = new JPanel(new BorderLayout(4, 4));
        izq.setBorder(BorderFactory.createTitledBorder("Platos y bebidas"));
        tProd.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(tProd, 3, 4, 5);
        izq.add(new JScrollPane(tProd), BorderLayout.CENTER);
        izq.add(Ui.fila(Ui.boton("Nuevo plato", () -> formularioProducto(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tProd); if (id > 0) formularioProducto(id); else Ui.info(this, "Seleccione un plato."); }),
                Ui.boton("Categorías...", this::categorias),
                Ui.boton("Opciones del plato...", () -> {
                    int id = Ui.idSeleccionado(tProd);
                    if (id < 0) { Ui.info(this, "Seleccione un plato."); return; }
                    OpcionesDialog.asignar(this, id, String.valueOf(tProd.getValueAt(tProd.getSelectedRow(), 2)));
                }),
                Ui.boton("Administrar opciones...", () -> OpcionesDialog.administrar(this)),
                Ui.boton("Listas de precios...", () -> ListasPreciosDialog.mostrar(this))), BorderLayout.SOUTH);

        JPanel der = new JPanel(new BorderLayout(4, 4));
        der.setBorder(BorderFactory.createTitledBorder("Receta (ingredientes por cada unidad vendida)"));
        der.add(lblReceta, BorderLayout.NORTH);
        tReceta.getColumnModel().getColumn(0).setMaxWidth(0);
        tReceta.getColumnModel().getColumn(0).setMinWidth(0);
        tReceta.getColumnModel().getColumn(0).setPreferredWidth(0);
        Ui.alinearDerecha(tReceta, 2, 4, 5);
        der.add(new JScrollPane(tReceta), BorderLayout.CENTER);
        der.add(Ui.fila(Ui.boton("Agregar / cambiar ingrediente", this::ingrediente),
                Ui.boton("Quitar ingrediente", this::quitarIngrediente)), BorderLayout.SOUTH);

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, izq, der);
        sp.setResizeWeight(0.55);
        add(sp, BorderLayout.CENTER);
        tProd.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) cargarReceta(); });
    }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.query(
                "SELECT p.id_producto, c.nombre AS categoria, p.nombre, p.precio, "
              + "COALESCE((SELECT SUM(rd.cantidad * (1 + rd.merma_pct/100) * i.costo_promedio) FROM receta_detalle rd JOIN insumos i ON i.id_insumo = rd.id_insumo WHERE rd.id_producto = p.id_producto), 0) AS costo, "
              + "COALESCE(e.nombre, ce.nombre, 'Se sirve directo') AS area_preparacion, IF(p.activo=1,'Sí','No') AS activo FROM productos_menu p JOIN categorias_menu c ON c.id_categoria = p.id_categoria "
              + "LEFT JOIN estaciones e ON e.id_estacion = p.id_estacion LEFT JOIN estaciones ce ON ce.id_estacion = c.id_estacion "
              + "ORDER BY c.orden, p.nombre"),
            filas -> {
                mProd.setRowCount(0);
                for (Map<String, Object> f : filas) {
                    BigDecimal precio = Db.dec(f.get("precio")), costo = Db.dec(f.get("costo"));
                    String margen = precio.signum() > 0 && costo.signum() > 0
                            ? precio.subtract(costo).multiply(BigDecimal.valueOf(100)).divide(precio, 0, RoundingMode.HALF_UP) + " %" : "-";
                    mProd.addRow(new Object[]{f.get("id_producto"), f.get("categoria"), f.get("nombre"), Ui.numero(precio),
                            costo.signum() > 0 ? Ui.numero(costo) : "sin receta", margen, f.get("area_preparacion"), f.get("activo")});
                }
            });
    }

    private void cargarReceta() {
        int id = Ui.idSeleccionado(tProd);
        mReceta.setRowCount(0);
        if (id < 0) { lblReceta.setText("Seleccione un plato para ver su receta"); return; }
        Object nombre = Db.scalar("SELECT nombre FROM productos_menu WHERE id_producto = ?", id);
        lblReceta.setText("Receta de: " + nombre);
        Ui.llenar(mReceta, Db.query("SELECT i.id_insumo, i.nombre, rd.cantidad, i.unidad_base, rd.merma_pct, "
                + "ROUND(rd.cantidad * (1 + rd.merma_pct/100) * i.costo_promedio, 2) AS costo "
                + "FROM receta_detalle rd JOIN insumos i ON i.id_insumo = rd.id_insumo WHERE rd.id_producto = ? ORDER BY i.nombre", id),
                "id_insumo", "nombre", "cantidad", "unidad_base", "merma_pct", "costo");
    }

    private void ingrediente() {
        int prod = Ui.idSeleccionado(tProd);
        if (prod < 0) { Ui.info(this, "Seleccione primero un plato."); return; }
        var insumos = Db.query("SELECT id_insumo, CONCAT(nombre, ' (', unidad_base, ')') AS txt FROM insumos WHERE activo = 1 ORDER BY nombre");
        if (insumos.isEmpty()) { Ui.info(this, "Primero registre insumos en el módulo Inventario."); return; }
        JComboBox<AccionesHotel.Opcion> ins = AccionesHotel.combo(insumos, "id_insumo", "txt", false);
        JTextField cant = new JTextField("1", 10), merma = new JTextField("0", 6);
        Campos.Formulario f = new Campos.Formulario().agregar("Ingrediente:", ins).agregar("Cantidad (en la unidad entre paréntesis):", cant)
                .agregar("Merma esperada (%):", merma)
                .nota("Ejemplo: pollo a la plancha usa 250 G de pechuga con 8 % de merma. G = gramos, ML = mililitros, UND = unidades.");
        Campos.dialogo(this, "Ingrediente de la receta", f, () -> {
            BigDecimal c = Ui.leerDinero(cant.getText(), "cantidad"), m = Ui.leerDinero(merma.getText(), "merma");
            if (c.signum() <= 0) throw new IllegalArgumentException("La cantidad debe ser mayor que cero.");
            if (m.signum() < 0 || m.compareTo(BigDecimal.valueOf(99)) > 0) throw new IllegalArgumentException("La merma debe estar entre 0 y 99.");
            Db.exec("INSERT INTO receta_detalle (id_producto, id_insumo, cantidad, merma_pct) VALUES (?,?,?,?) "
                  + "ON DUPLICATE KEY UPDATE cantidad = VALUES(cantidad), merma_pct = VALUES(merma_pct)", prod, AccionesHotel.idDe(ins), c, m);
            Bitacora.registrar("RECETA", "producto", prod, "insumo " + AccionesHotel.idDe(ins));
            return true;
        });
        cargarReceta();
        alMostrar();
    }

    private void quitarIngrediente() {
        int prod = Ui.idSeleccionado(tProd);
        int fila = tReceta.getSelectedRow();
        if (prod < 0 || fila < 0) { Ui.info(this, "Seleccione el ingrediente a quitar."); return; }
        int insumo = Integer.parseInt(tReceta.getValueAt(fila, 0).toString());
        if (!Ui.confirmar(this, "¿Quitar este ingrediente de la receta?")) return;
        Db.exec("DELETE FROM receta_detalle WHERE id_producto = ? AND id_insumo = ?", prod, insumo);
        cargarReceta();
        alMostrar();
    }

    private void formularioProducto(Integer id) {
        var cats = Db.query("SELECT id_categoria, nombre FROM categorias_menu WHERE activa = 1 ORDER BY orden, nombre");
        if (cats.isEmpty()) { Ui.info(this, "Primero cree una categoría."); categorias(); return; }
        Map<String, Object> p = id == null ? null : Db.uno("SELECT * FROM productos_menu WHERE id_producto = ?", id);
        JComboBox<AccionesHotel.Opcion> cat = AccionesHotel.combo(cats, "id_categoria", "nombre", false);
        JTextField nombre = new JTextField(p == null ? "" : Db.str(p.get("nombre")), 22);
        JTextField precio = new JTextField(p == null ? "" : Db.dec(p.get("precio")).toPlainString(), 10);
        JTextField desc = new JTextField(p == null ? "" : Db.str(p.get("descripcion")), 24);
        boolean tieneReceta = id != null && Db.scalar("SELECT 1 FROM receta_detalle WHERE id_producto = ? LIMIT 1", id) != null;
        JCheckBox reventa = new JCheckBox("Producto de reventa: controlar su inventario (bebidas, snacks, tienda)");
        reventa.setEnabled(!tieneReceta);
        if (tieneReceta) reventa.setText("Ya tiene receta / inventario (edítela abajo en «Receta»)");
        JTextField costoCompra = new JTextField("0.00", 10);
        JTextField barras = new JTextField(p == null || p.get("codigo_barras") == null ? "" : Db.str(p.get("codigo_barras")), 18);
        JComboBox<AccionesHotel.Opcion> estacion = new JComboBox<>();
        estacion.addItem(new AccionesHotel.Opcion(0, "La de su categoría"));
        for (Map<String, Object> e : Db.query("SELECT id_estacion, nombre FROM estaciones WHERE activa = 1 ORDER BY orden, nombre"))
            estacion.addItem(new AccionesHotel.Opcion(Db.ent(e.get("id_estacion")), Db.str(e.get("nombre"))));
        estacion.addItem(new AccionesHotel.Opcion(-1, "Ninguna (se sirve directo, sin comanda)"));
        reventa.addActionListener(ev -> { if (reventa.isSelected()) estacion.setSelectedIndex(estacion.getItemCount() - 1); });
        byte[][] foto = {p == null ? null : Fotos.bytes(p.get("foto"))};
        JLabel vista = new JLabel();
        Runnable pintarFoto = () -> {
            java.awt.image.BufferedImage im = Fotos.imagen(foto[0]);
            vista.setIcon(im == null ? null : new ImageIcon(im.getScaledInstance(96, 96, Image.SCALE_SMOOTH)));
            vista.setText(im == null ? "Sin foto" : "");
        };
        pintarFoto.run();
        JPanel panelFoto = Ui.fila(vista, Ui.boton("Elegir foto...", () -> Ui.seguro(this, () -> {
            try {
                byte[] nueva = Fotos.elegir(this);
                if (nueva != null) { foto[0] = nueva; pintarFoto.run(); }
            } catch (java.io.IOException ex) { throw new IllegalArgumentException(ex.getMessage()); }
        })), Ui.boton("Quitar foto", () -> { foto[0] = null; pintarFoto.run(); }));
        JComboBox<AccionesHotel.Opcion> trat = new JComboBox<>();
        java.util.Map<Integer, BigDecimal> tasaDe = new java.util.HashMap<>();
        for (Map<String, Object> t : Db.query("SELECT id_tasa, nombre, tasa FROM tasas_isv WHERE activa = 1 ORDER BY tasa")) {
            trat.addItem(new AccionesHotel.Opcion(Db.ent(t.get("id_tasa")), Db.str(t.get("nombre"))));
            tasaDe.put(Db.ent(t.get("id_tasa")), Db.dec(t.get("tasa")));
        }
        JCheckBox activo = new JCheckBox("Disponible en el menú", p == null || Db.ent(p.get("activo")) == 1);
        if (p != null) {
            AccionesHotel.seleccionar(cat, Db.ent(p.get("id_categoria")));
            AccionesHotel.seleccionar(estacion, "NINGUNA".equals(Db.str(p.get("area_preparacion"))) ? -1 : p.get("id_estacion") == null ? 0 : Db.ent(p.get("id_estacion")));
            for (Map.Entry<Integer, BigDecimal> e : tasaDe.entrySet())
                if (e.getValue().compareTo(Db.dec(p.get("tasa_isv"))) == 0) AccionesHotel.seleccionar(trat, e.getKey());
        }
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Categoría:", cat).agregar("Nombre:", nombre).agregar("Precio de venta (L, SIN ISV):", precio)
         .agregar("Descripción:", desc).agregar("Código de barras (opcional):", barras).agregar("Foto:", panelFoto).agregar("Envía la comanda a:", estacion).agregar("Impuesto (ISV):", trat).agregar("", activo).agregar("", reventa).agregar("Costo de compra (L, sin ISV):", costoCompra)
         .nota("El ISV se suma al facturar según la tasa elegida (las tasas se configuran en Control > Impuestos). Si sus precios al público ya lo incluyen, divida entre 1.15 al registrarlos.");
        Campos.dialogo(this, id == null ? "Nuevo plato" : "Editar plato", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            BigDecimal pr = Ui.leerDinero(precio.getText(), "precio");
            if (pr.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
            String d = desc.getText().isBlank() ? null : desc.getText().trim();
            if (trat.getSelectedItem() == null) throw new IllegalArgumentException("No hay tasas de ISV. Ejecute el script 10 o créelas en Impuestos.");
            BigDecimal tasa = tasaDe.get(AccionesHotel.idDe(trat));
            String fiscal = tasa.signum() == 0 ? "EXENTO" : "GRAVADO";
            int est = AccionesHotel.idDe(estacion);
            Object idEst = est > 0 ? est : null;
            String area = est == -1 ? "NINGUNA" : est > 0 && estacion.getSelectedItem().toString().equalsIgnoreCase("BARRA") ? "BARRA" : "COCINA";
            if (id == null) {
                long nid = Db.insert("INSERT INTO productos_menu (id_categoria, nombre, descripcion, precio, tratamiento_fiscal, tasa_isv, area_preparacion, id_estacion, foto, activo) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        AccionesHotel.idDe(cat), nombre.getText().trim(), d, pr, fiscal, tasa, area, idEst, foto[0], activo.isSelected() ? 1 : 0);
                guardarBarras(nid, barras.getText());
                if (reventa.isSelected()) hn.hotel.service.ReventaService.controlarInventario(nid, nombre.getText().trim(), Ui.leerDinero(costoCompra.getText(), "costo"));
                Bitacora.registrar("CREAR", "producto", nid, nombre.getText().trim());
            } else {
                Db.exec("UPDATE productos_menu SET id_categoria=?, nombre=?, descripcion=?, precio=?, tratamiento_fiscal=?, tasa_isv=?, area_preparacion=?, id_estacion=?, foto=?, activo=? WHERE id_producto=?",
                        AccionesHotel.idDe(cat), nombre.getText().trim(), d, pr, fiscal, tasa, area, idEst, foto[0], activo.isSelected() ? 1 : 0, id);
                guardarBarras(id, barras.getText());
                if (reventa.isSelected() && reventa.isEnabled()) hn.hotel.service.ReventaService.controlarInventario(id, nombre.getText().trim(), Ui.leerDinero(costoCompra.getText(), "costo"));
                Bitacora.registrar("EDITAR", "producto", id, nombre.getText().trim() + " L" + pr);
            }
            return true;
        });
        alMostrar();
    }

    private void categorias() {
        DefaultTableModel m = Ui.modelo("Id", "Categoría", "Orden", "Envía comandas a", "Línea de factura", "Activa");
        JTable t = Ui.tabla(m);
        t.getColumnModel().getColumn(0).setMaxWidth(45);
        Runnable cargar = () -> Ui.llenar(m, Db.query("SELECT c.id_categoria, c.nombre, c.orden, COALESCE(e.nombre, 'Se sirve directo') AS est, c.tipo, IF(c.activa=1,'Sí','No') AS act "
                + "FROM categorias_menu c LEFT JOIN estaciones e ON e.id_estacion = c.id_estacion ORDER BY c.orden, c.nombre"),
                "id_categoria", "nombre", "orden", "est", "tipo", "act");
        cargar.run();
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(500, 240));
        p.add(sp, BorderLayout.CENTER);
        p.add(Ui.fila(Ui.boton("Nueva", () -> { if (formCategoria(null)) cargar.run(); }),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(t); if (id > 0 && formCategoria(id)) cargar.run(); })), BorderLayout.SOUTH);
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Categorías del menú", Dialog.ModalityType.APPLICATION_MODAL);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
        alMostrar();
    }

    private boolean formCategoria(Integer id) {
        Map<String, Object> c = id == null ? null : Db.uno("SELECT * FROM categorias_menu WHERE id_categoria = ?", id);
        JTextField nombre = new JTextField(c == null ? "" : Db.str(c.get("nombre")), 18);
        JSpinner orden = Campos.entero(c == null ? 1 : Db.ent(c.get("orden")), 1, 100);
        JComboBox<String> tipo = new JComboBox<>(new String[]{"ALIMENTOS", "BEBIDAS"});
        if (c != null) tipo.setSelectedItem(Db.str(c.get("tipo")));
        JCheckBox activa = new JCheckBox("Activa", c == null || Db.ent(c.get("activa")) == 1);
        JComboBox<AccionesHotel.Opcion> estacion = AccionesHotel.combo(Db.query("SELECT id_estacion, nombre FROM estaciones WHERE activa = 1 ORDER BY orden, nombre"), "id_estacion", "nombre", true);
        if (c != null && c.get("id_estacion") != null) AccionesHotel.seleccionar(estacion, Db.ent(c.get("id_estacion")));
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre:", nombre).agregar("Orden:", orden)
                .agregar("Envía las comandas a:", estacion).agregar("Línea en la factura:", tipo).agregar("", activa)
                .nota("La estación decide a qué pantalla e impresora llega cada plato de esta categoría. Se crean en Restaurante > Estaciones de comanda. «(ninguno)» = se sirve directo, sin comanda.");
        return Campos.dialogo(this, id == null ? "Nueva categoría" : "Editar categoría", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            int est = AccionesHotel.idDe(estacion);
            Object idEst = est > 0 ? est : null;
            if (id == null) Db.insert("INSERT INTO categorias_menu (nombre, orden, tipo, id_estacion, activa) VALUES (?,?,?,?,?)",
                    nombre.getText().trim(), Campos.valorEntero(orden), tipo.getSelectedItem(), idEst, activa.isSelected() ? 1 : 0);
            else Db.exec("UPDATE categorias_menu SET nombre=?, orden=?, tipo=?, id_estacion=?, activa=? WHERE id_categoria=?",
                    nombre.getText().trim(), Campos.valorEntero(orden), tipo.getSelectedItem(), idEst, activa.isSelected() ? 1 : 0, id);
            return true;
        });
    }

    private static void guardarBarras(long idProducto, String codigo) {
        String c = codigo == null ? "" : codigo.trim();
        try { Db.exec("UPDATE productos_menu SET codigo_barras = ? WHERE id_producto = ?", c.isEmpty() ? null : c, idProducto); }
        catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("duplicate")) throw new IllegalArgumentException("Ese código de barras ya lo tiene otro producto.");
            throw e;
        }
    }
}
