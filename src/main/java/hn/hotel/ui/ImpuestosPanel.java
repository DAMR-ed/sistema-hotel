package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.util.Map;

/** Catálogo de tasas de ISV que se pueden asignar a platos y servicios (0 %, 15 %, 18 %...). */
public class ImpuestosPanel extends JPanel implements Actualizable {
    private final DefaultTableModel m = Ui.modelo("Id", "Nombre", "Tasa %", "Activa", "Platos que la usan");
    private final JTable t = Ui.tabla(m);
    private final JLabel general = new JLabel(" ");

    public ImpuestosPanel() {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        add(new JLabel("<html><span style='font-size:20px'><b>Impuestos (ISV)</b></span><br><span style='color:#64748B'>"
                + "Cada plato y servicio escoge una de estas tasas. La tasa «general» (la del hospedaje y la de los productos normales) se cambia en Configuración (isv_general). "
                + "Cualquier otra tasa positiva se factura como ISV selectivo (columna de 18 %).</span></html>"), BorderLayout.NORTH);
        t.getColumnModel().getColumn(0).setMaxWidth(45);
        Ui.alinearDerecha(t, 2, 4);
        Tema.Tarjeta c = new Tema.Tarjeta(new BorderLayout(0, 8), null);
        c.add(new JScrollPane(t), BorderLayout.CENTER);
        c.add(Ui.fila(Ui.botonPrimario("+ Nueva tasa", () -> formulario(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(t); if (id > 0) formulario(id); else Ui.info(this, "Seleccione una tasa."); }), general), BorderLayout.SOUTH);
        add(c, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        Ui.llenar(m, Db.query("SELECT i.id_tasa, i.nombre, i.tasa, IF(i.activa=1,'Sí','No') AS act, "
                + "(SELECT COUNT(*) FROM productos_menu p WHERE p.tasa_isv = i.tasa) AS n FROM tasas_isv i ORDER BY i.tasa"), "id_tasa", "nombre", "tasa", "act", "n");
        general.setText("   ISV general vigente: " + Config.texto("isv_general", "15") + " %");
    }

    private void formulario(Integer id) {
        Map<String, Object> r = id == null ? null : Db.uno("SELECT * FROM tasas_isv WHERE id_tasa = ?", id);
        JTextField nombre = new JTextField(r == null ? "" : Db.str(r.get("nombre")), 18);
        JTextField tasa = new JTextField(r == null ? "" : Db.dec(r.get("tasa")).stripTrailingZeros().toPlainString(), 6);
        JCheckBox activa = new JCheckBox("Activa", r == null || Db.ent(r.get("activa")) == 1);
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre:", nombre).agregar("Tasa (%):", tasa).agregar("", activa)
                .nota("Ejemplos: Exento 0, ISV 15, ISV 18 (bebidas alcohólicas, tabaco...). Consulte a su contador qué tasa corresponde a cada producto.");
        Campos.dialogo(this, id == null ? "Nueva tasa" : "Editar tasa", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            BigDecimal v = Ui.leerDinero(tasa.getText(), "tasa");
            if (v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException("La tasa debe estar entre 0 y 100.");
            if (id == null) Db.insert("INSERT INTO tasas_isv (nombre, tasa, activa) VALUES (?,?,?)", nombre.getText().trim(), v, activa.isSelected() ? 1 : 0);
            else {
                if (Db.dec(r.get("tasa")).compareTo(v) != 0 && Db.scalar("SELECT 1 FROM productos_menu WHERE tasa_isv = ? LIMIT 1", r.get("tasa")) != null)
                    throw new IllegalArgumentException("Hay platos con esta tasa. Cree una tasa nueva y cambie esos platos, en lugar de modificar el porcentaje.");
                Db.exec("UPDATE tasas_isv SET nombre=?, tasa=?, activa=? WHERE id_tasa=?", nombre.getText().trim(), v, activa.isSelected() ? 1 : 0, id);
            }
            Bitacora.registrar("ISV", "tasas_isv", id == null ? 0 : id, nombre.getText().trim() + " " + v);
            return true;
        });
        alMostrar();
    }
}
