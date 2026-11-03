package hn.hotel.ui;

import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;
import hn.hotel.service.Impresion;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

/**
 * Estaciones de comanda: cocina, barra, postres, parrilla... Cada estación tiene su pantalla (Cocina) y su impresora.
 * Cada categoría del menú envía sus platos a una estación.
 */
public class EstacionesPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mEst = Ui.modelo("Id", "Estación", "Impresora", "Pantalla", "Imprime sola", "Activa", "Categorías que envía");
    private final JTable tEst = Ui.tabla(mEst);
    private final DefaultTableModel mCat = Ui.modelo("Id", "Categoría del menú", "Envía la comanda a");
    private final JTable tCat = Ui.tabla(mCat);

    public EstacionesPanel() {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        add(new JLabel("<html><span style='font-size:20px'><b>Estaciones de comanda</b></span><br>"
                + "<span style='color:#64748B'>Una estación es un lugar donde se prepara algo (cocina, barra, postres...). Cada una tiene su pantalla en «Pantalla de comandas» "
                + "y, si quiere, su impresora. Abajo se elige a qué estación va cada categoría del menú.</span></html>"), BorderLayout.NORTH);

        tEst.getColumnModel().getColumn(0).setMaxWidth(45);
        Tema.Tarjeta arriba = new Tema.Tarjeta(new BorderLayout(0, 8), null);
        arriba.add(new JScrollPane(tEst), BorderLayout.CENTER);
        JPanel b1 = Ui.fila(Ui.botonPrimario("+ Nueva estación", () -> formulario(null)),
                Ui.boton("Editar", () -> { int id = Ui.idSeleccionado(tEst); if (id > 0) formulario(id); else Ui.info(this, "Seleccione una estación."); }),
                Ui.boton("Activar / desactivar", this::alternar),
                Ui.boton("Probar impresora", this::probar));
        arriba.add(b1, BorderLayout.SOUTH);

        tCat.getColumnModel().getColumn(0).setMaxWidth(45);
        Tema.Tarjeta abajo = new Tema.Tarjeta(new BorderLayout(0, 8), null);
        abajo.add(new JScrollPane(tCat), BorderLayout.CENTER);
        abajo.add(Ui.fila(Ui.botonPrimario("Cambiar estación de la categoría...", this::cambiarCategoria),
                new JLabel("  Un plato puede tener su propia estación en «Menú y recetas».")), BorderLayout.SOUTH);

        JPanel centro = new JPanel(new GridLayout(2, 1, 0, 12));
        centro.setOpaque(false);
        centro.add(arriba);
        centro.add(abajo);
        add(centro, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        int sel = Ui.idSeleccionado(tEst);
        Ui.llenar(mEst, Db.query("SELECT e.id_estacion, e.nombre, COALESCE(NULLIF(e.impresora,''), 'Sin impresora') AS imp, IF(e.usa_pantalla=1,'Sí','No') AS pan, "
                + "IF(e.imprime_auto=1,'Sí','No') AS aut, IF(e.activa=1,'Sí','No') AS act, "
                + "COALESCE((SELECT GROUP_CONCAT(c.nombre ORDER BY c.orden SEPARATOR ', ') FROM categorias_menu c WHERE c.id_estacion = e.id_estacion), '-') AS cats "
                + "FROM estaciones e ORDER BY e.orden, e.nombre"), "id_estacion", "nombre", "imp", "pan", "aut", "act", "cats");
        Ui.llenar(mCat, Db.query("SELECT c.id_categoria, c.nombre, COALESCE(e.nombre, 'Se sirve directo (sin comanda)') AS est "
                + "FROM categorias_menu c LEFT JOIN estaciones e ON e.id_estacion = c.id_estacion WHERE c.activa = 1 ORDER BY c.orden, c.nombre"),
                "id_categoria", "nombre", "est");
    }

    private void formulario(Integer id) {
        Map<String, Object> e = id == null ? null : Db.uno("SELECT * FROM estaciones WHERE id_estacion = ?", id);
        JTextField nombre = new JTextField(e == null ? "" : Db.str(e.get("nombre")), 16);
        JTextField imp = new JTextField(e == null || e.get("impresora") == null ? "" : Db.str(e.get("impresora")), 18);
        imp.putClientProperty("JTextField.placeholderText", "192.168.1.50:9100 (vacío = sin impresora)");
        JCheckBox pantalla = new JCheckBox("Mostrar en la pantalla de comandas", e == null || Db.ent(e.get("usa_pantalla")) == 1);
        JCheckBox auto = new JCheckBox("Imprimir sola al enviar la comanda", e == null || Db.ent(e.get("imprime_auto")) == 1);
        JSpinner orden = Campos.entero(e == null ? 5 : Db.ent(e.get("orden")), 1, 99);
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre:", nombre).agregar("Impresora (IP:puerto):", imp)
                .agregar("", pantalla).agregar("", auto).agregar("Orden:", orden)
                .nota("La impresora térmica de red usa normalmente el puerto 9100. Si no escribe nada, la comanda solo sale en pantalla.");
        Campos.dialogo(this, id == null ? "Nueva estación" : "Editar estación", f, () -> {
            String n = nombre.getText().trim().toUpperCase();
            if (n.isEmpty()) throw new IllegalArgumentException("Escriba el nombre de la estación.");
            String ip = imp.getText().trim();
            if (!ip.isEmpty() && !ip.matches("[A-Za-z0-9.\\-]+(:\\d{2,5})?"))
                throw new IllegalArgumentException("Escriba la impresora como 192.168.1.50:9100 o déjela vacía.");
            if (!pantalla.isSelected() && ip.isEmpty())
                throw new IllegalArgumentException("La estación necesita pantalla o impresora; si no, nadie vería la comanda.");
            Object ipv = ip.isEmpty() ? null : ip;
            if (id == null) {
                long nid = Db.insert("INSERT INTO estaciones (nombre, impresora, usa_pantalla, imprime_auto, orden) VALUES (?,?,?,?,?)",
                        n, ipv, pantalla.isSelected() ? 1 : 0, auto.isSelected() ? 1 : 0, Campos.valorEntero(orden));
                Bitacora.registrar("CREAR", "estacion", nid, n);
            } else {
                Db.exec("UPDATE estaciones SET nombre=?, impresora=?, usa_pantalla=?, imprime_auto=?, orden=? WHERE id_estacion=?",
                        n, ipv, pantalla.isSelected() ? 1 : 0, auto.isSelected() ? 1 : 0, Campos.valorEntero(orden), id);
                Db.exec("UPDATE tickets_cocina SET destino = ? WHERE id_estacion = ?", n, id);
                Bitacora.registrar("EDITAR", "estacion", id, n);
            }
            return true;
        });
        alMostrar();
    }

    private void alternar() {
        int id = Ui.idSeleccionado(tEst);
        if (id < 0) { Ui.info(this, "Seleccione una estación."); return; }
        Ui.seguro(this, () -> {
            Map<String, Object> e = Db.uno("SELECT activa FROM estaciones WHERE id_estacion = ?", id);
            if (Db.ent(e.get("activa")) == 1) {
                if (Db.scalar("SELECT 1 FROM tickets_cocina WHERE id_estacion = ? AND estado IN ('NUEVO','PREPARANDO','LISTO') LIMIT 1", id) != null)
                    throw new IllegalArgumentException("Esa estación tiene comandas activas. Termínelas primero.");
                if (Db.scalar("SELECT 1 FROM categorias_menu WHERE id_estacion = ? AND activa = 1 LIMIT 1", id) != null)
                    throw new IllegalArgumentException("Hay categorías que envían a esta estación. Cámbielas primero de estación.");
            }
            Db.exec("UPDATE estaciones SET activa = 1 - activa WHERE id_estacion = ?", id);
            alMostrar();
        });
    }

    private void probar() {
        int id = Ui.idSeleccionado(tEst);
        if (id < 0) { Ui.info(this, "Seleccione una estación."); return; }
        Map<String, Object> e = Db.uno("SELECT nombre, impresora FROM estaciones WHERE id_estacion = ?", id);
        if (e.get("impresora") == null || Db.str(e.get("impresora")).isBlank()) { Ui.info(this, "Esta estación no tiene impresora; sus comandas salen solo en pantalla."); return; }
        String ip = Db.str(e.get("impresora"));
        Ui.enSegundoPlano(this, () -> {
            try { Impresion.enviar(ip, Impresion.escpos("PRUEBA DE IMPRESION\n" + e.get("nombre") + "\nSi lee esto, la impresora funciona.\n", "PRUEBA " + e.get("nombre"))); return null; }
            catch (RuntimeException ex) { return ex.getMessage(); }
        }, err -> { if (err == null) Ui.info(this, "Se envió la página de prueba a " + ip + "."); else Ui.error(this, err); });
    }

    private void cambiarCategoria() {
        int cat = Ui.idSeleccionado(tCat);
        if (cat < 0) { Ui.info(this, "Seleccione una categoría."); return; }
        JComboBox<AccionesHotel.Opcion> est = AccionesHotel.combo(Db.query("SELECT id_estacion, nombre FROM estaciones WHERE activa = 1 ORDER BY orden, nombre"), "id_estacion", "nombre", true);
        Object actual = Db.scalar("SELECT id_estacion FROM categorias_menu WHERE id_categoria = ?", cat);
        if (actual != null) AccionesHotel.seleccionar(est, Db.ent(actual));
        Campos.Formulario f = new Campos.Formulario().agregar("Estación:", est).nota("«(ninguno)» = los platos de esta categoría se sirven directo, sin comanda.");
        Campos.dialogo(this, "Estación de la categoría", f, () -> {
            int e = AccionesHotel.idDe(est);
            Db.exec("UPDATE categorias_menu SET id_estacion = ? WHERE id_categoria = ?", e > 0 ? e : null, cat);
            return true;
        });
        alMostrar();
    }
}
