package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.HotelService;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Agenda una reserva: elige huésped, fechas y una habitación realmente disponible en esas fechas. */
public final class NuevaReservaDialog {
    private NuevaReservaDialog() {}

    private record Hab(int id, int capacidad, BigDecimal precio, String texto) {
        @Override public String toString() { return texto; }
    }

    public static boolean mostrar(Component padre) { return mostrar(padre, null); }

    /** Si se indica una habitación, queda elegida de entrada (si está libre en las fechas). */
    public static boolean mostrar(Component padre, Integer habitacionPreferida) {
        int[] idH = {-1};
        JLabel lblHuesped = new JLabel("(sin elegir)");
        JButton btnH = Ui.boton("Elegir huésped...", () -> {
            int id = AccionesHotel.elegirHuesped(padre);
            if (id > 0) { idH[0] = id; lblHuesped.setText(AccionesHotel.nombreHuesped(id)); }
        });
        JSpinner llegada = Campos.fecha(LocalDate.now().plusDays(1));
        JSpinner salida = Campos.fecha(LocalDate.now().plusDays(2));
        JComboBox<AccionesHotel.Opcion> tipo = AccionesHotel.combo(
                Db.query("SELECT id_tipo, nombre FROM tipos_habitacion WHERE activo = 1 ORDER BY nombre"), "id_tipo", "nombre", true);
        JComboBox<Hab> hab = new JComboBox<>();
        JLabel lblPrecio = new JLabel(" ");
        JSpinner personas = Campos.entero(1, 1, 20);
        JTextField anticipo = new JTextField("0", 10);
        JComboBox<AccionesHotel.Opcion> credito = AccionesHotel.combo(
                Db.query("SELECT id_cliente_credito, nombre FROM clientes_credito WHERE activo = 1 ORDER BY nombre"), "id_cliente_credito", "nombre", true);
        JTextField obs = new JTextField(24);
        JCheckBox confirmada = new JCheckBox("Marcar como confirmada", true);

        Runnable buscar = () -> Ui.seguro(padre, () -> {
            LocalDate l = Campos.valor(llegada), s = Campos.valor(salida);
            if (!s.isAfter(l)) throw new IllegalArgumentException("La salida debe ser después de la llegada.");
            int t = AccionesHotel.idDe(tipo);
            List<Map<String, Object>> libres = HotelService.disponibles(l, s, t == 0 ? null : t, null);
            hab.removeAllItems();
            for (Map<String, Object> f : libres)
                hab.addItem(new Hab(Db.ent(f.get("id_habitacion")), Db.ent(f.get("capacidad")), Db.dec(f.get("precio_noche")),
                        "Hab. " + f.get("numero") + " - " + f.get("tipo") + " (" + f.get("piso") + ") cap. " + f.get("capacidad")
                        + " - " + Ui.lempiras(f.get("precio_noche")) + "/noche"));
            if (habitacionPreferida != null)
                for (int i = 0; i < hab.getItemCount(); i++) if (hab.getItemAt(i).id() == habitacionPreferida) { hab.setSelectedIndex(i); break; }
            lblPrecio.setText(libres.isEmpty() ? "No hay habitaciones disponibles en esas fechas." : libres.size() + " habitación(es) disponible(s).");
        });
        Runnable estimar = () -> {
            Hab h = (Hab) hab.getSelectedItem();
            if (h == null) return;
            long noches = Math.max(0, ChronoUnit.DAYS.between(Campos.valor(llegada), Campos.valor(salida)));
            BigDecimal recargo = HotelService.recargoPorPersonas(Campos.valorEntero(personas), h.capacidad(), noches);
            BigDecimal base = h.precio().multiply(BigDecimal.valueOf(noches));
            lblPrecio.setText(noches + " noche(s) x " + Ui.lempiras(h.precio()) + " = " + Ui.lempiras(base)
                    + (recargo.signum() > 0 ? "  + recargo por personas extra " + Ui.lempiras(recargo) : "") + "  (antes de impuestos)");
        };
        hab.addActionListener(e -> estimar.run());
        personas.addChangeListener(e -> estimar.run());

        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Huésped:", Ui.fila(btnH, lblHuesped))
         .agregar("Llegada:", llegada).agregar("Salida:", salida).agregar("Tipo de habitación:", tipo)
         .agregar("", Ui.boton("Buscar habitaciones disponibles", buscar))
         .agregar("Habitación:", hab).agregar("", lblPrecio)
         .agregar("Personas:", personas).agregar("Anticipo (L):", anticipo)
         .agregar("A cargo de empresa (crédito):", credito).agregar("Observaciones:", obs).agregar("", confirmada);
        buscar.run();
        return Campos.dialogo(padre, "Nueva reserva", f, () -> {
            if (idH[0] < 0) throw new IllegalArgumentException("Elija el huésped.");
            Hab h = (Hab) hab.getSelectedItem();
            if (h == null) throw new IllegalArgumentException("Busque y elija una habitación disponible.");
            int c = AccionesHotel.idDe(credito);
            HotelService.crearReserva(idH[0], h.id(), Campos.valor(llegada), Campos.valor(salida), Campos.valorEntero(personas),
                    Ui.leerDinero(anticipo.getText(), "anticipo"), c == 0 ? null : c, obs.getText(), confirmada.isSelected());
            return true;
        });
    }
}
