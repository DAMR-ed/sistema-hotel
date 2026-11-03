package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;
import hn.hotel.service.RestauranteService;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Salón del restaurante: mesas que se diseñan en pantalla y la cuenta de la mesa elegida. */
public class RestaurantePanel extends JPanel implements Actualizable {
    private final PlanoCanvas canvas = new PlanoCanvas();
    private final ComandaPanel comanda;
    private final JComboBox<AccionesHotel.Opcion> cmbArea = new JComboBox<>();
    private final JToggleButton btnDiseno = new JToggleButton("Modo diseño");
    private final JPanel barraDiseno = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    private boolean cargandoAreas;

    public RestaurantePanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        comanda = new ComandaPanel(this::recargarMesas);

        JPanel norte = new JPanel(new BorderLayout());
        norte.add(Ui.titulo("Restaurante"), BorderLayout.WEST);
        JPanel ctl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        ctl.add(Ui.boton("Para llevar", this::nuevoParaLlevar));
        ctl.add(Ui.boton("Pedidos sin mesa", this::pedidosSinMesa));
        ctl.add(new JLabel("Zona:"));
        ctl.add(cmbArea);
        if (Sesion.permite("pos.disenar_mesas")) ctl.add(btnDiseno);
        norte.add(ctl, BorderLayout.EAST);

        barraDiseno.add(Ui.boton("+ Mesa", () -> formularioMesa(null)));
        barraDiseno.add(Ui.boton("+ Varias mesas", this::variasMesas));
        barraDiseno.add(Ui.boton("Editar seleccionada", this::editarSeleccionada));
        barraDiseno.add(Ui.boton("Eliminar seleccionada", this::eliminarSeleccionada));
        barraDiseno.add(Ui.boton("+ Zona", this::nuevaArea));
        barraDiseno.add(Ui.boton("Editar zona (nombre y tamaño)", this::tamanoArea));
        barraDiseno.add(Ui.boton("Quitar zona", this::quitarArea));
        barraDiseno.setVisible(false);

        JPanel top = new JPanel(new BorderLayout());
        top.add(norte, BorderLayout.NORTH);
        top.add(barraDiseno, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        JPanel izq = new JPanel(new BorderLayout());
        izq.add(canvas, BorderLayout.CENTER);
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, izq, comanda);
        sp.setResizeWeight(0.52);
        add(sp, BorderLayout.CENTER);

        cmbArea.addActionListener(e -> { if (!cargandoAreas) recargarMesas(); });
        btnDiseno.addActionListener(e -> {
            canvas.setEditable(btnDiseno.isSelected());
            barraDiseno.setVisible(btnDiseno.isSelected());
            recargarMesas();
            revalidate();
        });
        canvas.alMover = (it, pos) -> {
            try { Db.exec("UPDATE mesas SET pos_x = ?, pos_y = ? WHERE id_mesa = ?", pos[0], pos[1], it.id); return true; }
            catch (RuntimeException e) { Ui.error(this, e.getMessage()); return false; }
        };
        canvas.alClic = this::clicEnMesa;
        canvas.alClicDerecho = this::menuMesa;
        canvas.alDobleClic = it -> { if (canvas.isEditable()) formularioMesa(it.id); else clicEnMesa(it); };
    }

    // --------------------------------------------------------------- datos
    @Override public void alMostrar() {
        comanda.recargarCategorias();
        cargandoAreas = true;
        Object sel = cmbArea.getSelectedItem();
        int idSel = sel == null ? 0 : ((AccionesHotel.Opcion) sel).id();
        cmbArea.removeAllItems();
        for (Map<String, Object> f : Db.query("SELECT id_area, nombre FROM areas_restaurante WHERE activa = 1 ORDER BY id_area"))
            cmbArea.addItem(new AccionesHotel.Opcion(Db.ent(f.get("id_area")), Db.str(f.get("nombre"))));
        if (idSel > 0) AccionesHotel.seleccionar(cmbArea, idSel);
        cargandoAreas = false;
        recargarMesas();
    }

    private int areaActual() {
        Object o = cmbArea.getSelectedItem();
        return o == null ? 0 : ((AccionesHotel.Opcion) o).id();
    }

    private void recargarMesas() {
        int area = areaActual();
        if (area == 0) { canvas.setItems(List.of()); return; }
        boolean diseno = canvas.isEditable();
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> new Object[]{
                tx.uno("SELECT columnas, filas FROM areas_restaurante WHERE id_area = ?", area),
                tx.query("SELECT m.id_mesa, m.nombre, m.capacidad, m.forma, m.pos_x, m.pos_y, m.ancho, m.alto, m.estado, "
                       + "(SELECT t.subtotal FROM comandas c JOIN v_comandas_total t ON t.id_comanda = c.id_comanda "
                       + "  WHERE c.id_mesa = m.id_mesa AND c.estado IN ('ABIERTA','CERRADA') LIMIT 1) AS cuenta "
                       + "FROM mesas m WHERE m.id_area = ?", area)}),
            r -> {
                @SuppressWarnings("unchecked") Map<String, Object> a = (Map<String, Object>) r[0];
                @SuppressWarnings("unchecked") List<Map<String, Object>> ms = (List<Map<String, Object>>) r[1];
                canvas.setCuadricula(Db.ent(a.get("columnas")), Db.ent(a.get("filas")));
                List<PlanoCanvas.Item> items = new ArrayList<>();
                for (Map<String, Object> m : ms) {
                    PlanoCanvas.Item it = new PlanoCanvas.Item(Db.ent(m.get("id_mesa")));
                    it.texto = Db.str(m.get("nombre"));
                    it.x = Db.ent(m.get("pos_x")); it.y = Db.ent(m.get("pos_y"));
                    it.ancho = Db.ent(m.get("ancho")); it.alto = Db.ent(m.get("alto"));
                    it.redondo = "REDONDA".equals(Db.str(m.get("forma")));
                    it.color = Ui.colorEstado(Db.str(m.get("estado")));
                    it.subtexto = diseno ? Db.ent(m.get("capacidad")) + " pers." : (m.get("cuenta") != null ? Ui.lempiras(m.get("cuenta")) : Db.ent(m.get("capacidad")) + " pers.");
                    items.add(it);
                }
                canvas.setItems(items);
            });
    }

    // --------------------------------------------------------------- operación
    private void clicEnMesa(PlanoCanvas.Item it) {
        if (canvas.isEditable()) return;
        Ui.seguro(this, () -> {
            Map<String, Object> m = Db.uno("SELECT estado, capacidad FROM mesas WHERE id_mesa = ?", it.id);
            if ("INACTIVA".equals(Db.str(m.get("estado")))) { Ui.info(this, "La mesa está inactiva."); return; }
            Object abierta = Db.scalar("SELECT id_comanda FROM comandas WHERE id_mesa = ? AND estado IN ('ABIERTA','CERRADA') LIMIT 1", it.id);
            int personas = 1;
            if (abierta == null) {
                JSpinner sp = Campos.entero(Math.min(2, Db.ent(m.get("capacidad"))), 1, 50);
                Campos.Formulario f = new Campos.Formulario().agregar("Personas en la mesa:", sp);
                if (!Campos.dialogo(this, "Abrir " + it.texto, f, () -> true)) return;
                personas = Campos.valorEntero(sp);
            }
            comanda.cargar(RestauranteService.abrirComanda("SALON", it.id, personas));
            recargarMesas();
        });
    }

    private void menuMesa(PlanoCanvas.Item it) {
        JPopupMenu menu = new JPopupMenu();
        Map<String, Object> m = Db.uno("SELECT estado FROM mesas WHERE id_mesa = ?", it.id);
        String estado = Db.str(m.get("estado"));
        if (!canvas.isEditable()) {
            menu.add(item("Abrir / ver cuenta", () -> clicEnMesa(it)));
            if (estado.equals("LIBRE")) menu.add(item("Marcar RESERVADA", () -> estadoMesa(it.id, "RESERVADA")));
            if (estado.equals("RESERVADA")) menu.add(item("Marcar LIBRE", () -> estadoMesa(it.id, "LIBRE")));
        } else {
            menu.add(item("Editar...", () -> formularioMesa(it.id)));
            menu.add(item("Eliminar", this::eliminarSeleccionada));
            menu.add(item(estado.equals("INACTIVA") ? "Activar" : "Desactivar (fuera de servicio)",
                    () -> estadoMesa(it.id, estado.equals("INACTIVA") ? "LIBRE" : "INACTIVA")));
        }
        Point p = canvas.getMousePosition();
        menu.show(canvas, p == null ? 20 : p.x, p == null ? 20 : p.y);
    }

    private JMenuItem item(String texto, Runnable r) {
        return new JMenuItem(new AbstractAction(texto) {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { r.run(); }
        });
    }

    private void estadoMesa(int id, String estado) {
        Ui.seguro(this, () -> {
            if (Db.scalar("SELECT 1 FROM comandas WHERE id_mesa = ? AND estado IN ('ABIERTA','CERRADA')", id) != null)
                throw new IllegalArgumentException("La mesa tiene una cuenta abierta.");
            Db.exec("UPDATE mesas SET estado = ? WHERE id_mesa = ?", estado, id);
            recargarMesas();
        });
    }

    private void nuevoParaLlevar() {
        Ui.seguro(this, () -> { comanda.cargar(RestauranteService.abrirComanda("LLEVAR", null, 1)); });
    }

    private void pedidosSinMesa() {
        List<Map<String, Object>> filas = Db.query("SELECT c.id_comanda, CONCAT('#', c.id_comanda, ' ', IF(c.tipo='LLEVAR','Para llevar','Habitación'), ' - ', "
                + "DATE_FORMAT(c.abierta_en,'%H:%i'), ' - ', IF(c.estado='CERRADA','cerrada','abierta')) AS txt FROM comandas c "
                + "WHERE c.id_mesa IS NULL AND c.estado IN ('ABIERTA','CERRADA') ORDER BY c.id_comanda");
        if (filas.isEmpty()) { Ui.info(this, "No hay pedidos abiertos sin mesa."); return; }
        JComboBox<AccionesHotel.Opcion> c = AccionesHotel.combo(filas, "id_comanda", "txt", false);
        Campos.Formulario f = new Campos.Formulario().agregar("Pedido:", c);
        if (Campos.dialogo(this, "Pedidos sin mesa", f, () -> true)) comanda.cargar(AccionesHotel.idDe(c));
    }

    // --------------------------------------------------------------- diseño
    private void editarSeleccionada() {
        PlanoCanvas.Item it = canvas.getSeleccionado();
        if (it == null) { Ui.info(this, "Haga clic en una mesa del plano para seleccionarla."); return; }
        formularioMesa(it.id);
    }

    private void formularioMesa(Integer id) {
        int area = areaActual();
        if (area == 0) { Ui.info(this, "Primero cree un área."); return; }
        Map<String, Object> m = id == null ? null : Db.uno("SELECT * FROM mesas WHERE id_mesa = ?", id);
        Object sig = Db.scalar("SELECT COUNT(*) + 1 FROM mesas WHERE id_area = ?", area);
        JTextField nombre = new JTextField(m == null ? "Mesa " + sig : Db.str(m.get("nombre")), 14);
        JSpinner cap = Campos.entero(m == null ? 4 : Db.ent(m.get("capacidad")), 1, 50);
        JComboBox<String> forma = new JComboBox<>(new String[]{"CUADRADA", "REDONDA", "RECTANGULAR"});
        if (m != null) forma.setSelectedItem(Db.str(m.get("forma")));
        JSpinner ancho = Campos.entero(m == null ? 2 : Db.ent(m.get("ancho")), 1, 10);
        JSpinner alto = Campos.entero(m == null ? 2 : Db.ent(m.get("alto")), 1, 10);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("Capacidad:", cap).agregar("Forma:", forma)
         .agregar("Ancho (celdas):", ancho).agregar("Alto (celdas):", alto);
        Campos.dialogo(this, id == null ? "Nueva mesa" : "Editar mesa", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre de la mesa.");
            int w = Campos.valorEntero(ancho), h = Campos.valorEntero(alto);
            if (id == null) {
                int[] lugar = canvas.buscarLugar(w, h);
                if (lugar == null) throw new IllegalArgumentException("No hay espacio en esta área. Agrande el área o use una mesa más pequeña.");
                long nid = Db.insert("INSERT INTO mesas (id_area, nombre, capacidad, forma, pos_x, pos_y, ancho, alto) VALUES (?,?,?,?,?,?,?,?)",
                        area, nombre.getText().trim(), Campos.valorEntero(cap), forma.getSelectedItem(), lugar[0], lugar[1], w, h);
                Bitacora.registrar("CREAR", "mesa", nid, nombre.getText().trim());
            } else {
                int x = Db.ent(m.get("pos_x")), y = Db.ent(m.get("pos_y"));
                if (x + w > canvas.getColumnas() || y + h > canvas.getFilas()
                        || Db.scalar("SELECT 1 FROM mesas WHERE id_area = ? AND id_mesa <> ? AND ? < pos_x + ancho AND ? + ? > pos_x AND ? < pos_y + alto AND ? + ? > pos_y LIMIT 1",
                                area, id, x, x, w, y, y, h) != null)
                    throw new IllegalArgumentException("Con ese tamaño choca con otra mesa o se sale del plano.");
                Db.exec("UPDATE mesas SET nombre=?, capacidad=?, forma=?, ancho=?, alto=? WHERE id_mesa=?",
                        nombre.getText().trim(), Campos.valorEntero(cap), forma.getSelectedItem(), w, h, id);
                Bitacora.registrar("EDITAR", "mesa", id, nombre.getText().trim());
            }
            return true;
        });
        recargarMesas();
    }

    private void variasMesas() {
        int area = areaActual();
        if (area == 0) { Ui.info(this, "Primero cree un área."); return; }
        JSpinner cantidad = Campos.entero(6, 1, 40), cap = Campos.entero(4, 1, 50);
        JTextField prefijo = new JTextField("Mesa ", 10);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Cantidad de mesas:", cantidad).agregar("Capacidad de cada una:", cap).agregar("Prefijo del nombre:", prefijo)
         .nota("Se colocan solas en los espacios libres; luego las arrastra a su lugar.");
        Campos.dialogo(this, "Agregar varias mesas", f, () -> {
            int n = Campos.valorEntero(cantidad);
            int creadas = 0;
            Object ultimo = Db.scalar("SELECT COUNT(*) FROM mesas WHERE id_area = ?", area);
            int numero = Db.ent(ultimo);
            for (int i = 0; i < n; i++) {
                int[] lugar = canvas.buscarLugar(2, 2);
                if (lugar == null) break;
                numero++;
                Db.insert("INSERT INTO mesas (id_area, nombre, capacidad, pos_x, pos_y) VALUES (?,?,?,?,?)",
                        area, prefijo.getText() + numero, Campos.valorEntero(cap), lugar[0], lugar[1]);
                creadas++;
                canvas.setItems(itemsActuales());   // para que la siguiente búsqueda vea el espacio ya usado
            }
            if (creadas < n) Ui.info(this, "Solo cupieron " + creadas + " mesa(s). Agrande el área para más.");
            return true;
        });
        recargarMesas();
    }

    private List<PlanoCanvas.Item> itemsActuales() {
        // el lienzo no expone su lista; reconstruir desde la base garantiza datos correctos
        List<PlanoCanvas.Item> r = new ArrayList<>();
        for (Map<String, Object> m : Db.query("SELECT id_mesa, pos_x, pos_y, ancho, alto FROM mesas WHERE id_area = ?", areaActual())) {
            PlanoCanvas.Item it = new PlanoCanvas.Item(Db.ent(m.get("id_mesa")));
            it.x = Db.ent(m.get("pos_x")); it.y = Db.ent(m.get("pos_y")); it.ancho = Db.ent(m.get("ancho")); it.alto = Db.ent(m.get("alto"));
            r.add(it);
        }
        return r;
    }

    private void eliminarSeleccionada() {
        PlanoCanvas.Item it = canvas.getSeleccionado();
        if (it == null) { Ui.info(this, "Haga clic en una mesa del plano para seleccionarla."); return; }
        Ui.seguro(this, () -> {
            if (Db.scalar("SELECT 1 FROM comandas WHERE id_mesa = ? AND estado IN ('ABIERTA','CERRADA')", it.id) != null)
                throw new IllegalArgumentException("La mesa tiene una cuenta abierta.");
            boolean historial = Db.scalar("SELECT 1 FROM comandas WHERE id_mesa = ? LIMIT 1", it.id) != null;
            if (historial) {
                if (!Ui.confirmar(this, "La mesa " + it.texto + " tiene historial de ventas; se marcará INACTIVA en lugar de borrarse. ¿Continuar?")) return;
                Db.exec("UPDATE mesas SET estado = 'INACTIVA' WHERE id_mesa = ?", it.id);
            } else {
                if (!Ui.confirmar(this, "¿Eliminar la mesa " + it.texto + "?")) return;
                Db.exec("DELETE FROM mesas WHERE id_mesa = ?", it.id);
            }
            Bitacora.registrar(historial ? "DESACTIVAR" : "ELIMINAR", "mesa", it.id, it.texto);
            recargarMesas();
        });
    }

    private void nuevaArea() {
        JTextField nombre = new JTextField(14);
        JSpinner cols = Campos.entero(20, 4, 60), filas = Campos.entero(12, 4, 40);
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre de la zona:", nombre).agregar("Columnas:", cols).agregar("Filas:", filas);
        Campos.dialogo(this, "Nueva zona (salón, terraza, bar, piscina...)", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre.");
            Db.insert("INSERT INTO areas_restaurante (nombre, columnas, filas) VALUES (?,?,?)", nombre.getText().trim(), Campos.valorEntero(cols), Campos.valorEntero(filas));
            return true;
        });
        alMostrar();
    }

    private void tamanoArea() {
        int area = areaActual();
        if (area == 0) return;
        Map<String, Object> a = Db.uno("SELECT nombre, columnas, filas FROM areas_restaurante WHERE id_area = ?", area);
        JTextField nombre = new JTextField(Db.str(a.get("nombre")), 14);
        JSpinner cols = Campos.entero(Db.ent(a.get("columnas")), 4, 60), filas = Campos.entero(Db.ent(a.get("filas")), 4, 40);
        JComboBox<AccionesHotel.Opcion> lista = AccionesHotel.combo(Db.query("SELECT id_lista, nombre FROM listas_precios WHERE activa = 1 ORDER BY nombre"), "id_lista", "nombre", true);
        lista.removeItemAt(0);
        lista.insertItemAt(new AccionesHotel.Opcion(0, "General"), 0);
        lista.setSelectedIndex(0);
        Object actual = Db.scalar("SELECT id_lista FROM areas_restaurante WHERE id_area = ?", area);
        if (actual != null) AccionesHotel.seleccionar(lista, Db.ent(actual));
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre:", nombre).agregar("Columnas:", cols).agregar("Filas:", filas)
                .agregar("Lista de precios de la zona:", lista);
        Campos.dialogo(this, "Editar zona", f, () -> {
            int idLista = AccionesHotel.idDe(lista);
            Db.exec("UPDATE areas_restaurante SET id_lista = ? WHERE id_area = ?", idLista == 0 ? null : idLista, area);
            int c = Campos.valorEntero(cols), r = Campos.valorEntero(filas);
            if (Db.scalar("SELECT 1 FROM mesas WHERE id_area = ? AND (pos_x + ancho > ? OR pos_y + alto > ?) LIMIT 1", area, c, r) != null)
                throw new IllegalArgumentException("Hay mesas que quedarían fuera. Muévalas antes de reducir el área.");
            Db.exec("UPDATE areas_restaurante SET nombre = ?, columnas = ?, filas = ? WHERE id_area = ?", nombre.getText().trim(), c, r, area);
            return true;
        });
        alMostrar();
    }

    private void quitarArea() {
        int area = areaActual();
        if (area == 0) return;
        if (Db.scalar("SELECT COUNT(*) FROM areas_restaurante WHERE activa = 1") instanceof Number n && n.intValue() <= 1) {
            Ui.error(this, "Debe quedar al menos una zona."); return;
        }
        if (Db.scalar("SELECT 1 FROM mesas m JOIN comandas c ON c.id_mesa = m.id_mesa WHERE m.id_area = ? AND c.estado IN ('ABIERTA','CERRADA') LIMIT 1", area) != null) {
            Ui.error(this, "Hay mesas de esta zona con cuenta abierta. Cóbrelas primero."); return;
        }
        if (!Ui.confirmar(this, "¿Quitar la zona seleccionada? Sus mesas dejarán de mostrarse (el historial se conserva).")) return;
        Ui.seguro(this, () -> { Db.exec("UPDATE areas_restaurante SET activa = 0 WHERE id_area = ?", area); alMostrar(); });
    }
}
