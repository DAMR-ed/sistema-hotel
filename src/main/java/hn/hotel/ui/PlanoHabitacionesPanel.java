package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;
import hn.hotel.service.HotelService;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plano del hotel. Modo operación: ver el estado de cada habitación y actuar sobre ella.
 * Modo diseño (solo ADMIN): crear habitaciones, arrastrarlas, cambiar tamaño y tipo.
 */
public class PlanoHabitacionesPanel extends JPanel implements Actualizable {
    private final PlanoCanvas canvas = new PlanoCanvas();
    private final JComboBox<AccionesHotel.Opcion> cmbPiso = new JComboBox<>();
    private final JToggleButton btnDiseno = new JToggleButton("Modo diseño");
    private final JPanel barraDiseno = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    private final JLabel lblInfo = new JLabel(" ");
    private boolean cargandoPisos;

    public PlanoHabitacionesPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JPanel norte = new JPanel(new BorderLayout());
        norte.add(Ui.titulo("Habitaciones"), BorderLayout.WEST);
        JPanel controles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 2));
        controles.add(new JLabel("Piso:"));
        controles.add(cmbPiso);
        controles.add(Ui.boton("Actualizar", this::recargar));
        if (Sesion.esAdmin()) controles.add(btnDiseno);
        norte.add(controles, BorderLayout.EAST);

        barraDiseno.add(Ui.boton("+ Habitación", this::nuevaHabitacion));
        barraDiseno.add(Ui.boton("Editar seleccionada", this::editarSeleccionada));
        barraDiseno.add(Ui.boton("Eliminar seleccionada", this::eliminarSeleccionada));
        barraDiseno.add(Ui.boton("Tipos de habitación", () -> { TiposHabitacionDialog.mostrar(this); recargar(); }));
        barraDiseno.add(Ui.boton("+ Piso", this::nuevoPiso));
        barraDiseno.add(Ui.boton("Tamaño del piso", this::tamanoPiso));
        barraDiseno.setVisible(false);

        JPanel top = new JPanel(new BorderLayout());
        top.add(norte, BorderLayout.NORTH);
        top.add(barraDiseno, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);

        JPanel sur = new JPanel(new BorderLayout());
        sur.add(leyenda(), BorderLayout.WEST);
        sur.add(lblInfo, BorderLayout.CENTER);
        add(sur, BorderLayout.SOUTH);

        cmbPiso.addActionListener(e -> { if (!cargandoPisos) recargar(); });
        btnDiseno.addActionListener(e -> {
            canvas.setEditable(btnDiseno.isSelected());
            barraDiseno.setVisible(btnDiseno.isSelected());
            lblInfo.setText(btnDiseno.isSelected()
                    ? "  Diseño: arrastre para mover; doble clic para editar. El color es el del tipo."
                    : " ");
            recargar();
            revalidate();
        });

        canvas.alMover = (it, pos) -> {
            try {
                Db.exec("UPDATE habitaciones SET pos_x = ?, pos_y = ? WHERE id_habitacion = ?", pos[0], pos[1], it.id);
                return true;
            } catch (RuntimeException e) { Ui.error(this, e.getMessage()); return false; }
        };
        canvas.alClic = this::mostrarMenu;
        canvas.alClicDerecho = this::mostrarMenu;
        canvas.alDobleClic = it -> { if (canvas.isEditable()) editar(it.id); else mostrarDetalle(it.id); };
    }

    private JPanel leyenda() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        String[][] e = {{"LIBRE", "Libre"}, {"OCUPADA", "Ocupada"}, {"RESERVADA", "Reservada hoy"}, {"LIMPIEZA", "Limpieza"}, {"REPARACION", "Reparación"}};
        for (String[] x : e) {
            JLabel c = new JLabel("   ");
            c.setOpaque(true);
            c.setBackground(Ui.colorEstado(x[0]));
            p.add(c);
            p.add(new JLabel(x[1]));
        }
        return p;
    }

    // ------------------------------------------------------------ datos
    @Override public void alMostrar() {
        cargandoPisos = true;
        Object sel = cmbPiso.getSelectedItem();
        int idSel = sel == null ? 0 : ((AccionesHotel.Opcion) sel).id();
        cmbPiso.removeAllItems();
        for (Map<String, Object> f : Db.query("SELECT id_piso, nombre FROM pisos WHERE activo = 1 ORDER BY orden, id_piso"))
            cmbPiso.addItem(new AccionesHotel.Opcion(Db.ent(f.get("id_piso")), Db.str(f.get("nombre"))));
        if (idSel > 0) AccionesHotel.seleccionar(cmbPiso, idSel);
        cargandoPisos = false;
        recargar();
    }

    private int pisoActual() {
        Object o = cmbPiso.getSelectedItem();
        return o == null ? 0 : ((AccionesHotel.Opcion) o).id();
    }

    private void recargar() {
        int piso = pisoActual();
        if (piso == 0) { canvas.setItems(List.of()); return; }
        boolean diseno = canvas.isEditable();
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> {
            Map<String, Object> p = tx.uno("SELECT columnas, filas FROM pisos WHERE id_piso = ?", piso);
            List<Map<String, Object>> hs = tx.query(
                "SELECT v.id_habitacion, v.numero, v.tipo, v.color, v.pos_x, v.pos_y, v.ancho, v.alto, "
              + "IF(v.estado = 'LIBRE' AND EXISTS (SELECT 1 FROM reservas r WHERE r.id_habitacion = v.id_habitacion "
              + "   AND r.estado IN ('PENDIENTE','CONFIRMADA') AND r.fecha_llegada <= CURDATE() AND r.fecha_salida > CURDATE()), 'RESERVADA', v.estado) AS estado_visual, "
              + "(SELECT hu.nombre FROM estancias e JOIN huespedes hu ON hu.id_huesped = e.id_huesped "
              + "  WHERE e.id_habitacion = v.id_habitacion AND e.estado = 'ACTIVA' LIMIT 1) AS huesped "
              + "FROM v_habitaciones v WHERE v.id_piso = ? AND v.activa = 1", piso);
            return new Object[]{p, hs};
        }), r -> {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) r[0];
            @SuppressWarnings("unchecked") List<Map<String, Object>> hs = (List<Map<String, Object>>) r[1];
            canvas.setCuadricula(Db.ent(p.get("columnas")), Db.ent(p.get("filas")));
            List<PlanoCanvas.Item> items = new ArrayList<>();
            for (Map<String, Object> h : hs) {
                PlanoCanvas.Item it = new PlanoCanvas.Item(Db.ent(h.get("id_habitacion")));
                it.texto = Db.str(h.get("numero"));
                it.x = Db.ent(h.get("pos_x")); it.y = Db.ent(h.get("pos_y"));
                it.ancho = Db.ent(h.get("ancho")); it.alto = Db.ent(h.get("alto"));
                String estado = Db.str(h.get("estado_visual"));
                if (diseno) {
                    it.color = Ui.colorHex(Db.str(h.get("color")), Color.GRAY);
                    it.subtexto = Db.str(h.get("tipo"));
                } else {
                    it.color = Ui.colorEstado(estado);
                    it.subtexto = estado.equals("OCUPADA") && h.get("huesped") != null ? Db.str(h.get("huesped")) : Db.str(h.get("tipo"));
                }
                items.add(it);
            }
            canvas.setItems(items);
        });
    }

    // ------------------------------------------------------------ operación
    private void mostrarMenu(PlanoCanvas.Item it) {
        if (canvas.isEditable()) return;
        JPopupMenu menu = new JPopupMenu();
        Map<String, Object> h = Db.uno("SELECT estado, numero FROM habitaciones WHERE id_habitacion = ?", it.id);
        String estado = Db.str(h.get("estado"));
        menu.add(new JMenuItem(new AbstractAction("Ver detalle") {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { mostrarDetalle(it.id); }
        }));
        if (Sesion.puede("reservas") && !estado.equals("REPARACION")) {
            menu.add(new JMenuItem(new AbstractAction("Reservar esta habitación...") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (NuevaReservaDialog.mostrar(PlanoHabitacionesPanel.this, it.id)) recargar();
                }
            }));
        }
        if (Sesion.puede("estancias") && (estado.equals("LIBRE"))) {
            menu.add(new JMenuItem(new AbstractAction("Check-in directo...") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (AccionesHotel.checkInDirecto(PlanoHabitacionesPanel.this, it.id)) recargar();
                }
            }));
            Object reservaHoy = Db.scalar("SELECT id_reserva FROM reservas WHERE id_habitacion = ? AND estado IN ('PENDIENTE','CONFIRMADA') "
                    + "AND fecha_llegada <= CURDATE() AND fecha_salida > CURDATE() ORDER BY fecha_llegada LIMIT 1", it.id);
            if (reservaHoy != null) menu.add(new JMenuItem(new AbstractAction("Check-in de la reserva de hoy") {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (AccionesHotel.checkInReserva(PlanoHabitacionesPanel.this, Db.ent(reservaHoy))) recargar();
                }
            }));
        }
        if (!estado.equals("OCUPADA")) {
            menu.addSeparator();
            for (String nuevo : new String[]{"LIBRE", "LIMPIEZA", "REPARACION"}) {
                if (nuevo.equals(estado)) continue;
                String etiqueta = switch (nuevo) {
                    case "LIBRE" -> "Marcar como LIBRE (lista)";
                    case "LIMPIEZA" -> "Marcar en LIMPIEZA";
                    default -> "Marcar en REPARACIÓN";
                };
                menu.add(new JMenuItem(new AbstractAction(etiqueta) {
                    @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                        Ui.seguro(PlanoHabitacionesPanel.this, () -> { HotelService.cambiarEstadoHabitacion(it.id, nuevo); recargar(); });
                    }
                }));
            }
        }
        Point p = canvas.getMousePosition();
        menu.show(canvas, p == null ? 20 : p.x, p == null ? 20 : p.y);
    }

    private void mostrarDetalle(int idHabitacion) {
        Map<String, Object> h = Db.uno("SELECT numero, tipo, piso, capacidad, precio_noche, estado, descripcion FROM v_habitaciones WHERE id_habitacion = ?", idHabitacion);
        StringBuilder sb = new StringBuilder();
        sb.append("Habitación ").append(h.get("numero")).append(" - ").append(h.get("tipo")).append(" (").append(h.get("piso")).append(")\n");
        sb.append("Capacidad: ").append(h.get("capacidad")).append(" personas\n");
        sb.append("Precio por noche: ").append(Ui.lempiras(h.get("precio_noche"))).append("\n");
        sb.append("Estado: ").append(h.get("estado")).append("\n");
        if (h.get("descripcion") != null) sb.append("Notas: ").append(h.get("descripcion")).append("\n");
        Map<String, Object> e = Db.uno("SELECT hu.nombre, es.checkin_real, es.salida_prevista FROM estancias es "
                + "JOIN huespedes hu ON hu.id_huesped = es.id_huesped WHERE es.id_habitacion = ? AND es.estado = 'ACTIVA'", idHabitacion);
        if (e != null) sb.append("\nHuésped actual: ").append(e.get("nombre")).append("\nIngresó: ").append(Ui.texto(Ui.fechaHora(e.get("checkin_real"))))
                .append("\nSalida prevista: ").append(Ui.texto(Ui.fecha(e.get("salida_prevista")))).append("\n");
        List<Map<String, Object>> prox = Db.query("SELECT hu.nombre, r.fecha_llegada, r.fecha_salida, r.estado FROM reservas r "
                + "JOIN huespedes hu ON hu.id_huesped = r.id_huesped WHERE r.id_habitacion = ? AND r.estado IN ('PENDIENTE','CONFIRMADA') "
                + "AND r.fecha_salida >= CURDATE() ORDER BY r.fecha_llegada LIMIT 5", idHabitacion);
        if (!prox.isEmpty()) {
            sb.append("\nPróximas reservas:\n");
            for (Map<String, Object> r : prox)
                sb.append(" - ").append(Ui.texto(Ui.fecha(r.get("fecha_llegada")))).append(" al ").append(Ui.texto(Ui.fecha(r.get("fecha_salida"))))
                  .append("  ").append(r.get("nombre")).append(" (").append(r.get("estado")).append(")\n");
        }
        Ui.info(this, sb.toString());
    }

    // ------------------------------------------------------------ diseño
    private void nuevaHabitacion() {
        int piso = pisoActual();
        if (piso == 0) { Ui.info(this, "Primero cree un piso."); return; }
        List<Map<String, Object>> tipos = Db.query("SELECT id_tipo, nombre FROM tipos_habitacion WHERE activo = 1 ORDER BY nombre");
        if (tipos.isEmpty()) { Ui.info(this, "Primero cree al menos un tipo de habitación (botón «Tipos de habitación»)."); return; }
        Object siguiente = Db.scalar("SELECT COALESCE(MAX(numero), 100) + 1 FROM habitaciones");
        formularioHabitacion(null, piso, tipos, siguiente == null ? "101" : siguiente.toString());
    }

    private void editarSeleccionada() {
        PlanoCanvas.Item it = canvas.getSeleccionado();
        if (it == null) { Ui.info(this, "Haga clic en una habitación del plano para seleccionarla."); return; }
        editar(it.id);
    }

    private void editar(int idHabitacion) {
        List<Map<String, Object>> tipos = Db.query("SELECT id_tipo, nombre FROM tipos_habitacion WHERE activo = 1 ORDER BY nombre");
        formularioHabitacion(idHabitacion, pisoActual(), tipos, null);
    }

    private void formularioHabitacion(Integer id, int piso, List<Map<String, Object>> tipos, String numeroSugerido) {
        Map<String, Object> h = id == null ? null : Db.uno("SELECT * FROM habitaciones WHERE id_habitacion = ?", id);
        JTextField numero = new JTextField(h == null ? numeroSugerido : Db.str(h.get("numero")), 6);
        JComboBox<AccionesHotel.Opcion> tipo = AccionesHotel.combo(tipos, "id_tipo", "nombre", false);
        JSpinner ancho = Campos.entero(h == null ? 2 : Db.ent(h.get("ancho")), 1, 8);
        JSpinner alto = Campos.entero(h == null ? 2 : Db.ent(h.get("alto")), 1, 8);
        JTextField especial = new JTextField(h == null || h.get("precio_especial") == null ? "" : Db.dec(h.get("precio_especial")).toPlainString(), 10);
        JTextField desc = new JTextField(h == null ? "" : Db.str(h.get("descripcion")), 24);
        if (h != null) AccionesHotel.seleccionar(tipo, Db.ent(h.get("id_tipo")));

        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Número de habitación:", numero).agregar("Tipo:", tipo)
         .agregar("Ancho (celdas):", ancho).agregar("Alto (celdas):", alto)
         .agregar("Precio especial (vacío = el del tipo):", especial).agregar("Notas:", desc);
        Campos.dialogo(this, id == null ? "Nueva habitación" : "Editar habitación", f, () -> {
            int num;
            try { num = Integer.parseInt(numero.getText().trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException("El número de habitación debe ser un número."); }
            if (num < 1 || num > 65000) throw new IllegalArgumentException("Número de habitación fuera de rango.");
            BigDecimal pe = especial.getText().isBlank() ? null : Ui.leerDinero(especial.getText(), "precio especial");
            if (pe != null && pe.signum() < 0) throw new IllegalArgumentException("El precio no puede ser negativo.");
            int w = Campos.valorEntero(ancho), hh = Campos.valorEntero(alto);
            String d = desc.getText().isBlank() ? null : desc.getText().trim();
            if (id == null) {
                PlanoCanvas.Item prueba = new PlanoCanvas.Item(0); prueba.ancho = w; prueba.alto = hh;
                int[] lugar = canvas.buscarLugar(w, hh);
                if (lugar == null) throw new IllegalArgumentException("No hay espacio en este piso. Agrande el piso («Tamaño del piso») o use una habitación más pequeña.");
                long nid = Db.insert("INSERT INTO habitaciones (numero, id_tipo, id_piso, pos_x, pos_y, ancho, alto, precio_especial, descripcion) VALUES (?,?,?,?,?,?,?,?,?)",
                        num, AccionesHotel.idDe(tipo), piso, lugar[0], lugar[1], w, hh, pe, d);
                Bitacora.registrar("CREAR", "habitacion", nid, "Hab " + num);
            } else {
                PlanoCanvas.Item it = null;
                int x = Db.ent(h.get("pos_x")), y = Db.ent(h.get("pos_y"));
                PlanoCanvas.Item prueba = new PlanoCanvas.Item(id); prueba.ancho = w; prueba.alto = hh;
                // ¿cabe con el nuevo tamaño? (se ignora a sí misma porque el lienzo la excluye por identidad; se revisa contra los demás)
                if (!cabeSinChocar(id, x, y, w, hh)) throw new IllegalArgumentException("Con ese tamaño choca con otra habitación o se sale del plano.");
                Db.exec("UPDATE habitaciones SET numero=?, id_tipo=?, ancho=?, alto=?, precio_especial=?, descripcion=? WHERE id_habitacion=?",
                        num, AccionesHotel.idDe(tipo), w, hh, pe, d, id);
                Bitacora.registrar("EDITAR", "habitacion", id, "Hab " + num);
            }
            return true;
        });
        recargar();
    }

    private boolean cabeSinChocar(int idHab, int x, int y, int w, int h) {
        if (x + w > canvas.getColumnas() || y + h > canvas.getFilas()) return false;
        Object choque = Db.scalar("SELECT 1 FROM habitaciones WHERE id_piso = (SELECT id_piso FROM habitaciones WHERE id_habitacion = ?) "
                + "AND activa = 1 AND id_habitacion <> ? AND ? < pos_x + ancho AND ? + ? > pos_x AND ? < pos_y + alto AND ? + ? > pos_y LIMIT 1",
                idHab, idHab, x, x, w, y, y, h);
        return choque == null;
    }

    private void eliminarSeleccionada() {
        PlanoCanvas.Item it = canvas.getSeleccionado();
        if (it == null) { Ui.info(this, "Haga clic en una habitación del plano para seleccionarla."); return; }
        Ui.seguro(this, () -> {
            Object usos = Db.scalar("SELECT (SELECT COUNT(*) FROM reservas WHERE id_habitacion = ?) + (SELECT COUNT(*) FROM estancias WHERE id_habitacion = ?)", it.id, it.id);
            boolean conHistorial = Db.ent(usos) > 0;
            if (Db.scalar("SELECT 1 FROM estancias WHERE id_habitacion = ? AND estado = 'ACTIVA'", it.id) != null)
                throw new IllegalArgumentException("La habitación tiene un huésped dentro.");
            String msg = conHistorial
                    ? "La habitación " + it.texto + " tiene historial de reservas; se desactivará (desaparece del plano pero se conserva el historial). ¿Continuar?"
                    : "¿Eliminar la habitación " + it.texto + " definitivamente?";
            if (!Ui.confirmar(this, msg)) return;
            if (conHistorial) Db.exec("UPDATE habitaciones SET activa = 0 WHERE id_habitacion = ?", it.id);
            else Db.exec("DELETE FROM habitaciones WHERE id_habitacion = ?", it.id);
            Bitacora.registrar(conHistorial ? "DESACTIVAR" : "ELIMINAR", "habitacion", it.id, "Hab " + it.texto);
            recargar();
        });
    }

    private void nuevoPiso() {
        JTextField nombre = new JTextField(14);
        JSpinner cols = Campos.entero(20, 4, 60), filas = Campos.entero(12, 4, 40);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre del piso:", nombre).agregar("Columnas:", cols).agregar("Filas:", filas);
        Campos.dialogo(this, "Nuevo piso", f, () -> {
            if (nombre.getText().isBlank()) throw new IllegalArgumentException("Escriba el nombre del piso.");
            Object orden = Db.scalar("SELECT COALESCE(MAX(orden), 0) + 1 FROM pisos");
            long id = Db.insert("INSERT INTO pisos (nombre, orden, columnas, filas) VALUES (?,?,?,?)", nombre.getText().trim(), orden,
                    Campos.valorEntero(cols), Campos.valorEntero(filas));
            Bitacora.registrar("CREAR", "piso", id, nombre.getText().trim());
            return true;
        });
        alMostrar();
    }

    private void tamanoPiso() {
        int piso = pisoActual();
        if (piso == 0) return;
        Map<String, Object> p = Db.uno("SELECT nombre, columnas, filas FROM pisos WHERE id_piso = ?", piso);
        JTextField nombre = new JTextField(Db.str(p.get("nombre")), 14);
        JSpinner cols = Campos.entero(Db.ent(p.get("columnas")), 4, 60), filas = Campos.entero(Db.ent(p.get("filas")), 4, 40);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Nombre:", nombre).agregar("Columnas:", cols).agregar("Filas:", filas);
        Campos.dialogo(this, "Tamaño del piso", f, () -> {
            int c = Campos.valorEntero(cols), r = Campos.valorEntero(filas);
            Object fuera = Db.scalar("SELECT 1 FROM habitaciones WHERE id_piso = ? AND activa = 1 AND (pos_x + ancho > ? OR pos_y + alto > ?) LIMIT 1", piso, c, r);
            if (fuera != null) throw new IllegalArgumentException("Hay habitaciones que quedarían fuera del plano. Muévalas antes de reducir el piso.");
            Db.exec("UPDATE pisos SET nombre = ?, columnas = ?, filas = ? WHERE id_piso = ?", nombre.getText().trim(), c, r, piso);
            return true;
        });
        alMostrar();
    }
}
