package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.HotelService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Diálogos compartidos del hotel: elegir huésped, check-in, etc. */
public final class AccionesHotel {
    private AccionesHotel() {}

    /** Un elemento de lista desplegable con su id. */
    public record Opcion(int id, String texto) {
        @Override public String toString() { return texto; }
    }

    public static JComboBox<Opcion> combo(List<Map<String, Object>> filas, String claveId, String claveTexto, boolean conVacio) {
        JComboBox<Opcion> c = new JComboBox<>();
        if (conVacio) c.addItem(new Opcion(0, "(ninguno)"));
        for (Map<String, Object> f : filas) c.addItem(new Opcion(Db.ent(f.get(claveId)), Db.str(f.get(claveTexto))));
        return c;
    }

    public static int idDe(JComboBox<Opcion> c) {
        Opcion o = (Opcion) c.getSelectedItem();
        return o == null ? 0 : o.id();
    }

    public static void seleccionar(JComboBox<Opcion> c, int id) {
        for (int i = 0; i < c.getItemCount(); i++) if (c.getItemAt(i).id() == id) { c.setSelectedIndex(i); return; }
    }

    /** Busca un huésped existente o permite crear uno nuevo. Devuelve el id o -1. */
    public static int elegirHuesped(Component padre) {
        JTextField buscar = new JTextField(20);
        DefaultTableModel m = Ui.modelo("Id", "Documento", "Nombre", "Teléfono");
        JTable t = Ui.tabla(m);
        t.getColumnModel().getColumn(0).setMaxWidth(60);
        Runnable cargar = () -> {
            String q = "%" + buscar.getText().trim() + "%";
            Ui.llenar(m, Db.query("SELECT id_huesped, CONCAT(tipo_documento,' ',numero_documento) AS doc, nombre, telefono "
                    + "FROM huespedes WHERE activo = 1 AND (nombre LIKE ? OR numero_documento LIKE ? OR rtn LIKE ?) "
                    + "ORDER BY nombre LIMIT 200", q, q, q), "id_huesped", "doc", "nombre", "telefono");
        };
        cargar.run();
        buscar.addActionListener(e -> cargar.run());

        int[] elegido = {-1};
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));
        p.add(Ui.fila(new JLabel("Buscar:"), buscar, Ui.boton("Buscar", cargar),
                Ui.boton("Nuevo huésped...", () -> {
                    int id = HuespedDialog.mostrar(padre, null);
                    if (id > 0) { elegido[0] = id; Window w = SwingUtilities.getWindowAncestor(t); if (w != null) w.dispose(); }
                })), BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(560, 280));
        p.add(sp, BorderLayout.CENTER);
        boolean ok = Campos.dialogo(padre, "Elegir huésped", p, () -> {
            int id = Ui.idSeleccionado(t);
            if (id < 0) throw new IllegalArgumentException("Seleccione un huésped de la lista.");
            elegido[0] = id;
            return true;
        });
        return (ok || elegido[0] > 0) ? elegido[0] : -1;
    }

    public static String nombreHuesped(int id) {
        Object n = Db.scalar("SELECT nombre FROM huespedes WHERE id_huesped = ?", id);
        return n == null ? "" : n.toString();
    }

    /** Check-in de un huésped sin reserva. Devuelve true si se hizo. */
    public static boolean checkInDirecto(Component padre, Integer idHabitacionSugerida) {
        List<Map<String, Object>> libres = Db.query("SELECT id_habitacion, CONCAT('Hab. ', numero, ' - ', tipo, ' (', piso, ') L ', precio_noche) AS txt "
                + "FROM v_habitaciones WHERE activa = 1 AND estado = 'LIBRE' "
                + "AND NOT EXISTS (SELECT 1 FROM reservas r WHERE r.id_habitacion = v_habitaciones.id_habitacion AND r.estado IN ('PENDIENTE','CONFIRMADA') AND r.fecha_llegada = CURDATE()) "
                + "ORDER BY numero");
        if (libres.isEmpty()) { Ui.info(padre, "No hay habitaciones libres sin reserva para hoy."); return false; }
        JComboBox<Opcion> hab = combo(libres, "id_habitacion", "txt", false);
        if (idHabitacionSugerida != null) seleccionar(hab, idHabitacionSugerida);
        JLabel lblHuesped = new JLabel("(sin elegir)");
        int[] idH = {-1};
        JButton btnH = Ui.boton("Elegir huésped...", () -> {
            int id = elegirHuesped(padre);
            if (id > 0) { idH[0] = id; lblHuesped.setText(nombreHuesped(id)); }
        });
        JSpinner salida = Campos.fecha(LocalDate.now().plusDays(1));
        JSpinner personas = Campos.entero(1, 1, 20);
        JComboBox<Opcion> credito = combo(Db.query("SELECT id_cliente_credito, nombre FROM clientes_credito WHERE activo = 1 ORDER BY nombre"),
                "id_cliente_credito", "nombre", true);

        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Huésped:", Ui.fila(btnH, lblHuesped)).agregar("Habitación:", hab)
         .agregar("Fecha de salida:", salida).agregar("Personas:", personas)
         .agregar("Cargar a empresa (crédito):", credito)
         .nota("La llegada es hoy. El precio por noche queda fijo al hacer el check-in.");
        return Campos.dialogo(padre, "Check-in directo", f, () -> {
            if (idH[0] < 0) throw new IllegalArgumentException("Elija el huésped.");
            int c = idDe(credito);
            HotelService.checkInDirecto(idH[0], idDe(hab), Campos.valor(salida), Campos.valorEntero(personas), c == 0 ? null : c);
            return true;
        });
    }

    public static boolean checkInReserva(Component padre, int idReserva) {
        if (!Ui.confirmar(padre, "¿Hacer el check-in de esta reserva ahora?")) return false;
        try {
            HotelService.checkInReserva(idReserva);
            return true;
        } catch (RuntimeException e) {
            Ui.error(padre, e.getMessage());
            return false;
        }
    }
}
