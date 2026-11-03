package hn.hotel.ui;

import hn.hotel.service.Autenticacion;

import javax.swing.*;
import java.awt.*;

/** Se muestra una sola vez, cuando la base de datos no tiene usuarios: crea el administrador. */
public class PrimerAdminDialog extends JDialog {
    private final JTextField txtUsuario = new JTextField("admin", 18);
    private final JTextField txtNombre = new JTextField(18);
    private final JPasswordField txtClave = new JPasswordField(18);
    private final JPasswordField txtClave2 = new JPasswordField(18);
    private boolean creado = false;

    public PrimerAdminDialog(Frame padre) {
        super(padre, "Crear administrador", true);
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(5, 5, 5, 5);
        g.gridx = 0; g.gridy = 0; g.gridwidth = 2;
        p.add(new JLabel("<html><b>Primer uso:</b> cree el usuario administrador.<br>"
                + "Mínimo " + Autenticacion.CLAVE_MINIMA + " caracteres, con letras y números.</html>"), g);
        g.gridwidth = 1;
        String[] etq = {"Usuario:", "Nombre completo:", "Contraseña:", "Repetir contraseña:"};
        JComponent[] cmp = {txtUsuario, txtNombre, txtClave, txtClave2};
        for (int i = 0; i < etq.length; i++) {
            g.gridy++; g.gridx = 0; g.anchor = GridBagConstraints.EAST; p.add(new JLabel(etq[i]), g);
            g.gridx = 1; g.anchor = GridBagConstraints.WEST; p.add(cmp[i], g);
        }
        JButton ok = Ui.boton("Crear y continuar", this::crear);
        g.gridy++; g.gridx = 0; g.gridwidth = 2; g.anchor = GridBagConstraints.CENTER;
        p.add(ok, g);
        setContentPane(p);
        getRootPane().setDefaultButton(ok);
        pack();
        setLocationRelativeTo(padre);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    private void crear() {
        Ui.seguro(this, () -> {
            if (txtUsuario.getText().isBlank() || txtNombre.getText().isBlank())
                throw new IllegalArgumentException("Complete usuario y nombre.");
            String c1 = new String(txtClave.getPassword());
            if (!c1.equals(new String(txtClave2.getPassword())))
                throw new IllegalArgumentException("Las contraseñas no coinciden.");
            String err = Autenticacion.validarClave(c1);
            if (err != null) throw new IllegalArgumentException(err);
            Autenticacion.crearUsuario(txtUsuario.getText(), txtNombre.getText(), c1, "ADMIN");
            creado = true;
            dispose();
        });
    }

    public boolean fueCreado() { return creado; }
}
