package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Control de acceso: qué pantallas ve cada rol y qué acciones del POS puede hacer sin autorización de un supervisor. */
public class PermisosPanel extends JPanel implements Actualizable {
    private static final String[] ROLES = {"SUPERVISOR", "RECEPCION", "AMA_LLAVES", "CAJERO", "MESERO", "COCINA", "BODEGA", "CONTADOR"};
    private final JComboBox<String> cmbRol = new JComboBox<>(ROLES);
    private final JPanel pantallas = new JPanel(), acciones = new JPanel();
    private final Map<String, JCheckBox> cajas = new LinkedHashMap<>();
    private final Map<String, JCheckBox> clavesPantalla = new LinkedHashMap<>();   // «abrir con clave» si la pantalla está bloqueada
    private final Map<String, JCheckBox> claves = new LinkedHashMap<>();   // «pedir clave de supervisor» si la acción está bloqueada
    private final JLabel estado = new JLabel(" ");

    public PermisosPanel() {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        add(new JLabel("<html><span style='font-size:20px'><b>Roles y permisos</b></span><br><span style='color:#64748B'>"
                + "Marque lo que cada rol puede ver y hacer. Lo que no marque queda bloqueado. En lo bloqueado, la casilla "
                + "«Pedir clave» decide: marcada, el sistema pide usuario y contraseña de un supervisor (o administrador) que sí tenga el permiso "
                + "(las pantallas aparecen con candado); sin marcar, queda negado del todo. Toda pantalla o función nueva aparece aquí sola. El rol ADMIN siempre puede todo.</span></html>"), BorderLayout.NORTH);

        pantallas.setLayout(new BoxLayout(pantallas, BoxLayout.Y_AXIS));
        acciones.setLayout(new BoxLayout(acciones, BoxLayout.Y_AXIS));
        Tema.Tarjeta izq = new Tema.Tarjeta(new BorderLayout(0, 6), null);
        izq.add(new JLabel("<html><b>Pantallas del menú</b> <span style='color:#64748B'>(sin marcar = oculta, o con candado si pide clave)</span></html>"), BorderLayout.NORTH);
        izq.add(new JScrollPane(pantallas), BorderLayout.CENTER);
        Tema.Tarjeta der = new Tema.Tarjeta(new BorderLayout(0, 6), null);
        der.add(new JLabel("<html><b>Acciones del punto de venta</b> <span style='color:#64748B'>(sin marcar = bloqueada)</span></html>"), BorderLayout.NORTH);
        der.add(new JScrollPane(acciones), BorderLayout.CENTER);
        JPanel centro = new JPanel(new GridLayout(1, 2, 14, 0));
        centro.setOpaque(false);
        centro.add(izq);
        centro.add(der);
        add(centro, BorderLayout.CENTER);

        for (Modulos.Modulo m : Modulos.todos()) {
            if (m.id().equals("inicio")) continue;
            JCheckBox c = new JCheckBox(m.titulo() + "  (" + m.grupo().toLowerCase() + ")");
            JCheckBox k = new JCheckBox("Pedir clave", false);
            k.setToolTipText("Si está bloqueada: marcada = aparece en el menú con candado y se abre con la clave de un supervisor; sin marcar = no aparece.");
            c.addActionListener(e -> k.setEnabled(!c.isSelected()));
            cajas.put(m.id(), c);
            clavesPantalla.put(m.id(), k);
            JPanel fila = new JPanel(new BorderLayout(8, 0));
            fila.setOpaque(false);
            fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            fila.add(c, BorderLayout.CENTER);
            fila.add(k, BorderLayout.EAST);
            pantallas.add(fila);
        }
        for (Map.Entry<String, String> a : Sesion.ACCIONES.entrySet()) {
            JCheckBox c = new JCheckBox(a.getValue());
            JCheckBox k = new JCheckBox("Pedir clave", true);
            k.setToolTipText("Si está bloqueada: marcada = un supervisor puede autorizarla con su clave; sin marcar = negada.");
            c.addActionListener(e -> k.setEnabled(!c.isSelected()));
            cajas.put(a.getKey(), c);
            claves.put(a.getKey(), k);
            JPanel fila = new JPanel(new BorderLayout(8, 0));
            fila.setOpaque(false);
            fila.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            fila.add(c, BorderLayout.CENTER);
            fila.add(k, BorderLayout.EAST);
            acciones.add(fila);
        }

        JPanel sur = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        sur.setOpaque(false);
        sur.add(new JLabel("Rol:"));
        sur.add(cmbRol);
        sur.add(Ui.botonPrimario("Guardar permisos del rol", this::guardar));
        sur.add(Ui.boton("Restablecer valores de fábrica", this::restablecer));
        sur.add(estado);
        add(sur, BorderLayout.SOUTH);
        cmbRol.addActionListener(e -> cargar());
    }

    @Override public void alMostrar() { cargar(); }

    private void cargar() {
        String rol = (String) cmbRol.getSelectedItem();
        Set<String> ef = Sesion.efectivos(rol);
        for (Map.Entry<String, JCheckBox> e : cajas.entrySet()) e.getValue().setSelected(ef.contains(e.getKey()));
        for (Map.Entry<String, JCheckBox> e : clavesPantalla.entrySet()) {
            e.getValue().setSelected(Sesion.pideClavePantalla(rol, e.getKey()));
            e.getValue().setEnabled(!cajas.get(e.getKey()).isSelected());
        }
        for (Map.Entry<String, JCheckBox> e : claves.entrySet()) {
            e.getValue().setSelected(Sesion.pideClave(rol, e.getKey()));
            e.getValue().setEnabled(!cajas.get(e.getKey()).isSelected());
        }
        boolean personalizado = Db.scalar("SELECT 1 FROM permisos_rol WHERE rol = ? LIMIT 1", rol) != null;
        estado.setText(personalizado ? "   Permisos personalizados" : "   Permisos de fábrica");
    }

    private void guardar() {
        String rol = (String) cmbRol.getSelectedItem();
        Ui.seguro(this, () -> {
            Db.transaccion(tx -> {
                tx.exec("DELETE FROM permisos_rol WHERE rol = ?", rol);
                tx.exec("INSERT INTO permisos_rol (rol, permiso) VALUES (?, 'inicio')", rol);
                for (Map.Entry<String, JCheckBox> e : cajas.entrySet())
                    if (e.getValue().isSelected()) tx.exec("INSERT INTO permisos_rol (rol, permiso) VALUES (?,?)", rol, e.getKey());
                tx.exec("DELETE FROM permisos_con_clave WHERE rol = ?", rol);
                for (Map.Entry<String, JCheckBox> e : clavesPantalla.entrySet())
                    if (!cajas.get(e.getKey()).isSelected() && e.getValue().isSelected())
                        tx.exec("INSERT INTO permisos_con_clave (rol, permiso) VALUES (?,?)", rol, e.getKey());
                tx.exec("DELETE FROM permisos_sin_clave WHERE rol = ?", rol);
                for (Map.Entry<String, JCheckBox> e : claves.entrySet())
                    if (!cajas.get(e.getKey()).isSelected() && !e.getValue().isSelected())
                        tx.exec("INSERT INTO permisos_sin_clave (rol, permiso) VALUES (?,?)", rol, e.getKey());
                return null;
            });
            Bitacora.registrar("PERMISOS", "rol", rol, "Permisos actualizados");
            Sesion.cargarPermisos();
            cargar();
            Ui.info(this, "Permisos guardados. Los usuarios con ese rol los verán al volver a iniciar sesión.");
        });
    }

    private void restablecer() {
        String rol = (String) cmbRol.getSelectedItem();
        if (!Ui.confirmar(this, "¿Volver los permisos de " + rol + " a los de fábrica?")) return;
        Ui.seguro(this, () -> { Db.exec("DELETE FROM permisos_rol WHERE rol = ?", rol); Db.exec("DELETE FROM permisos_sin_clave WHERE rol = ?", rol); Db.exec("DELETE FROM permisos_con_clave WHERE rol = ?", rol); Sesion.cargarPermisos(); cargar(); });
    }
}
