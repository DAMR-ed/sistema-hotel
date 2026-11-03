package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Autenticacion;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/** Altas, roles y contraseñas de los usuarios del sistema. */
public class UsuariosPanel extends JPanel implements Actualizable {
    private static final String[] ROLES = {"ADMIN", "SUPERVISOR", "RECEPCION", "AMA_LLAVES", "CAJERO", "MESERO", "COCINA", "BODEGA", "CONTADOR"};
    private final DefaultTableModel modelo = Ui.modelo("Id", "Usuario", "Nombre", "Rol", "Puntos de venta", "Activo", "Último acceso");
    private final JTable tabla = Ui.tabla(modelo);

    public UsuariosPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Usuarios"), BorderLayout.NORTH);
        tabla.getColumnModel().getColumn(0).setMaxWidth(45);
        add(new JScrollPane(tabla), BorderLayout.CENTER);
        add(Ui.fila(Ui.boton("Nuevo usuario", this::nuevo), Ui.boton("Editar rol y puntos de venta", this::editar),
                Ui.boton("Restablecer contraseña", this::restablecer), Ui.boton("Activar / desactivar", this::alternar)), BorderLayout.SOUTH);
    }

    @Override public void alMostrar() {
        Ui.llenar(modelo, Db.query("SELECT u.id_usuario, u.username, u.nombre, u.rol, COALESCE((SELECT GROUP_CONCAT(p.nombre ORDER BY p.nombre SEPARATOR ', ') FROM usuario_puntos up JOIN puntos_venta p ON p.id_punto = up.id_punto WHERE up.id_usuario = u.id_usuario), '') AS punto, "
                + "IF(u.activo=1,'Sí','No') AS act, u.ultimo_acceso FROM usuarios u ORDER BY u.username"),
                "id_usuario", "username", "nombre", "rol", "punto", "act", "ultimo_acceso");
    }

    private void nuevo() {
        JTextField u = new JTextField(14), n = new JTextField(22);
        JComboBox<String> rol = new JComboBox<>(ROLES);
        rol.setSelectedItem("RECEPCION");
        JPasswordField c1 = new JPasswordField(16), c2 = new JPasswordField(16);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Usuario:", u).agregar("Nombre completo:", n).agregar("Rol:", rol).agregar("Contraseña:", c1).agregar("Repetir contraseña:", c2)
         .nota("Mínimo " + Autenticacion.CLAVE_MINIMA + " caracteres con letras y números.");
        Campos.dialogo(this, "Nuevo usuario", f, () -> {
            if (u.getText().isBlank() || n.getText().isBlank()) throw new IllegalArgumentException("Complete usuario y nombre.");
            String a = new String(c1.getPassword());
            if (!a.equals(new String(c2.getPassword()))) throw new IllegalArgumentException("Las contraseñas no coinciden.");
            String err = Autenticacion.validarClave(a);
            if (err != null) throw new IllegalArgumentException(err);
            long id = Autenticacion.crearUsuario(u.getText(), n.getText(), a, (String) rol.getSelectedItem());
            Bitacora.registrar("CREAR", "usuario", id, u.getText().trim() + " " + rol.getSelectedItem());
            return true;
        });
        alMostrar();
    }

    private void editar() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { Ui.info(this, "Seleccione un usuario."); return; }
        var u = Db.uno("SELECT nombre, rol, id_punto_venta FROM usuarios WHERE id_usuario = ?", id);
        JTextField n = new JTextField(Db.str(u.get("nombre")), 22);
        java.util.Set<Integer> mios = new java.util.HashSet<>();
        for (var x : Db.query("SELECT id_punto FROM usuario_puntos WHERE id_usuario = ?", id)) mios.add(Db.ent(x.get("id_punto")));
        java.util.Map<Integer, JCheckBox> cajas = new java.util.LinkedHashMap<>();
        JPanel panelPuntos = new JPanel(new GridLayout(0, 1));
        for (var p : Db.query("SELECT id_punto, nombre FROM puntos_venta WHERE activo = 1 ORDER BY nombre")) {
            int idp = Db.ent(p.get("id_punto"));
            JCheckBox c = new JCheckBox(Db.str(p.get("nombre")), mios.contains(idp));
            cajas.put(idp, c);
            panelPuntos.add(c);
        }
        JComboBox<AccionesHotel.Opcion> predet = new JComboBox<>();
        for (var p : Db.query("SELECT id_punto, nombre FROM puntos_venta WHERE activo = 1 ORDER BY nombre")) predet.addItem(new AccionesHotel.Opcion(Db.ent(p.get("id_punto")), Db.str(p.get("nombre"))));
        if (u.get("id_punto_venta") != null) AccionesHotel.seleccionar(predet, Db.ent(u.get("id_punto_venta")));
        JComboBox<String> rol = new JComboBox<>(ROLES);
        rol.setSelectedItem(Db.str(u.get("rol")));
        Campos.Formulario f = new Campos.Formulario().agregar("Nombre:", n).agregar("Rol:", rol).agregar("Puede trabajar en:", panelPuntos).agregar("Predeterminado al entrar:", predet)
                .nota("Sin puntos marcados, usa el punto de la computadora. Con varios, el usuario cambia entre ellos desde la barra superior. El administrador puede usar cualquiera.");
        Campos.dialogo(this, "Editar usuario", f, () -> {
            if (id == Sesion.idUsuario && !"ADMIN".equals(rol.getSelectedItem())) throw new IllegalArgumentException("No puede quitarse a sí mismo el rol de administrador.");
            java.util.List<Integer> marcados = new java.util.ArrayList<>();
            for (var e : cajas.entrySet()) if (e.getValue().isSelected()) marcados.add(e.getKey());
            int pre = predet.getItemCount() == 0 ? 0 : AccionesHotel.idDe(predet);
            Integer predeterminado = marcados.isEmpty() ? null : marcados.contains(pre) ? Integer.valueOf(pre) : marcados.get(0);
            Db.transaccion(tx -> {
                tx.exec("UPDATE usuarios SET nombre = ?, rol = ?, id_punto_venta = ? WHERE id_usuario = ?", n.getText().trim(), rol.getSelectedItem(), predeterminado, id);
                tx.exec("DELETE FROM usuario_puntos WHERE id_usuario = ?", id);
                for (int idp : marcados) tx.exec("INSERT INTO usuario_puntos (id_usuario, id_punto) VALUES (?,?)", id, idp);
                return null;
            });
            Bitacora.registrar("EDITAR", "usuario", id, rol.getSelectedItem().toString());
            return true;
        });
        alMostrar();
    }

    private void restablecer() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { Ui.info(this, "Seleccione un usuario."); return; }
        JPasswordField c1 = new JPasswordField(16), c2 = new JPasswordField(16);
        Campos.Formulario f = new Campos.Formulario().agregar("Nueva contraseña:", c1).agregar("Repetir:", c2);
        Campos.dialogo(this, "Restablecer contraseña", f, () -> {
            String a = new String(c1.getPassword());
            if (!a.equals(new String(c2.getPassword()))) throw new IllegalArgumentException("Las contraseñas no coinciden.");
            String err = Autenticacion.validarClave(a);
            if (err != null) throw new IllegalArgumentException(err);
            Autenticacion.cambiarClave(id, a);
            return true;
        });
    }

    private void alternar() {
        int id = Ui.idSeleccionado(tabla);
        if (id < 0) { Ui.info(this, "Seleccione un usuario."); return; }
        if (id == Sesion.idUsuario) { Ui.info(this, "No puede desactivar su propio usuario."); return; }
        Db.exec("UPDATE usuarios SET activo = 1 - activo WHERE id_usuario = ?", id);
        Bitacora.registrar("ACTIVAR", "usuario", id, null);
        alMostrar();
    }
}
