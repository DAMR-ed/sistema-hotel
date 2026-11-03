package hn.hotel.ui;

import hn.hotel.db.Db;

import javax.swing.*;
import java.awt.*;

/** Lista de zonas con botón «+» para crear una zona nueva sin salir de la ficha (como en Odoo). */
public final class ZonaCampo extends JPanel {
    private final JComboBox<AccionesHotel.Opcion> combo = new JComboBox<>();

    public ZonaCampo(Integer idInicial) {
        super(new BorderLayout(6, 0));
        recargar(idInicial == null ? 0 : idInicial);
        JButton nueva = Ui.boton("+ Nueva zona", this::crear);
        nueva.setToolTipText("Crea la zona en este momento si todavía no existe");
        add(combo, BorderLayout.CENTER);
        add(nueva, BorderLayout.EAST);
    }

    private void recargar(int seleccionar) {
        combo.removeAllItems();
        combo.addItem(new AccionesHotel.Opcion(0, "(sin zona)"));
        for (var z : Db.query("SELECT id_zona, nombre FROM zonas WHERE activo = 1 ORDER BY nombre"))
            combo.addItem(new AccionesHotel.Opcion(Db.ent(z.get("id_zona")), Db.str(z.get("nombre"))));
        AccionesHotel.seleccionar(combo, seleccionar);
    }

    private void crear() {
        String n = JOptionPane.showInputDialog(this, "Nombre de la nueva zona:", "Nueva zona", JOptionPane.PLAIN_MESSAGE);
        if (n == null || n.isBlank()) return;
        n = n.trim();
        if (n.length() > 60) { Ui.error(this, "El nombre de la zona es muy largo (máximo 60 letras)."); return; }
        try {
            Db.exec("INSERT INTO zonas (nombre) VALUES (?) ON DUPLICATE KEY UPDATE activo = 1", n);
            Object id = Db.scalar("SELECT id_zona FROM zonas WHERE nombre = ?", n);
            recargar(id == null ? 0 : Db.ent(id));
        } catch (RuntimeException e) { Ui.error(this, e.getMessage()); }
    }

    /** Id de la zona elegida, o null si no hay. */
    public Integer id() {
        int id = AccionesHotel.idDe(combo);
        return id == 0 ? null : id;
    }
}
