package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.HotelService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;

public class ReservasPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Huésped", "Hab.", "Tipo", "Llegada", "Salida", "Noches", "Pers.", "Anticipo", "Crédito", "Estado");
    private final JTable tabla = Ui.tabla(modelo);
    private final JComboBox<String> cmbEstado = new JComboBox<>(new String[]{"Vigentes (pendiente/confirmada/en casa)", "Todas", "PENDIENTE", "CONFIRMADA", "EN_CASA", "FINALIZADA", "CANCELADA", "NO_SHOW"});
    private final JSpinner desde = Campos.fecha(LocalDate.now().minusDays(7));
    private final JSpinner hasta = Campos.fecha(LocalDate.now().plusDays(60));

    public ReservasPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JPanel norte = new JPanel(new BorderLayout());
        JPanel cab = new JPanel(new BorderLayout());
        cab.setOpaque(false);
        cab.add(Ui.titulo("Reservas"), BorderLayout.WEST);
        JButton nueva = Ui.botonPrimario("+ Nueva reserva", () -> { if (NuevaReservaDialog.mostrar(this)) alMostrar(); });
        nueva.setMargin(new Insets(8, 18, 8, 18));
        cab.add(nueva, BorderLayout.EAST);
        norte.add(cab, BorderLayout.NORTH);
        norte.add(Ui.fila(new JLabel("Estado:"), cmbEstado, new JLabel("Llegada desde:"), desde, new JLabel("hasta:"), hasta,
                Ui.boton("Mostrar", this::alMostrar)), BorderLayout.CENTER);
        add(norte, BorderLayout.NORTH);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        tabla.getColumnModel().getColumn(0).setMaxWidth(55);
        Ui.alinearDerecha(tabla, 6, 7, 8);

        add(Ui.fila(
                Ui.boton("Confirmar", () -> accion(id -> HotelService.confirmarReserva(id))),
                Ui.boton("Cancelar reserva", this::cancelar),
                Ui.boton("Check-in", () -> { int id = Ui.idSeleccionado(tabla); if (id < 0) aviso(); else if (AccionesHotel.checkInReserva(this, id)) alMostrar(); }),
                Ui.boton("Marcar «no llegó»", () -> accion(id -> HotelService.marcarNoShow(id))),
                Ui.boton("Exportar a Excel (CSV)", () -> Exportar.csv(this, modelo, "reservas_" + LocalDate.now()))), BorderLayout.SOUTH);
    }

    private void aviso() { Ui.info(this, "Seleccione una reserva de la tabla."); }

    private void accion(java.util.function.IntConsumer a) {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { aviso(); return; }
        Ui.seguro(this, () -> { a.accept(id); alMostrar(); });
    }

    private void cancelar() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { aviso(); return; }
        String motivo = JOptionPane.showInputDialog(this, "Motivo de la cancelación:", "Cancelar reserva", JOptionPane.QUESTION_MESSAGE);
        if (motivo == null) return;
        Ui.seguro(this, () -> { HotelService.cancelarReserva(id, motivo); alMostrar(); });
    }

    @Override public void alMostrar() {
        String est = (String) cmbEstado.getSelectedItem();
        LocalDate d = Campos.valor(desde), h = Campos.valor(hasta);
        String filtro = est.startsWith("Vigentes") ? "AND r.estado IN ('PENDIENTE','CONFIRMADA','EN_CASA') "
                : est.equals("Todas") ? "" : "AND r.estado = '" + est.replaceAll("[^A-Z_]", "") + "' ";
        Ui.enSegundoPlano(this, () -> Db.query(
                "SELECT r.id_reserva, hu.nombre, ha.numero, t.nombre AS tipo, r.fecha_llegada, r.fecha_salida, r.noches, r.num_personas, "
              + "r.anticipo, cc.nombre AS credito, r.estado "
              + "FROM reservas r JOIN huespedes hu ON hu.id_huesped = r.id_huesped "
              + "JOIN habitaciones ha ON ha.id_habitacion = r.id_habitacion JOIN tipos_habitacion t ON t.id_tipo = ha.id_tipo "
              + "LEFT JOIN clientes_credito cc ON cc.id_cliente_credito = r.id_cliente_credito "
              + "WHERE r.fecha_llegada BETWEEN ? AND ? " + filtro + "ORDER BY r.fecha_llegada, ha.numero LIMIT 1000", d, h),
            filas -> Ui.llenar(modelo, filas, "id_reserva", "nombre", "numero", "tipo", "fecha_llegada", "fecha_salida", "noches", "num_personas", "anticipo", "credito", "estado"));
    }
}
