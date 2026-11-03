package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.HotelService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Huéspedes en casa: check-in, consumos a la cuenta, extensiones y salida. */
public class EstanciasPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Hab.", "Huésped", "Ingreso", "Salida prevista", "Noches", "Precio/noche", "Consumos por facturar", "Pers.");
    private final JTable tabla = Ui.tabla(modelo);

    public EstanciasPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Huéspedes en casa"), BorderLayout.NORTH);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        tabla.getColumnModel().getColumn(0).setMaxWidth(55);
        Ui.alinearDerecha(tabla, 5, 6, 7, 8);

        JPanel botones = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        botones.add(Ui.boton("Check-in de reserva...", this::checkInDeReserva));
        botones.add(Ui.boton("Check-in directo...", () -> { if (AccionesHotel.checkInDirecto(this, null)) alMostrar(); }));
        botones.add(Ui.boton("Agregar consumo", this::agregarConsumo));
        botones.add(Ui.boton("Ver cuenta", this::verCuenta));
        botones.add(Ui.boton("Extender / acortar", this::extender));
        botones.add(Ui.boton("Check-out y facturar", () -> {
            int id = seleccion();
            if (id > 0 && FacturarDialog.estancia(this, id)) alMostrar();
        }));
        botones.add(Ui.boton("Catálogo de servicios", () -> ServiciosDialog.mostrar(this)));
        add(botones, BorderLayout.SOUTH);
    }

    private int seleccion() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) Ui.info(this, "Seleccione una estadía de la tabla.");
        return id;
    }

    private void checkInDeReserva() {
        List<Map<String, Object>> filas = Db.query("SELECT r.id_reserva, CONCAT('Hab. ', ha.numero, ' - ', hu.nombre, ' (', DATE_FORMAT(r.fecha_llegada,'%d/%m'), ' al ', DATE_FORMAT(r.fecha_salida,'%d/%m'), ')') AS txt "
                + "FROM reservas r JOIN huespedes hu ON hu.id_huesped = r.id_huesped JOIN habitaciones ha ON ha.id_habitacion = r.id_habitacion "
                + "WHERE r.estado IN ('PENDIENTE','CONFIRMADA') AND r.fecha_llegada <= CURDATE() ORDER BY ha.numero");
        if (filas.isEmpty()) { Ui.info(this, "No hay reservas con llegada de hoy o anteriores pendientes de check-in."); return; }
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(filas, "id_reserva", "txt", false);
        Campos.Formulario f = new Campos.Formulario().agregar("Reserva:", c);
        Campos.dialogo(this, "Check-in de reserva", f, () -> {
            HotelService.checkInReserva(AccionesHotel.idDe(c));
            return true;
        });
        alMostrar();
    }

    private void agregarConsumo() {
        int id = seleccion();
        if (id < 0) return;
        List<Map<String, Object>> serv = Db.query("SELECT id_servicio, nombre, precio, tratamiento_fiscal FROM servicios WHERE activo = 1 ORDER BY nombre");
        JComboBox<String> cmb = new JComboBox<>();
        cmb.addItem("(otro - escribir descripción)");
        for (Map<String, Object> s : serv) cmb.addItem(s.get("nombre") + "  -  " + Ui.lempiras(s.get("precio")));
        JTextField desc = new JTextField(22), precio = new JTextField("0", 10), cant = new JTextField("1", 6);
        JComboBox<String> trat = new JComboBox<>(new String[]{"GRAVADO", "EXENTO"});
        cmb.addActionListener(e -> {
            int i = cmb.getSelectedIndex();
            if (i > 0) {
                Map<String, Object> s = serv.get(i - 1);
                desc.setText(Db.str(s.get("nombre")));
                precio.setText(Db.dec(s.get("precio")).toPlainString());
                trat.setSelectedItem(Db.str(s.get("tratamiento_fiscal")));
            }
        });
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Servicio:", cmb).agregar("Descripción:", desc).agregar("Cantidad:", cant)
         .agregar("Precio unitario (L):", precio).agregar("Impuesto:", trat);
        Campos.dialogo(this, "Agregar consumo a la cuenta", f, () -> {
            int i = cmb.getSelectedIndex();
            Integer idServ = i > 0 ? Db.ent(serv.get(i - 1).get("id_servicio")) : null;
            HotelService.agregarConsumo(id, idServ, desc.getText(), Ui.leerDinero(cant.getText(), "cantidad"),
                    Ui.leerDinero(precio.getText(), "precio"), (String) trat.getSelectedItem());
            return true;
        });
        alMostrar();
    }

    private void verCuenta() {
        int id = seleccion();
        if (id < 0) return;
        DefaultTableModel m = Ui.modelo("Fecha", "Descripción", "Cant.", "Precio", "Importe", "Impuesto", "Estado");
        Ui.llenar(m, Db.query("SELECT fecha, descripcion, cantidad, precio_unitario, ROUND(cantidad*precio_unitario,2) AS importe, tratamiento_fiscal, "
                + "IF(facturado=1,'Facturado','Pendiente') AS estado FROM consumos WHERE id_estancia = ? ORDER BY fecha", id),
                "fecha", "descripcion", "cantidad", "precio_unitario", "importe", "tratamiento_fiscal", "estado");
        JTable t = Ui.tabla(m);
        Ui.alinearDerecha(t, 2, 3, 4);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(700, 280));
        JOptionPane.showMessageDialog(this, sp, "Consumos de la estadía", JOptionPane.PLAIN_MESSAGE);
    }

    private void extender() {
        int id = seleccion();
        if (id < 0) return;
        Map<String, Object> e = Db.uno("SELECT salida_prevista FROM estancias WHERE id_estancia = ?", id);
        JSpinner nueva = Campos.fecha(Ui.fecha(e.get("salida_prevista")));
        Campos.Formulario f = new Campos.Formulario().agregar("Nueva fecha de salida:", nueva)
                .nota("Si otra reserva ocupa la habitación después, el sistema no lo permitirá.");
        Campos.dialogo(this, "Extender o acortar estadía", f, () -> {
            HotelService.extenderEstancia(id, Campos.valor(nueva));
            return true;
        });
        alMostrar();
    }

    /** Id de la estadía seleccionada (lo usa Facturación para el check-out). */
    public int estanciaSeleccionada() { return Ui.idSeleccionado(tabla); }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.query(
                "SELECT e.id_estancia, h.numero, hu.nombre, e.checkin_real, e.salida_prevista, "
              + "GREATEST(DATEDIFF(CURDATE(), DATE(e.checkin_real)), 1) AS noches, e.precio_noche, "
              + "COALESCE((SELECT SUM(c.cantidad*c.precio_unitario) FROM consumos c WHERE c.id_estancia = e.id_estancia AND c.facturado = 0), 0) AS consumos, e.num_personas "
              + "FROM estancias e JOIN habitaciones h ON h.id_habitacion = e.id_habitacion JOIN huespedes hu ON hu.id_huesped = e.id_huesped "
              + "WHERE e.estado = 'ACTIVA' ORDER BY h.numero"),
            filas -> Ui.llenar(modelo, filas, "id_estancia", "numero", "nombre", "checkin_real", "salida_prevista", "noches", "precio_noche", "consumos", "num_personas"));
    }
}
