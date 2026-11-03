package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Impresion;
import hn.hotel.service.RestauranteService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Punto de venta del restaurante: se elige la mesa, se toman los pedidos con botones grandes y se cobra por cuenta. */
public class PosRestaurantePanel extends JPanel implements Actualizable {
    private static final String VISTA_MESAS = "mesas", VISTA_ORDEN = "orden";

    private final CardLayout cartas = new CardLayout();
    private final JPanel raiz = new JPanel(cartas);

    // ---- vista de mesas
    /** Panel de mesas: siempre del ancho de la pantalla, así las mesas bajan de línea en vez de irse hacia la derecha. */
    private final JPanel rejilla = new RejillaMesas();

    private static final class RejillaMesas extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(24, r.height - 40); }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }
    private final JLabel lblResumenMesas = new JLabel(" ");
    private final javax.swing.Timer reloj;

    // ---- vista de orden
    private int idComanda;
    private int cuentaActual = 1;
    private final JLabel lblCabecera = new JLabel(" ");
    private final JPanel barraCategorias = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
    private final JPanel gridProductos = new JPanel(new GridLayout(0, 4, 10, 10));
    private final JPanel barraCuentas = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private final DefaultTableModel mLineas = Ui.modelo("Id", "Cant.", "Plato", "Importe", "Estado");
    private final JTable tLineas = Ui.tabla(mLineas);
    private final JLabel lblTotal = new JLabel(" ");
    private final JSpinner spCantidad = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
    private final JTextField txtNotas = new JTextField(16);
    private final JButton btnCobrar = new JButton("Cobrar esta cuenta");
    private List<Map<String, Object>> productos = new ArrayList<>();
    private List<Map<String, Object>> lineas = new ArrayList<>();
    private final Map<Integer, Integer> cobradas = new java.util.HashMap<>();   // num_cuenta -> id_factura
    private int categoriaActual = 0;
    private String nombreLista = "General";
    private final Map<Integer, BigDecimal> preciosLista = new java.util.HashMap<>();
    private final Map<Integer, java.awt.image.BufferedImage> fotos = new java.util.HashMap<>();
    private boolean ordenVisible;

    public PosRestaurantePanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        raiz.setOpaque(false);
        raiz.add(crearVistaMesas(), VISTA_MESAS);
        raiz.add(crearVistaOrden(), VISTA_ORDEN);
        add(raiz, BorderLayout.CENTER);
        reloj = new javax.swing.Timer(20_000, e -> { if (isShowing() && !ordenVisible) cargarMesas(); });
        reloj.start();
    }

    @Override public void alMostrar() {
        if (ordenVisible) cargarOrden(); else cargarMesas();
    }

    // =====================================================================  MESAS
    private JComponent crearVistaMesas() {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setOpaque(false);
        JPanel norte = new JPanel(new BorderLayout());
        norte.setOpaque(false);
        norte.add(new JLabel("<html><span style='font-size:20px'><b>Punto de venta · Restaurante</b></span></html>"), BorderLayout.WEST);
        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        der.setOpaque(false);
        der.add(lblResumenMesas);
        der.add(Ui.botonPrimario("+ Para llevar", this::nuevoParaLlevar));
        if (Sesion.permite("pos.disenar_mesas"))
            der.add(Ui.boton("Diseñar mesas y zonas", () -> irA("restaurante")));
        der.add(Ui.boton("Actualizar", this::cargarMesas));
        norte.add(der, BorderLayout.EAST);
        p.add(norte, BorderLayout.NORTH);
        rejilla.setLayout(new BoxLayout(rejilla, BoxLayout.Y_AXIS));
        rejilla.setOpaque(false);
        JScrollPane sp = new JScrollPane(rejilla);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private void irA(String modulo) {
        Window w = SwingUtilities.getWindowAncestor(this);
        if (w instanceof MainFrame mf) mf.mostrar(modulo);
    }

    private boolean soloSuyas() {
        return !Sesion.permite("pos.ver_todas") && "SI".equalsIgnoreCase(Config.texto("mesero_ve_solo_suyas", "SI"));
    }

    /** Total consumido de la comanda con ISV (cada plato con su tasa). */
    private static final String SQL_TOTAL_ISV = "(SELECT COALESCE(SUM(ROUND(d.cantidad*d.precio_unitario-d.descuento,2) * (1 + d.tasa_isv/100)),0) "
            + "FROM comanda_detalle d WHERE d.id_comanda = c.id_comanda AND d.estado <> 'ANULADO')";

    private boolean conIsv = true;

    /** Precio tal como se muestra: con o sin ISV según Configuración (precios_con_isv). */
    private BigDecimal visible(BigDecimal sinIsv, BigDecimal tasa) {
        if (!conIsv || tasa == null) return sinIsv;
        return sinIsv.multiply(BigDecimal.ONE.add(tasa.movePointLeft(2))).setScale(2, RoundingMode.HALF_UP);
    }

    private static String hora(int minutos) {
        return minutos >= 60 ? (minutos / 60) + " h " + (minutos % 60) + " min" : minutos + " min";
    }

    private void cargarMesas() {
        conIsv = !"NO".equalsIgnoreCase(Config.texto("precios_con_isv", "SI").trim());
        List<Map<String, Object>> mesas;
        List<Map<String, Object>> llevar;
        try {
            mesas = Db.query("SELECT m.id_mesa, m.nombre, m.capacidad, a.nombre AS area, c.id_comanda, c.estado AS estado_comanda, c.id_usuario, "
                    + "u.nombre AS mesero, TIMESTAMPDIFF(MINUTE, c.abierta_en, NOW()) AS minutos, COALESCE(v.subtotal,0) AS subtotal, "
                    + "c.cuenta_pedida_en, TIMESTAMPDIFF(MINUTE, c.cuenta_pedida_en, NOW()) AS min_cuenta, " + SQL_TOTAL_ISV + " AS total_isv "
                    + "FROM mesas m JOIN areas_restaurante a ON a.id_area = m.id_area AND a.activa = 1 "
                    + "LEFT JOIN comandas c ON c.id_mesa = m.id_mesa AND c.estado IN ('ABIERTA','CERRADA') "
                    + "LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario LEFT JOIN v_comandas_total v ON v.id_comanda = c.id_comanda "
                    + "WHERE m.estado <> 'INACTIVA' ORDER BY a.nombre, LENGTH(m.nombre), m.nombre");
            llevar = Db.query("SELECT c.id_comanda, c.estado AS estado_comanda, c.id_usuario, u.nombre AS mesero, "
                    + "TIMESTAMPDIFF(MINUTE, c.abierta_en, NOW()) AS minutos, COALESCE(v.subtotal,0) AS subtotal, "
                    + "c.cuenta_pedida_en, TIMESTAMPDIFF(MINUTE, c.cuenta_pedida_en, NOW()) AS min_cuenta, " + SQL_TOTAL_ISV + " AS total_isv "
                    + "FROM comandas c LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario LEFT JOIN v_comandas_total v ON v.id_comanda = c.id_comanda "
                    + "WHERE c.tipo = 'LLEVAR' AND c.estado IN ('ABIERTA','CERRADA') ORDER BY c.abierta_en");
        } catch (RuntimeException e) { Ui.error(this, e.getMessage()); return; }

        rejilla.removeAll();
        int ocupadas = 0, libres = 0;
        String areaActual = null;
        JPanel fila = null;
        for (Map<String, Object> m : mesas) {
            String area = Db.str(m.get("area"));
            if (!area.equals(areaActual)) {
                areaActual = area;
                rejilla.add(encabezado(area));
                fila = nuevaFila();
                rejilla.add(fila);
            }
            boolean ocup = m.get("id_comanda") != null;
            if (ocup) ocupadas++; else libres++;
            fila.add(tarjetaMesa(Db.str(m.get("nombre")), ocup, m));
        }
        if (!llevar.isEmpty()) {
            rejilla.add(encabezado("Para llevar"));
            fila = nuevaFila();
            rejilla.add(fila);
            for (Map<String, Object> m : llevar) fila.add(tarjetaMesa("Pedido #" + m.get("id_comanda"), true, m));
        }
        lblResumenMesas.setText("<html><span style='color:#2E9E5B'>● " + libres + " libres</span> &nbsp; <span style='color:#D64545'>● " + ocupadas + " ocupadas</span></html>");
        rejilla.add(Box.createVerticalGlue());
        rejilla.revalidate();
        rejilla.repaint();
    }

    private JComponent encabezado(String texto) {
        JLabel l = new JLabel(texto.toUpperCase());
        l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
        l.setForeground(Tema.TEXTO_SUAVE);
        l.setBorder(BorderFactory.createEmptyBorder(14, 2, 6, 0));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JPanel nuevaFila() {
        JPanel f = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 10));
        f.setOpaque(false);
        f.setAlignmentX(Component.LEFT_ALIGNMENT);
        return f;
    }

    private JButton tarjetaMesa(String nombre, boolean ocupada, Map<String, Object> m) {
        String estadoC = Db.str(m.get("estado_comanda"));
        boolean pidioCuenta = ocupada && (m.get("cuenta_pedida_en") != null || estadoC.equals("CERRADA"));
        String color = !ocupada ? "#2E9E5B" : pidioCuenta ? "#7C3AED" : "#D64545";
        StringBuilder h = new StringBuilder("<html><div style='width:150px;text-align:center'><span style='font-size:15px'><b>" + nombre + "</b></span><br>");
        if (!ocupada) {
            h.append("<span style='font-size:11px'>Libre · ").append(m.get("capacidad")).append(" pers.</span>");
        } else {
            BigDecimal total = conIsv ? Db.dec(m.get("total_isv")).setScale(2, RoundingMode.HALF_UP) : Db.dec(m.get("subtotal"));
            h.append("<span style='font-size:14px'><b>").append(Ui.lempiras(total)).append("</b></span><br>")
             .append("<span style='font-size:10px'>Abierta hace ").append(hora(Db.ent(m.get("minutos")))).append("</span><br>")
             .append("<span style='font-size:10px'>").append(Db.str(m.get("mesero"))).append("</span>");
            if (pidioCuenta) {
                h.append("<br><span style='font-size:10px'><b>CUENTA PEDIDA");
                if (m.get("min_cuenta") != null) h.append(" · hace ").append(hora(Db.ent(m.get("min_cuenta"))));
                h.append("</b></span>");
            }
        }
        h.append("</div></html>");
        JButton b = new JButton(h.toString());
        b.setPreferredSize(new Dimension(172, pidioCuenta ? 118 : 104));
        b.setFocusable(false);
        boolean ajena = ocupada && soloSuyas() && m.get("id_usuario") != null && Db.ent(m.get("id_usuario")) != Sesion.idUsuario;
        if (ajena) color = "#94A3B8";
        b.putClientProperty("FlatLaf.style", "arc: 18; background: " + color + "; foreground: #FFFFFF; borderWidth: 0");
        b.addActionListener(e -> Ui.seguro(this, () -> {
            if (ajena) { Ui.info(this, "Esta mesa la atiende " + Db.str(m.get("mesero")) + "."); return; }
            if (ocupada) { abrirOrden(Db.ent(m.get("id_comanda"))); return; }
            int pers = pedirPersonas(nombre, Db.ent(m.get("capacidad")));
            if (pers <= 0) return;
            abrirOrden(RestauranteService.abrirComanda("SALON", Db.ent(m.get("id_mesa")), pers));
        }));
        return b;
    }

    private int pedirPersonas(String mesa, int sugerido) {
        JSpinner sp = Campos.entero(Math.max(1, Math.min(sugerido, 4)), 1, 50);
        Campos.Formulario f = new Campos.Formulario().agregar("¿Cuántas personas?", sp);
        return Campos.dialogo(this, "Abrir " + mesa, f, () -> true) ? Campos.valorEntero(sp) : 0;
    }

    private void nuevoParaLlevar() {
        Ui.seguro(this, () -> abrirOrden(RestauranteService.abrirComanda("LLEVAR", null, 1)));
    }

    // =====================================================================  ORDEN
    private JComponent crearVistaOrden() {
        JPanel p = new JPanel(new BorderLayout(14, 0));
        p.setOpaque(false);

        // ----- izquierda: menú
        JPanel izq = new JPanel(new BorderLayout(0, 8));
        izq.setOpaque(false);
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        JButton volver = Ui.boton("← Mesas", this::volverAMesas);
        volver.setFont(volver.getFont().deriveFont(Font.BOLD, 14f));
        lblCabecera.setFont(lblCabecera.getFont().deriveFont(Font.BOLD, 18f));
        JPanel cab = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        cab.setOpaque(false);
        cab.add(volver);
        cab.add(lblCabecera);
        arriba.add(cab, BorderLayout.NORTH);
        barraCategorias.setOpaque(false);
        arriba.add(barraCategorias, BorderLayout.CENTER);
        izq.add(arriba, BorderLayout.NORTH);

        JPanel envoltura = new JPanel(new BorderLayout());
        envoltura.setOpaque(false);
        gridProductos.setOpaque(false);
        envoltura.add(gridProductos, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(envoltura);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        izq.add(sp, BorderLayout.CENTER);

        JPanel pie = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        pie.setOpaque(false);
        pie.add(new JLabel("Cantidad:"));
        pie.add(spCantidad);
        pie.add(new JLabel("Nota para cocina:"));
        txtNotas.putClientProperty("JTextField.placeholderText", "sin cebolla, término medio...");
        pie.add(txtNotas);
        izq.add(pie, BorderLayout.SOUTH);

        // ----- derecha: cuenta
        Tema.Tarjeta der = new Tema.Tarjeta(new BorderLayout(0, 8), null);
        der.setPreferredSize(new Dimension(440, 0));
        barraCuentas.setOpaque(false);
        der.add(barraCuentas, BorderLayout.NORTH);
        tLineas.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        tLineas.setAutoCreateRowSorter(false);
        tLineas.getColumnModel().getColumn(0).setMinWidth(0);
        tLineas.getColumnModel().getColumn(0).setMaxWidth(0);
        tLineas.getColumnModel().getColumn(1).setMaxWidth(50);
        tLineas.getColumnModel().getColumn(3).setMaxWidth(90);
        tLineas.getColumnModel().getColumn(4).setMaxWidth(90);
        tLineas.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                String est = String.valueOf(t.getValueAt(r, 4));
                if (!sel) comp.setForeground(est.equals("PENDIENTE") ? new Color(0xB36B00) : est.equals("LISTO") ? new Color(0x2E9E5B) : Tema.TEXTO);
                setHorizontalAlignment(c == 3 ? SwingConstants.RIGHT : SwingConstants.LEFT);
                return comp;
            }
        });
        der.add(new JScrollPane(tLineas), BorderLayout.CENTER);

        JPanel sur = new JPanel(new BorderLayout(0, 8));
        sur.setOpaque(false);
        lblTotal.setFont(lblTotal.getFont().deriveFont(13f));
        sur.add(lblTotal, BorderLayout.NORTH);
        JPanel botones = new JPanel(new GridLayout(0, 2, 6, 6));
        botones.setOpaque(false);
        botones.add(Ui.botonPrimario("Enviar a cocina", this::enviar));
        btnCobrar.addActionListener(e -> Autorizacion.ejecutar(this, "pos.cobrar", "cobrar cuentas", this::cobrar));
        btnCobrar.setBackground(new Color(0x2E9E5B));
        btnCobrar.setForeground(Color.WHITE);
        btnCobrar.setFont(btnCobrar.getFont().deriveFont(Font.BOLD));
        botones.add(btnCobrar);
        botones.add(Ui.boton("Quitar plato", this::anularPlato));
        botones.add(Ui.botonPrimario("Pedir cuenta", this::pedirCuenta));
        botones.add(Ui.boton("Pre-cuenta", () -> Autorizacion.ejecutar(this, "pos.precuenta", "imprimir la pre-cuenta", this::preCuenta)));
        botones.add(Ui.boton("Mover a cuenta...", () -> Autorizacion.ejecutar(this, "pos.dividir", "dividir la cuenta", this::moverACuenta)));
        botones.add(Ui.boton("Partir plato...", () -> Autorizacion.ejecutar(this, "pos.dividir", "dividir la cuenta", this::partirPlato)));
        if (Sesion.hotelActivo) botones.add(Ui.boton("Cargar a habitación", () -> Autorizacion.ejecutar(this, "pos.cargar_habitacion", "cargar la cuenta a una habitación", this::cargarAHabitacion)));
        botones.add(Ui.boton("Lista de precios", () -> Autorizacion.ejecutar(this, "pos.cambiar_lista", "cambiar la lista de precios", this::cambiarLista)));
        botones.add(Ui.boton("Cambiar de mesa", () -> Autorizacion.ejecutar(this, "pos.cambiar_mesa", "cambiar la cuenta de mesa", this::cambiarMesa)));
        botones.add(Ui.boton("Transferir mesero", () -> Autorizacion.ejecutar(this, "pos.transferir", "transferir la mesa a otro mesero", this::transferir)));
        botones.add(Ui.boton("Anular comanda", () -> Autorizacion.ejecutar(this, "pos.anular_comanda", "anular la comanda completa", this::anularComanda)));
        sur.add(botones, BorderLayout.CENTER);
        der.add(sur, BorderLayout.SOUTH);

        p.add(izq, BorderLayout.CENTER);
        p.add(der, BorderLayout.EAST);
        return p;
    }

    private void abrirOrden(int id) {
        idComanda = id;
        cuentaActual = 1;
        categoriaActual = 0;
        ordenVisible = true;
        cartas.show(raiz, VISTA_ORDEN);
        cargarMenu();
        cargarOrden();
    }

    private void volverAMesas() {
        ordenVisible = false;
        cartas.show(raiz, VISTA_MESAS);
        cargarMesas();
    }

    private void cargarMenu() {
        conIsv = !"NO".equalsIgnoreCase(Config.texto("precios_con_isv", "SI").trim());
        List<Map<String, Object>> filas;
        try {
            filas = Db.query("SELECT p.id_producto, p.nombre, p.precio, p.tasa_isv, p.foto, c.id_categoria, c.nombre AS categoria FROM productos_menu p "
                    + "JOIN categorias_menu c ON c.id_categoria = p.id_categoria WHERE p.activo = 1 AND c.activa = 1 ORDER BY c.orden, c.nombre, p.nombre");
        } catch (RuntimeException e) {   // la columna foto aún no existe (falta ejecutar el script 06)
            filas = Db.query("SELECT p.id_producto, p.nombre, p.precio, c.id_categoria, c.nombre AS categoria FROM productos_menu p "
                    + "JOIN categorias_menu c ON c.id_categoria = p.id_categoria WHERE p.activo = 1 AND c.activa = 1 ORDER BY c.orden, c.nombre, p.nombre");
        }
        productos = filas;
        cargarLista();
        fotos.clear();
        for (Map<String, Object> p : productos) {
            java.awt.image.BufferedImage im = Fotos.imagen(Fotos.bytes(p.get("foto")));
            if (im != null) fotos.put(Db.ent(p.get("id_producto")), im);
        }
        pintarCategorias();
        pintarProductos();
    }

    /** Lista de precios vigente para esta comanda (la de la cuenta, la de su zona o la general). */
    private void cargarLista() {
        preciosLista.clear();
        nombreLista = "General";
        try {
            Map<String, Object> l = Db.uno("SELECT lp.id_lista, lp.nombre FROM comandas c LEFT JOIN mesas m ON m.id_mesa = c.id_mesa LEFT JOIN areas_restaurante a ON a.id_area = m.id_area "
                    + "JOIN listas_precios lp ON lp.id_lista = COALESCE(c.id_lista, a.id_lista) WHERE c.id_comanda = ?", idComanda);
            if (l == null) return;
            nombreLista = Db.str(l.get("nombre"));
            for (Map<String, Object> f : Db.query("SELECT id_producto, precio FROM precios_lista WHERE id_lista = ?", l.get("id_lista")))
                preciosLista.put(Db.ent(f.get("id_producto")), Db.dec(f.get("precio")));
        } catch (RuntimeException e) { /* el script 11 aún no se ejecutó */ }
    }

    private void cambiarLista() {
        List<Map<String, Object>> ls = Db.query("SELECT id_lista, nombre FROM listas_precios WHERE activa = 1 ORDER BY nombre");
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(ls, "id_lista", "nombre", true);
        ((DefaultComboBoxModel<AccionesHotel.Opcion>) c.getModel()).removeElementAt(0);
        c.insertItemAt(new AccionesHotel.Opcion(0, "General"), 0);
        c.setSelectedIndex(0);
        Campos.Formulario f = new Campos.Formulario().agregar("Lista de precios:", c)
                .nota("Los platos que ya están en la cuenta conservan su precio; los nuevos usarán esta lista.");
        if (Campos.dialogo(this, "Lista de precios", f, () -> {
            int id = AccionesHotel.idDe(c);
            RestauranteService.cambiarLista(idComanda, id == 0 ? null : id);
            return true;
        })) { cargarMenu(); }
    }

    private void pintarCategorias() {
        barraCategorias.removeAll();
        java.util.LinkedHashMap<Integer, String> cats = new java.util.LinkedHashMap<>();
        for (Map<String, Object> p : productos) cats.putIfAbsent(Db.ent(p.get("id_categoria")), Db.str(p.get("categoria")));
        barraCategorias.add(botonCategoria(0, "Todo"));
        cats.forEach((id, nombre) -> barraCategorias.add(botonCategoria(id, nombre)));
        barraCategorias.revalidate();
        barraCategorias.repaint();
    }

    private JButton botonCategoria(int id, String nombre) {
        JButton b = new JButton(nombre);
        b.setFocusable(false);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 14f));
        boolean sel = id == categoriaActual;
        b.putClientProperty("FlatLaf.style", "arc: 999; margin: 8,20,8,20; background: " + (sel ? "#2563EB" : "#E2E8F0") + "; foreground: " + (sel ? "#FFFFFF" : "#0F172A") + "; borderWidth: 0");
        b.addActionListener(e -> { categoriaActual = id; pintarCategorias(); pintarProductos(); });
        return b;
    }

    private void pintarProductos() {
        gridProductos.removeAll();
        if (productos.isEmpty()) {
            JLabel vacio = new JLabel("<html><div style='width:420px'><b>Todavía no hay platos en el menú.</b><br>"
                    + "Cree categorías y platos (con foto) en «Menú y recetas». Los platos activos aparecen aquí.</div></html>");
            vacio.setBorder(BorderFactory.createEmptyBorder(30, 10, 10, 10));
            gridProductos.setLayout(new FlowLayout(FlowLayout.LEFT));
            gridProductos.add(vacio);
            if (Sesion.puede("menu")) gridProductos.add(Ui.botonPrimario("Ir a Menú y recetas", () -> irA("menu")));
            gridProductos.revalidate();
            gridProductos.repaint();
            return;
        }
        gridProductos.setLayout(new GridLayout(0, 4, 10, 10));
        for (Map<String, Object> p : productos) {
            if (categoriaActual != 0 && Db.ent(p.get("id_categoria")) != categoriaActual) continue;
            JButton b = Fotos.tarjeta(Db.str(p.get("nombre")), Ui.lempiras(visible(preciosLista.getOrDefault(Db.ent(p.get("id_producto")), Db.dec(p.get("precio"))), p.get("tasa_isv") == null ? null : Db.dec(p.get("tasa_isv")))) + (conIsv ? "" : " + ISV"), fotos.get(Db.ent(p.get("id_producto"))), 140);
            int idProd = Db.ent(p.get("id_producto"));
            b.addActionListener(e -> Ui.seguro(this, () -> {
                BigDecimal cant = BigDecimal.valueOf(Campos.valorEntero(spCantidad));
                List<Integer> opciones = List.of();
                var grupos = hn.hotel.service.OpcionesService.gruposDeProducto(idProd);
                if (!grupos.isEmpty()) {
                    opciones = ElegirOpcionesDialog.mostrar(this, Db.str(p.get("nombre")), grupos);
                    if (opciones == null) return;   // canceló
                }
                RestauranteService.agregarPlato(idComanda, idProd, cant, txtNotas.getText(), cuentaActual, opciones);
                txtNotas.setText("");
                spCantidad.setValue(1);
                cargarOrden();
            }));
            gridProductos.add(b);
        }
        gridProductos.revalidate();
        gridProductos.repaint();
    }

    private void cargarOrden() {
        Map<String, Object> c = Db.uno("SELECT COALESCE(m.nombre, 'Para llevar') AS donde, c.num_personas, c.estado, u.nombre AS mesero "
                + "FROM comandas c LEFT JOIN mesas m ON m.id_mesa = c.id_mesa LEFT JOIN usuarios u ON u.id_usuario = c.id_usuario WHERE c.id_comanda = ?", idComanda);
        if (c == null || !(Db.str(c.get("estado")).equals("ABIERTA") || Db.str(c.get("estado")).equals("CERRADA"))) { volverAMesas(); return; }
        lblCabecera.setText(Db.str(c.get("donde")) + "  ·  " + Db.str(c.get("num_personas")) + " pers.  ·  " + Db.str(c.get("mesero"))
                + (nombreLista.equals("General") ? "" : "  ·  Lista: " + nombreLista));
        lineas = Db.query("SELECT id_detalle, num_cuenta, cantidad, descripcion, notas, estado, tasa_isv, ROUND(cantidad * precio_unitario - descuento, 2) AS importe "
                + "FROM comanda_detalle WHERE id_comanda = ? AND estado <> 'ANULADO' ORDER BY id_detalle", idComanda);
        cobradas.clear();
        for (Map<String, Object> r : Db.query("SELECT num_cuenta, id_factura FROM comanda_cuentas WHERE id_comanda = ? AND id_factura IS NOT NULL", idComanda))
            cobradas.put(Db.ent(r.get("num_cuenta")), Db.ent(r.get("id_factura")));

        TreeSet<Integer> cuentas = new TreeSet<>(cobradas.keySet());
        cuentas.add(1);
        for (Map<String, Object> l : lineas) cuentas.add(Db.ent(l.get("num_cuenta")));
        for (Map<String, Object> r : Db.query("SELECT num_cuenta FROM comanda_cuentas WHERE id_comanda = ?", idComanda)) cuentas.add(Db.ent(r.get("num_cuenta")));
        if (!cuentas.contains(cuentaActual)) cuentaActual = cuentas.first();

        barraCuentas.removeAll();
        for (int n : cuentas) {
            boolean sel = n == cuentaActual, pagada = cobradas.containsKey(n);
            JButton b = new JButton((pagada ? "✓ " : "") + "Cuenta " + n);
            b.setFocusable(false);
            b.putClientProperty("FlatLaf.style", "arc: 999; margin: 4,14,4,14; borderWidth: 0; background: " + (sel ? "#2563EB" : "#E2E8F0") + "; foreground: " + (sel ? "#FFFFFF" : "#0F172A"));
            b.addActionListener(e -> { cuentaActual = n; cargarOrden(); });
            barraCuentas.add(b);
        }
        JButton mas = new JButton("+");
        mas.setFocusable(false);
        mas.setToolTipText("Nueva cuenta (para dividir)");
        mas.putClientProperty("FlatLaf.style", "arc: 999; borderWidth: 0; background: #E2E8F0");
        int siguiente = cuentas.last() + 1;
        mas.addActionListener(e -> Ui.seguro(this, () -> {
            RestauranteService.ponerEtiquetaCuenta(idComanda, siguiente, null);
            cuentaActual = siguiente;
            cargarOrden();
        }));
        barraCuentas.add(mas);
        barraCuentas.revalidate();
        barraCuentas.repaint();

        mLineas.setRowCount(0);
        BigDecimal subCuenta = BigDecimal.ZERO, isvCuenta = BigDecimal.ZERO, totTodo = BigDecimal.ZERO;
        for (Map<String, Object> l : lineas) {
            BigDecimal imp = Db.dec(l.get("importe"));
            BigDecimal isv = imp.multiply(Db.dec(l.get("tasa_isv"))).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
            if (!cobradas.containsKey(Db.ent(l.get("num_cuenta")))) totTodo = totTodo.add(imp).add(isv);
            if (Db.ent(l.get("num_cuenta")) != cuentaActual) continue;
            subCuenta = subCuenta.add(imp);
            isvCuenta = isvCuenta.add(isv);
            String nombre = Db.str(l.get("descripcion")) + (l.get("notas") == null ? "" : "  (" + l.get("notas") + ")");
            mLineas.addRow(new Object[]{l.get("id_detalle"), Db.dec(l.get("cantidad")).stripTrailingZeros().toPlainString(), nombre,
                    Ui.numero(conIsv ? imp.add(isv) : imp), l.get("estado")});
        }
        boolean pagada = cobradas.containsKey(cuentaActual);
        lblTotal.setText("<html>Cuenta " + cuentaActual + (pagada ? " <b style='color:#2E9E5B'>(cobrada)</b>" : "") + ":<br>"
                + "Subtotal " + Ui.lempiras(subCuenta) + " &nbsp; ISV " + Ui.lempiras(isvCuenta) + " &nbsp; <b style='font-size:14px'>Total " + Ui.lempiras(subCuenta.add(isvCuenta)) + "</b><br>"
                + "<span style='color:#64748B'>Pendiente de toda la mesa: " + Ui.lempiras(totTodo) + " (con ISV)"
                + (conIsv ? " · importes de la lista con ISV" : "") + "</span></html>");
        btnCobrar.setEnabled(!pagada);
        btnCobrar.setToolTipText(Sesion.permite("pos.cobrar") ? null : "Su rol no cobra: un supervisor puede autorizarlo con su contraseña.");
    }

    // ---------------------------------------------------------------------  acciones
    private void enviar() {
        Ui.seguro(this, () -> {
            RestauranteService.Envio e = RestauranteService.enviar(idComanda);
            cargarOrden();
            Ui.enSegundoPlano(this, () -> Impresion.imprimirAuto(e.tickets()), errores -> {
                if (!errores.isEmpty()) Ui.error(this, "El pedido llegó a la pantalla de cocina, pero no se pudo imprimir:\n" + String.join("\n", errores));
            });
        });
    }

    private List<Integer> seleccionados() {
        List<Integer> ids = new ArrayList<>();
        for (int r : tLineas.getSelectedRows()) ids.add(Integer.parseInt(mLineas.getValueAt(r, 0).toString()));
        return ids;
    }

    private void anularPlato() {
        List<Integer> ids = seleccionados();
        if (ids.isEmpty()) { Ui.info(this, "Seleccione los platos que quiere quitar."); return; }
        boolean hayEnviados = false;
        for (int r : tLineas.getSelectedRows()) if (!"PENDIENTE".equals(String.valueOf(mLineas.getValueAt(r, 4)))) hayEnviados = true;
        Runnable quitar = () -> {
            String motivo = JOptionPane.showInputDialog(this, "Motivo:", "Quitar plato", JOptionPane.QUESTION_MESSAGE);
            if (motivo == null) return;
            Ui.seguro(this, () -> { for (int id : ids) RestauranteService.anularPlato(id, motivo); cargarOrden(); });
        };
        if (hayEnviados) Autorizacion.ejecutar(this, "pos.anular_enviado", "quitar platos ya enviados a cocina", quitar);
        else quitar.run();
    }

    /** Cuentas a las que se puede pasar un plato: todas las abiertas menos la actual, más una nueva. */
    private JComboBox<Integer> comboCuentasDestino() {
        TreeSet<Integer> cs = new TreeSet<>();
        cs.add(1);
        for (Map<String, Object> l : lineas) cs.add(Db.ent(l.get("num_cuenta")));
        cs.add(cuentaActual);
        cs.add(cs.last() + 1);
        cs.remove(cuentaActual);
        cs.removeAll(cobradas.keySet());
        return new JComboBox<>(cs.toArray(new Integer[0]));
    }

    private void moverACuenta() {
        List<Integer> ids = seleccionados();
        if (ids.isEmpty()) { Ui.info(this, "Seleccione en la lista los platos que irán a otra cuenta (puede marcar varios)."); return; }
        JComboBox<Integer> c = comboCuentasDestino();
        Campos.Formulario f = new Campos.Formulario().agregar("Pasar a la cuenta:", c);
        if (!Campos.dialogo(this, "Dividir cuenta", f, () -> {
            RestauranteService.moverPlatos(idComanda, ids, (Integer) c.getSelectedItem());
            return true;
        })) return;
        cargarOrden();
    }

    private void partirPlato() {
        List<Integer> ids = seleccionados();
        if (ids.size() != 1) { Ui.info(this, "Seleccione UN solo plato para partirlo (por ejemplo 2 pizzas: 1 para cada cuenta)."); return; }
        JTextField cant = new JTextField("1", 6);
        JComboBox<Integer> c = comboCuentasDestino();
        Campos.Formulario f = new Campos.Formulario().agregar("Cantidad a mover:", cant).agregar("A la cuenta:", c)
                .nota("Puede usar decimales (0.5) para repartir un plato compartido.");
        Campos.dialogo(this, "Partir plato", f, () -> {
            RestauranteService.dividirPlato(ids.get(0), Ui.leerDinero(cant.getText(), "cantidad"), (Integer) c.getSelectedItem());
            return true;
        });
        cargarOrden();
    }

    private void cobrar() {
        BigDecimal pct = Config.numero("propina_sugerida", "10");
        BigDecimal sub = BigDecimal.ZERO;
        boolean hayOtras = false;
        for (Map<String, Object> l : lineas) {
            int n = Db.ent(l.get("num_cuenta"));
            if (n == cuentaActual) sub = sub.add(Db.dec(l.get("importe")));
            else if (!cobradas.containsKey(n)) hayOtras = true;
        }
        if (sub.signum() <= 0) { Ui.info(this, "La cuenta " + cuentaActual + " no tiene platos."); return; }
        JTextField propina = new JTextField(sub.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).toPlainString(), 10);
        Campos.Formulario f = new Campos.Formulario().agregar("Propina voluntaria (L):", propina)
                .nota("Sugerida " + pct.stripTrailingZeros().toPlainString() + "%. No lleva ISV ni va en la factura. Ponga 0 si no deja.");
        BigDecimal[] prop = {BigDecimal.ZERO};
        if (!Campos.dialogo(this, "Cobrar cuenta " + cuentaActual, f, () -> { prop[0] = Ui.leerDinero(propina.getText(), "propina"); return true; })) return;
        boolean subcuenta = hayOtras || !cobradas.isEmpty() || cuentaActual != 1;
        boolean ok = FacturarDialog.comanda(this, idComanda, subcuenta ? Integer.valueOf(cuentaActual) : null);
        if (ok) {
            Ui.seguro(this, () -> RestauranteService.sumarPropina(idComanda, prop[0]));
            if (!hayOtras) volverAMesas(); else cargarOrden();
        }
    }

    /** "Tirar la cuenta": el mesero avisa que la mesa pidió la cuenta; se imprime la pre-cuenta y la mesa cambia de color. */
    private void pedirCuenta() {
        Ui.seguro(this, () -> {
            RestauranteService.pedirCuenta(idComanda);
            Ui.enSegundoPlano(this, () -> { try { return Impresion.imprimirPreCuenta(idComanda); } catch (RuntimeException e) { return false; } }, impresa -> { });
            Ui.info(this, "Cuenta pedida. La mesa queda marcada para caja" + "." );
            volverAMesas();
        });
    }

    private void preCuenta() {
        Ui.seguro(this, () -> {
            String texto = Impresion.preCuenta(idComanda);
            boolean impresa;
            try { impresa = Impresion.imprimirPreCuenta(idComanda); } catch (RuntimeException e) { impresa = false; }
            if (impresa) { Ui.info(this, "Pre-cuenta enviada a la impresora de caja."); return; }
            JTextArea ta = new JTextArea(texto, 18, Impresion.ancho() + 4);
            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            ta.setEditable(false);
            Object[] op = {"Imprimir", "Cerrar"};
            if (JOptionPane.showOptionDialog(this, new JScrollPane(ta), "Pre-cuenta", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, op, op[1]) == 0)
                try { ta.print(); } catch (Exception ex) { Ui.error(this, ex.getMessage()); }
        });
    }

    private void cargarAHabitacion() {
        List<Map<String, Object>> en = Db.query("SELECT e.id_estancia, CONCAT('Hab. ', h.numero, ' - ', hu.nombre) AS txt FROM estancias e "
                + "JOIN habitaciones h ON h.id_habitacion = e.id_habitacion JOIN huespedes hu ON hu.id_huesped = e.id_huesped WHERE e.estado = 'ACTIVA' ORDER BY h.numero");
        if (en.isEmpty()) { Ui.info(this, "No hay huéspedes en casa."); return; }
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(en, "id_estancia", "txt", false);
        Campos.Formulario f = new Campos.Formulario().agregar("Habitación:", c)
                .nota("Se carga TODO lo pendiente de la mesa a la cuenta del huésped; se factura en su check-out.");
        if (Campos.dialogo(this, "Cargar a habitación", f, () -> { RestauranteService.cargarAHabitacion(idComanda, AccionesHotel.idDe(c)); return true; })) {
            Ui.info(this, "Cargado a la habitación.");
            volverAMesas();
        }
    }

    private void cambiarMesa() {
        List<Map<String, Object>> libres = Db.query("SELECT m.id_mesa, CONCAT(a.nombre, ' · ', m.nombre) AS txt FROM mesas m JOIN areas_restaurante a ON a.id_area = m.id_area "
                + "WHERE m.estado = 'LIBRE' ORDER BY a.nombre, m.nombre");
        if (libres.isEmpty()) { Ui.info(this, "No hay mesas libres."); return; }
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(libres, "id_mesa", "txt", false);
        Campos.Formulario f = new Campos.Formulario().agregar("Nueva mesa:", c);
        if (Campos.dialogo(this, "Cambiar de mesa", f, () -> { RestauranteService.moverAMesa(idComanda, AccionesHotel.idDe(c)); return true; })) cargarOrden();
    }

    private void transferir() {
        List<Map<String, Object>> us = Db.query("SELECT id_usuario, nombre FROM usuarios WHERE activo = 1 AND rol IN ('MESERO','SUPERVISOR','CAJERO','ADMIN') ORDER BY nombre");
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(us, "id_usuario", "nombre", false);
        Campos.Formulario f = new Campos.Formulario().agregar("Nuevo mesero:", c);
        if (Campos.dialogo(this, "Transferir mesa", f, () -> { RestauranteService.transferirMesero(idComanda, AccionesHotel.idDe(c)); return true; })) cargarOrden();
    }

    private void anularComanda() {
        String motivo = JOptionPane.showInputDialog(this, "Motivo de la anulación de TODA la comanda:", "Anular comanda", JOptionPane.QUESTION_MESSAGE);
        if (motivo == null) return;
        Ui.seguro(this, () -> { RestauranteService.anularComanda(idComanda, motivo); volverAMesas(); });
    }
}
