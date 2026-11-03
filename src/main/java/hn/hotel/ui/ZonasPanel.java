package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/** Catálogo de zonas de clientes y proveedores. */
public class ZonasPanel extends JPanel implements Actualizable {
    private final DefaultTableModel modelo = Ui.modelo("Id", "Zona", "Clientes", "Proveedores", "Activa");
    private final JTable tabla = Ui.tabla(modelo);

    public ZonasPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Zonas"), BorderLayout.NORTH);
        tabla.getColumnModel().getColumn(0).setMaxWidth(50);
        Ui.alinearDerecha(tabla, 2, 3);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        add(Ui.fila(Ui.boton("Nueva zona", this::nueva), Ui.boton("Renombrar", this::renombrar), Ui.boton("Activar / desactivar", this::alternar),
                new JLabel("<html><i>Las zonas también se pueden crear al vuelo desde la ficha de un cliente o proveedor.</i></html>")), BorderLayout.SOUTH);
    }

    @Override public void alMostrar() {
        Ui.llenar(modelo, Db.query("SELECT z.id_zona, z.nombre, (SELECT COUNT(*) FROM clientes_credito c WHERE c.id_zona = z.id_zona) AS clientes, "
                + "(SELECT COUNT(*) FROM proveedores p WHERE p.id_zona = z.id_zona) AS proveedores, IF(z.activo = 1, 'Sí', 'No') AS act FROM zonas z ORDER BY z.nombre"),
                "id_zona", "nombre", "clientes", "proveedores", "act");
    }

    private void nueva() {
        String n = JOptionPane.showInputDialog(this, "Nombre de la zona:", "Nueva zona", JOptionPane.PLAIN_MESSAGE);
        if (n == null || n.isBlank()) return;
        Ui.seguro(this, () -> {
            Db.exec("INSERT INTO zonas (nombre) VALUES (?) ON DUPLICATE KEY UPDATE activo = 1", n.trim());
            Bitacora.registrar("CREAR", "zona", 0, n.trim());
            alMostrar();
        });
    }

    private void renombrar() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { Ui.info(this, "Seleccione una zona."); return; }
        String actual = modelo.getValueAt(tabla.convertRowIndexToModel(tabla.getSelectedRow()), 1).toString();
        String n = (String) JOptionPane.showInputDialog(this, "Nuevo nombre:", "Renombrar zona", JOptionPane.PLAIN_MESSAGE, null, null, actual);
        if (n == null || n.isBlank()) return;
        Ui.seguro(this, () -> { Db.exec("UPDATE zonas SET nombre = ? WHERE id_zona = ?", n.trim(), id); Bitacora.registrar("EDITAR", "zona", id, n.trim()); alMostrar(); });
    }

    private void alternar() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { Ui.info(this, "Seleccione una zona."); return; }
        Db.exec("UPDATE zonas SET activo = 1 - activo WHERE id_zona = ?", id);
        alMostrar();
    }
}
