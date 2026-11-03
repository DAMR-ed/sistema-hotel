package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.db.Db;
import hn.hotel.service.RestauranteService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.List;

/** Caja de tienda (retail): venta rápida sin mesas. Busca por nombre o escanea el código de barras, arma el carrito y cobra con factura. */
public class PosRetailPanel extends JPanel implements Actualizable {
    private record Item(int id, String nombre, BigDecimal precio, BigDecimal tasa) {}

    private List<Map<String, Object>> productos = new ArrayList<>();
    private final Map<Integer, BufferedImage> fotos = new HashMap<>();
    private final LinkedHashMap<Integer, BigDecimal> carrito = new LinkedHashMap<>();   // id producto -> cantidad
    private final Map<Integer, Item> items = new HashMap<>();
    private int categoriaActual = 0;
    private boolean conIsv = true;

    private final JTextField buscar = new JTextField(22);
    private final JPanel barraCategorias = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 6));
    private final JPanel grid = new RejillaAncha();
    private final DefaultTableModel mCarrito = Ui.modelo("Id", "Cant.", "Producto", "Importe");
    private final JTable tCarrito = Ui.tabla(mCarrito);
    private final JLabel lblTotal = new JLabel(" ");

    private static final class RejillaAncha extends JPanel implements Scrollable {
        RejillaAncha() { super(new WrapLayout(FlowLayout.LEFT, 10, 10)); setOpaque(false); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(24, r.height - 40); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    public PosRetailPanel() {
        setLayout(new BorderLayout(14, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel izq = new JPanel(new BorderLayout(0, 8));
        izq.setOpaque(false);
        JPanel arriba = new JPanel(new BorderLayout(0, 6));
        arriba.setOpaque(false);
        arriba.add(Ui.titulo("Caja de tienda"), BorderLayout.NORTH);
        buscar.putClientProperty("JTextField.placeholderText", "Escanee el código de barras o escriba el nombre y presione Enter");
        buscar.setFont(buscar.getFont().deriveFont(15f));
        buscar.addActionListener(e -> buscarOEscaner());
        buscar.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { pintarProductos(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { pintarProductos(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { pintarProductos(); }
        });
        JPanel filaBuscar = new JPanel(new BorderLayout(8, 0));
        filaBuscar.setOpaque(false);
        filaBuscar.add(buscar, BorderLayout.CENTER);
        arriba.add(filaBuscar, BorderLayout.CENTER);
        barraCategorias.setOpaque(false);
        JPanel sur2 = new JPanel(new BorderLayout());
        sur2.setOpaque(false);
        sur2.add(barraCategorias, BorderLayout.CENTER);
        arriba.add(sur2, BorderLayout.SOUTH);
        izq.add(arriba, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(grid);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        izq.add(sp, BorderLayout.CENTER);
        add(izq, BorderLayout.CENTER);

        JPanel der = new JPanel(new BorderLayout(0, 8));
        der.setOpaque(false);
        der.setPreferredSize(new Dimension(380, 100));
        der.add(new JLabel("<html><b style='font-size:14px'>Venta actual</b></html>"), BorderLayout.NORTH);
        tCarrito.getColumnModel().getColumn(0).setMaxWidth(0);
        tCarrito.getColumnModel().getColumn(0).setMinWidth(0);
        tCarrito.getColumnModel().getColumn(0).setPreferredWidth(0);
        tCarrito.getColumnModel().getColumn(1).setMaxWidth(60);
        Ui.alinearDerecha(tCarrito, 1, 3);
        der.add(new JScrollPane(tCarrito), BorderLayout.CENTER);
        JPanel pie = new JPanel(new GridLayout(0, 1, 6, 6));
        pie.setOpaque(false);
        lblTotal.setFont(lblTotal.getFont().deriveFont(15f));
        pie.add(lblTotal);
        pie.add(Ui.fila(Ui.boton("+", () -> cambiarCantidad(BigDecimal.ONE)), Ui.boton("−", () -> cambiarCantidad(BigDecimal.ONE.negate())),
                Ui.boton("Quitar", this::quitar), Ui.boton("Vaciar", this::vaciar)));
        JButton cobrar = Ui.botonPrimario("COBRAR Y FACTURAR", () -> Autorizacion.ejecutar(this, "pos.cobrar", "cobrar ventas", this::cobrar));
        cobrar.setFont(cobrar.getFont().deriveFont(Font.BOLD, 16f));
        pie.add(cobrar);
        der.add(pie, BorderLayout.SOUTH);
        add(der, BorderLayout.EAST);
    }

    @Override public void alMostrar() {
        conIsv = !"NO".equalsIgnoreCase(Config.texto("precios_con_isv", "SI").trim());
        try {
            productos = Db.query("SELECT p.id_producto, p.nombre, p.precio, p.tasa_isv, p.foto, p.codigo_barras, c.id_categoria, c.nombre AS categoria "
                    + "FROM productos_menu p JOIN categorias_menu c ON c.id_categoria = p.id_categoria WHERE p.activo = 1 AND c.activa = 1 ORDER BY c.orden, c.nombre, p.nombre");
        } catch (RuntimeException e) { Ui.error(this, "Falta ejecutar el script 14_tipo_negocio_retail.sql.\n" + e.getMessage()); return; }
        fotos.clear();
        items.clear();
        for (Map<String, Object> p : productos) {
            int id = Db.ent(p.get("id_producto"));
            BufferedImage im = Fotos.imagen(Fotos.bytes(p.get("foto")));
            if (im != null) fotos.put(id, im);
            items.put(id, new Item(id, Db.str(p.get("nombre")), Db.dec(p.get("precio")), Db.dec(p.get("tasa_isv"))));
        }
        pintarCategorias();
        pintarProductos();
        pintarCarrito();
        SwingUtilities.invokeLater(buscar::requestFocusInWindow);
    }

    // ------------------------------------------------------------------ catálogo
    private void pintarCategorias() {
        barraCategorias.removeAll();
        LinkedHashMap<Integer, String> cats = new LinkedHashMap<>();
        for (Map<String, Object> p : productos) cats.putIfAbsent(Db.ent(p.get("id_categoria")), Db.str(p.get("categoria")));
        barraCategorias.add(botonCategoria(0, "Todo"));
        cats.forEach((id, n) -> barraCategorias.add(botonCategoria(id, n)));
        barraCategorias.revalidate();
        barraCategorias.repaint();
    }

    private JButton botonCategoria(int id, String nombre) {
        JButton b = new JButton(nombre);
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        boolean sel = id == categoriaActual;
        b.putClientProperty("FlatLaf.style", "arc: 999; margin: 6,16,6,16; background: " + (sel ? "#2563EB" : "#E2E8F0") + "; foreground: " + (sel ? "#FFFFFF" : "#0F172A") + "; borderWidth: 0");
        b.addActionListener(e -> { categoriaActual = id; pintarCategorias(); pintarProductos(); });
        return b;
    }

    private BigDecimal visible(Item it) {
        return conIsv ? it.precio().multiply(BigDecimal.ONE.add(it.tasa().movePointLeft(2))).setScale(2, RoundingMode.HALF_UP) : it.precio();
    }

    private void pintarProductos() {
        grid.removeAll();
        String q = buscar.getText().trim().toLowerCase();
        if (productos.isEmpty()) {
            grid.add(new JLabel("<html><div style='width:420px'><b>Todavía no hay productos.</b><br>Créelos en «Productos y recetas» (con nombre, precio, ISV y código de barras).</div></html>"));
        }
        for (Map<String, Object> p : productos) {
            if (categoriaActual != 0 && Db.ent(p.get("id_categoria")) != categoriaActual) continue;
            if (!q.isEmpty() && !Db.str(p.get("nombre")).toLowerCase().contains(q) && !Db.str(p.get("codigo_barras")).contains(q)) continue;
            int id = Db.ent(p.get("id_producto"));
            Item it = items.get(id);
            JButton b = Fotos.tarjeta(it.nombre(), Ui.lempiras(visible(it)) + (conIsv ? "" : " + ISV"), fotos.get(id), 130);
            b.addActionListener(e -> { agregar(id, BigDecimal.ONE); buscar.requestFocusInWindow(); });
            grid.add(b);
        }
        grid.revalidate();
        grid.repaint();
    }

    /** Enter en el buscador: un código de barras exacto agrega el producto (lector de códigos); si no, deja filtrada la lista. */
    private void buscarOEscaner() {
        String q = buscar.getText().trim();
        if (q.isEmpty()) return;
        for (Map<String, Object> p : productos)
            if (q.equals(Db.str(p.get("codigo_barras")))) { agregar(Db.ent(p.get("id_producto")), BigDecimal.ONE); buscar.setText(""); return; }
        List<Map<String, Object>> coinciden = new ArrayList<>();
        for (Map<String, Object> p : productos) if (Db.str(p.get("nombre")).toLowerCase().contains(q.toLowerCase())) coinciden.add(p);
        if (coinciden.size() == 1) { agregar(Db.ent(coinciden.get(0).get("id_producto")), BigDecimal.ONE); buscar.setText(""); }
        else if (coinciden.isEmpty()) Toolkit.getDefaultToolkit().beep();
    }

    // ------------------------------------------------------------------ carrito
    private void agregar(int id, BigDecimal cant) {
        carrito.merge(id, cant, BigDecimal::add);
        pintarCarrito();
    }

    private void pintarCarrito() {
        mCarrito.setRowCount(0);
        BigDecimal sub = BigDecimal.ZERO, isv = BigDecimal.ZERO;
        for (Map.Entry<Integer, BigDecimal> e : carrito.entrySet()) {
            Item it = items.get(e.getKey());
            if (it == null) continue;
            BigDecimal imp = it.precio().multiply(e.getValue()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal i = imp.multiply(it.tasa()).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
            sub = sub.add(imp);
            isv = isv.add(i);
            mCarrito.addRow(new Object[]{it.id(), e.getValue().stripTrailingZeros().toPlainString(), it.nombre(), Ui.numero(conIsv ? imp.add(i) : imp)});
        }
        lblTotal.setText("<html>Subtotal " + Ui.lempiras(sub) + " &nbsp; ISV " + Ui.lempiras(isv)
                + "<br><b style='font-size:18px'>TOTAL " + Ui.lempiras(sub.add(isv)) + "</b></html>");
    }

    private Integer seleccionado() {
        int f = tCarrito.getSelectedRow();
        if (f < 0) { Ui.info(this, "Seleccione un producto de la venta."); return null; }
        return ((Number) mCarrito.getValueAt(f, 0)).intValue();
    }

    private void cambiarCantidad(BigDecimal delta) {
        Integer id = seleccionado();
        if (id == null) return;
        int fila = tCarrito.getSelectedRow();
        BigDecimal nueva = carrito.get(id).add(delta);
        if (nueva.signum() <= 0) carrito.remove(id); else carrito.put(id, nueva);
        pintarCarrito();
        if (fila < tCarrito.getRowCount()) tCarrito.setRowSelectionInterval(fila, fila);
    }

    private void quitar() {
        Integer id = seleccionado();
        if (id == null) return;
        carrito.remove(id);
        pintarCarrito();
    }

    private void vaciar() {
        if (carrito.isEmpty()) return;
        if (JOptionPane.showConfirmDialog(this, "¿Vaciar la venta actual?", "Venta", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            carrito.clear();
            pintarCarrito();
        }
    }

    // ------------------------------------------------------------------ cobro
    /** La venta se registra como una cuenta «para llevar» (así usa la misma factura, el mismo ISV, el inventario y los cortes de caja). */
    private void cobrar() {
        if (carrito.isEmpty()) { Ui.info(this, "La venta está vacía."); return; }
        int[] idComanda = {0};
        try {
            idComanda[0] = RestauranteService.abrirComanda("LLEVAR", null, 1);
            for (Map.Entry<Integer, BigDecimal> e : carrito.entrySet())
                RestauranteService.agregarPlato(idComanda[0], e.getKey(), e.getValue(), null);
        } catch (RuntimeException ex) {
            descartar(idComanda[0]);
            Ui.error(this, ex.getMessage());
            return;
        }
        boolean ok = FacturarDialog.comanda(this, idComanda[0]);
        if (ok) { carrito.clear(); pintarCarrito(); buscar.requestFocusInWindow(); }
        else descartar(idComanda[0]);   // no se cobró: no deja cuentas abiertas
    }

    private void descartar(int idComanda) {
        if (idComanda <= 0) return;
        try { RestauranteService.anularComanda(idComanda, "Venta de tienda cancelada"); } catch (RuntimeException ignorado) { }
    }
}
