package hn.hotel.app;

import hn.hotel.db.Db;
import hn.hotel.service.Autenticacion;
import hn.hotel.ui.LoginFrame;
import hn.hotel.ui.PrimerAdminDialog;

import javax.swing.*;
import java.awt.*;

public class Main {
    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        hn.hotel.ui.Tema.aplicar();

        SwingUtilities.invokeLater(() -> {
            try {
                Db.cargarConfiguracion();
                while (true) {
                    try {
                        Autenticacion.hayUsuarios();   // prueba la conexión
                        break;
                    } catch (RuntimeException ex) {
                        if (!pedirServidor(ex.getMessage())) System.exit(1);
                    }
                }
                if (!Autenticacion.hayUsuarios()) {
                    PrimerAdminDialog d = new PrimerAdminDialog(null);
                    d.setVisible(true);
                    if (!d.fueCreado()) System.exit(0);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "Error al iniciar:\n\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
            new LoginFrame().setVisible(true);
        });
    }

    /** Pantalla de conexión: sirve para apuntar esta PC al servidor de la red (IP o nombre del equipo donde está MySQL). */
    private static boolean pedirServidor(String motivo) {
        String[] actual = Db.servidorActual();
        JTextField host = new JTextField(actual[0], 18), puerto = new JTextField(actual[1], 6), user = new JTextField(Db.usuarioActual() == null ? "hotel_app" : Db.usuarioActual(), 14);
        JPasswordField clave = new JPasswordField(16);
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel("Servidor (IP o nombre):")); p.add(host);
        p.add(new JLabel("Puerto:")); p.add(puerto);
        p.add(new JLabel("Usuario de la base:")); p.add(user);
        p.add(new JLabel("Clave:")); p.add(clave);
        JPanel todo = new JPanel(new BorderLayout(0, 8));
        todo.add(new JLabel("<html><div style='width:360px'><b>No se pudo conectar con la base de datos.</b><br>" + (motivo == null ? "" : motivo.replace("<", "&lt;"))
                + "<br><br>Escriba los datos del servidor (la PC donde está MySQL). Si es esta misma PC use «localhost». "
                + "En una red, use la IP fija del servidor, por ejemplo 192.168.1.10.</div></html>"), BorderLayout.NORTH);
        todo.add(p, BorderLayout.CENTER);
        while (true) {
            Object[] op = {"Probar y guardar", "Salir"};
            if (JOptionPane.showOptionDialog(null, todo, "Conexión al servidor", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, op, op[0]) != 0) return false;
            try {
                String pw = new String(clave.getPassword());
                if (pw.isEmpty()) pw = "Hotel_App_2026!";
                Db.probar(host.getText(), puerto.getText(), user.getText().trim(), pw);
                Db.guardarConexion(host.getText(), puerto.getText(), user.getText().trim(), pw);
                return true;
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null, "No se pudo conectar:\n" + e.getMessage(), "Conexión", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
