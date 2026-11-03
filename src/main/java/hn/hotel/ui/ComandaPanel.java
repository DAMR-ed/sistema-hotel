package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.RestauranteService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Cuenta de una mesa (o de un pedido para llevar): agregar platos, enviar a cocina, cerrar, cobrar o cargar a habitación. */
public class ComandaPanel extends JPanel {
    private int idComanda = 0;
    private String estadoComanda = "";
    private final Runnable alCambiar;

    private final JLabel lblTitulo = new JLabel("Seleccione una mesa");
    private final JComboBox<AccionesHotel.Opcion> cmbCategoria = new JComboBox<>();
    private final DefaultTableModel mProductos = Ui.modelo("Id", "Producto", "Precio");
    private final JTable tProductos = Ui.tabla(mProductos);
    private final JSpinner spCantidad = new JSpinner(new SpinnerNumberModel(1, 1, 99, 1));
    private final JTextField txtNotas = new JTextField(14);
    private final DefaultTableModel mDetalle = Ui.modelo("Id", "Cant.", "Plato", "Notas", "P. unit.", "Importe", "Estado");
    private final JTable tDetalle = Ui.tabla(mDetalle);
    private final JLabel lblTotales = new JLabel(" ");
    private final JButton btnAgregar, btnEnviar, btnAnularPlato, btnCerrar, btnReabrir, btnCargar, btnCobrar, btnAnularComanda;

    public ComandaPanel(Runnable alCambiar) {
        this.alCambiar = alCambiar;
        setLayout(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        lblTitulo.setFont(lblTitulo.getFont().deriveFont(Font.BOLD, 16f));
        add(lblTitulo, BorderLayout.NORTH);

        // --- selector de productos
        JPanel selector = new JPanel(new BorderLayout(4, 4));
        selector.setBorder(BorderFactory.createTitledBorder("Menú"));
        selector.add(cmbCategoria, BorderLayout.NORTH);
        tProductos.getColumnModel().getColumn(0).setMaxWidth(0);
        tProductos.getColumnModel().getColumn(0).setMinWidth(0);
        tProductos.getColumnModel().getColumn(0).setPreferredWidth(0);
        Ui.alinearDerecha(tProductos, 2);
        selector.add(new JScrollPane(tProductos), BorderLayout.CENTER);
        btnAgregar = Ui.boton("Agregar", this::agregar);
        selector.add(Ui.fila(new JLabel("Cant.:"), spCantidad, new JLabel("Nota:"), txtNotas, btnAgregar), BorderLayout.SOUTH);
        selector.setPreferredSize(new Dimension(380, 230));

        // --- detalle
        JPanel detalle = new JPanel(new BorderLayout(4, 4));
        detalle.setBorder(BorderFactory.createTitledBorder("Cuenta"));
        tDetalle.getColumnModel().getColumn(0).setMaxWidth(45);
        tDetalle.getColumnModel().getColumn(1).setMaxWidth(50);
        Ui.alinearDerecha(tDetalle, 1, 4, 5);
        detalle.add(new JScrollPane(tDetalle), BorderLayout.CENTER);
        lblTotales.setFont(lblTotales.getFont().deriveFont(Font.BOLD, 13f));
        detalle.add(lblTotales, BorderLayout.SOUTH);

        btnEnviar = Ui.boton("Enviar a cocina/barra", this::enviar);
        btnAnularPlato = Ui.boton("Anular plato", this::anularPlato);
        btnCerrar = Ui.boton("Cerrar cuenta", this::cerrar);
        btnReabrir = Ui.boton("Reabrir", () -> hacer(() -> RestauranteService.reabrir(idComanda)));
        btnCargar = Ui.boton("Cargar a habitación", this::cargarAHabitacion);
        btnCobrar = Ui.boton("Cobrar y facturar", this::cobrar);
        btnAnularComanda = Ui.boton("Anular comanda", this::anularComanda);
        JPanel botones = new JPanel(new GridLayout(2, 4, 6, 6));
        for (JButton b : new JButton[]{btnEnviar, btnAnularPlato, btnCerrar, btnReabrir, btnCargar, btnCobrar, btnAnularComanda,
                Ui.boton("Ver pre-cuenta", this::preCuenta)}) botones.add(b);

        JPanel centro = new JPanel(new BorderLayout(6, 6));
        centro.add(selector, BorderLayout.NORTH);
        centro.add(detalle, BorderLayout.CENTER);
        add(centro, BorderLayout.CENTER);
        add(botones, BorderLayout.SOUTH);

        cmbCategoria.addActionListener(e -> cargarProductos());
        tProductos.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) agregar(); }
        });
        recargarCategorias();
        cargar(0);
    }

    // ------------------------------------------------------------------ datos
    public void recargarCategorias() {
        Object sel = cmbCategoria.getSelectedItem();
        int idSel = sel == null ? 0 : ((AccionesHotel.Opcion) sel).id();
        cmbCategoria.removeAllItems();
        for (Map<String, Object> f : Db.query("SELECT id_categoria, nombre FROM categorias_menu WHERE activa = 1 ORDER BY orden, nombre"))
            cmbCategoria.addItem(new AccionesHotel.Opcion(Db.ent(f.get("id_categoria")), Db.str(f.get("nombre"))));
        if (idSel > 0) AccionesHotel.seleccionar(cmbCategoria, idSel);
    }

    private void cargarProductos() {
        Object sel = cmbCategoria.getSelectedItem();
        if (sel == null) { mProductos.setRowCount(0); return; }
        Ui.llenar(mProductos, Db.query("SELECT id_producto, nombre, precio FROM productos_menu WHERE activo = 1 AND id_categoria = ? ORDER BY nombre",
                ((AccionesHotel.Opcion) sel).id()), "id_producto", "nombre", "precio");
    }

    public int getIdComanda() { return idComanda; }

    /** Muestra la comanda indicada (0 = ninguna). */
    public final void cargar(int id) {
        idComanda = id;
        if (id == 0) {
            estadoComanda = "";
            lblTitulo.setText("Seleccione una mesa o abra un pedido");
            mDetalle.setRowCount(0);
            lblTotales.setText(" ");
            actualizarBotones();
            return;
        }
        Map<String, Object> c = Db.uno("SELECT c.estado, c.tipo, c.propina, m.nombre AS mesa, c.num_personas FROM comandas c LEFT JOIN mesas m ON m.id_mesa = c.id_mesa WHERE c.id_comanda = ?", id);
        estadoComanda = Db.str(c.get("estado"));
        String donde = c.get("mesa") != null ? Db.str(c.get("mesa")) : (Db.str(c.get("tipo")).equals("LLEVAR") ? "Para llevar" : "Servicio a habitación");
        lblTitulo.setText(donde + "  -  Comanda #" + id + "  [" + estadoComanda + "]  " + c.get("num_personas") + " pers.");
        List<Map<String, Object>> filas = Db.query("SELECT id_detalle, cantidad, descripcion, notas, precio_unitario, "
                + "ROUND(cantidad*precio_unitario - descuento, 2) AS importe, estado, tratamiento_fiscal FROM comanda_detalle WHERE id_comanda = ? ORDER BY id_detalle", id);
        Ui.llenar(mDetalle, filas, "id_detalle", "cantidad", "descripcion", "notas", "precio_unitario", "importe", "estado");
        BigDecimal grav = BigDecimal.ZERO, exento = BigDecimal.ZERO;
        for (Map<String, Object> f : filas) {
            if ("ANULADO".equals(Db.str(f.get("estado")))) continue;
            if ("EXENTO".equals(Db.str(f.get("tratamiento_fiscal")))) exento = exento.add(Db.dec(f.get("importe")));
            else grav = grav.add(Db.dec(f.get("importe")));
        }
        BigDecimal isv = grav.multiply(Config.numero("isv_general", "15")).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        lblTotales.setText("<html>Subtotal " + Ui.lempiras(grav.add(exento)) + "   ISV " + Ui.lempiras(isv)
                + "   <font color='#1F4E79'>TOTAL " + Ui.lempiras(grav.add(exento).add(isv)) + "</font></html>");
        actualizarBotones();
    }

    private void actualizarBotones() {
        boolean hay = idComanda > 0;
        boolean abierta = hay && estadoComanda.equals("ABIERTA");
        boolean cerrada = hay && estadoComanda.equals("CERRADA");
        btnAgregar.setEnabled(abierta);
        btnEnviar.setEnabled(abierta);
        btnAnularPlato.setEnabled(abierta);
        btnCerrar.setEnabled(abierta);
        btnReabrir.setEnabled(cerrada);
        btnCargar.setEnabled(abierta || cerrada);
        btnCobrar.setEnabled(abierta || cerrada);
        btnAnularComanda.setEnabled(abierta || cerrada);
    }

    // ------------------------------------------------------------------ acciones
    private void hacer(Runnable r) {
        Ui.seguro(this, () -> { r.run(); cargar(idComanda); alCambiar.run(); });
    }

    private void agregar() {
        int fila = tProductos.getSelectedRow();
        if (fila < 0) { Ui.info(this, "Seleccione un producto del menú."); return; }
        int idProd = Integer.parseInt(tProductos.getValueAt(fila, 0).toString());
        BigDecimal cant = BigDecimal.valueOf(((Number) spCantidad.getValue()).intValue());
        hacer(() -> RestauranteService.agregarPlato(idComanda, idProd, cant, txtNotas.getText()));
        txtNotas.setText("");
        spCantidad.setValue(1);
    }

    private void enviar() {
        hacer(() -> {
            int n = RestauranteService.enviarACocina(idComanda);
            Ui.info(this, n + " plato(s) enviado(s) a cocina/barra.");
        });
    }

    private void anularPlato() {
        int id = Ui.idSeleccionado(tDetalle);
        if (id < 0) { Ui.info(this, "Seleccione el plato a anular en la cuenta."); return; }
        String motivo = JOptionPane.showInputDialog(this, "Motivo de la anulación:", "Anular plato", JOptionPane.QUESTION_MESSAGE);
        if (motivo == null) return;
        hacer(() -> RestauranteService.anularPlato(id, motivo));
    }

    private void cerrar() {
        BigDecimal sugerida = BigDecimal.ZERO;
        JTextField propina = new JTextField(10);
        BigDecimal pct = Config.numero("propina_sugerida", "10");
        Object sub = Db.scalar("SELECT subtotal FROM v_comandas_total WHERE id_comanda = ?", idComanda);
        if (sub != null) sugerida = Db.dec(sub).multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        propina.setText(sugerida.toPlainString());
        Campos.Formulario f = new Campos.Formulario().agregar("Propina (L), voluntaria:", propina)
                .nota("Sugerida " + pct.stripTrailingZeros().toPlainString() + "%. La propina no lleva ISV ni va en la factura.");
        Campos.dialogo(this, "Cerrar cuenta", f, () -> {
            RestauranteService.cerrarCuenta(idComanda, Ui.leerDinero(propina.getText(), "propina"));
            return true;
        });
        cargar(idComanda);
        alCambiar.run();
    }

    private void cargarAHabitacion() {
        List<Map<String, Object>> en = Db.query("SELECT e.id_estancia, CONCAT('Hab. ', h.numero, ' - ', hu.nombre) AS txt FROM estancias e "
                + "JOIN habitaciones h ON h.id_habitacion = e.id_habitacion JOIN huespedes hu ON hu.id_huesped = e.id_huesped "
                + "WHERE e.estado = 'ACTIVA' ORDER BY h.numero");
        if (en.isEmpty()) { Ui.info(this, "No hay huéspedes en casa."); return; }
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(en, "id_estancia", "txt", false);
        Campos.Formulario f = new Campos.Formulario().agregar("Habitación:", c)
                .nota("El consumo se suma a la cuenta del huésped y se factura en su check-out.");
        boolean ok = Campos.dialogo(this, "Cargar a habitación", f, () -> {
            RestauranteService.cargarAHabitacion(idComanda, AccionesHotel.idDe(c));
            return true;
        });
        if (ok) { Ui.info(this, "Cargado a la habitación."); cargar(0); alCambiar.run(); }
    }

    private void cobrar() {
        if (FacturarDialog.comanda(this, idComanda)) { cargar(0); alCambiar.run(); }
    }

    private void anularComanda() {
        String motivo = JOptionPane.showInputDialog(this, "Motivo de la anulación de TODA la comanda:", "Anular comanda", JOptionPane.QUESTION_MESSAGE);
        if (motivo == null) return;
        Ui.seguro(this, () -> { RestauranteService.anularComanda(idComanda, motivo); cargar(0); alCambiar.run(); });
    }

    private void preCuenta() {
        if (idComanda == 0) return;
        StringBuilder sb = new StringBuilder(lblTitulo.getText()).append("\n\n");
        for (int i = 0; i < mDetalle.getRowCount(); i++) {
            if ("ANULADO".equals(mDetalle.getValueAt(i, 6))) continue;
            sb.append(String.format("%-5s %-28s %12s%n", mDetalle.getValueAt(i, 1), mDetalle.getValueAt(i, 2), mDetalle.getValueAt(i, 5)));
        }
        sb.append("\n").append(lblTotales.getText().replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim());
        sb.append("\nPropina sugerida ").append(Config.numero("propina_sugerida", "10").stripTrailingZeros().toPlainString()).append("% (voluntaria)");
        JTextArea ta = new JTextArea(sb.toString(), 16, 46);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        ta.setEditable(false);
        JOptionPane.showMessageDialog(this, new JScrollPane(ta), "Pre-cuenta", JOptionPane.PLAIN_MESSAGE);
    }

    public int idPlatoSeleccionado() { return Ui.idSeleccionado(tDetalle); }
    public String usuario() { return Sesion.nombre; }
}
