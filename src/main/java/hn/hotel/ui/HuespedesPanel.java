package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class HuespedesPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Documento", "Nombre", "RTN", "Teléfono", "Procedencia", "Visitas");
    private final JTable tabla = Ui.tabla(modelo);
    private final JTextField buscar = new JTextField(22);

    public HuespedesPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Huéspedes"), BorderLayout.NORTH);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        tabla.getColumnModel().getColumn(0).setMaxWidth(60);
        buscar.addActionListener(e -> alMostrar());
        add(Ui.fila(new JLabel("Buscar (nombre, documento o RTN):"), buscar,
                Ui.boton("Buscar", this::alMostrar),
                Ui.boton("Nuevo", () -> { if (HuespedDialog.mostrar(this, null) > 0) alMostrar(); }),
                Ui.boton("Editar", this::editar)), BorderLayout.SOUTH);
        tabla.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) editar(); }
        });
    }

    private void editar() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { Ui.info(this, "Seleccione un huésped."); return; }
        if (HuespedDialog.mostrar(this, id) > 0) alMostrar();
    }

    @Override public void alMostrar() {
        String q = "%" + buscar.getText().trim() + "%";
        Ui.enSegundoPlano(this, () -> Db.query(
                "SELECT h.id_huesped, CONCAT(h.tipo_documento, ' ', h.numero_documento) AS doc, h.nombre, h.rtn, h.telefono, h.procedencia, "
              + "(SELECT COUNT(*) FROM estancias e WHERE e.id_huesped = h.id_huesped) AS visitas "
              + "FROM huespedes h WHERE h.activo = 1 AND (h.nombre LIKE ? OR h.numero_documento LIKE ? OR h.rtn LIKE ?) "
              + "ORDER BY h.nombre LIMIT 500", q, q, q),
            filas -> Ui.llenar(modelo, filas, "id_huesped", "doc", "nombre", "rtn", "telefono", "procedencia", "visitas"));
    }
}
