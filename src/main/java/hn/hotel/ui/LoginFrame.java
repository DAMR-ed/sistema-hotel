package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.service.Autenticacion;

import javax.swing.*;
import java.awt.*;

public class LoginFrame extends JFrame {
    private final JTextField txtUsuario = new JTextField(20);
    private final JPasswordField txtClave = new JPasswordField(20);
    private final JLabel lblMensaje = new JLabel(" ");
    private final JButton btnEntrar = new JButton("Iniciar sesión");

    public LoginFrame() {
        super("Sistema Hotel y Restaurante");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        // Lado izquierdo: marca
        JPanel marca = new JPanel(new GridBagLayout());
        marca.setBackground(Tema.SIDEBAR);
        marca.setPreferredSize(new Dimension(320, 440));
        JLabel nombre = new JLabel("<html><div style='width:230px'><span style='font-size:26px;color:white'><b>"
                + Config.texto("hotel_nombre", "Hotel") + "</b></span><br><br>"
                + "<span style='font-size:13px;color:#94A3B8'>Hospedaje, restaurante, inventario y facturación en un solo sistema.</span></div></html>");
        marca.add(nombre);

        // Lado derecho: formulario
        JPanel form = new JPanel(new GridBagLayout());
        form.setBackground(Color.WHITE);
        form.setBorder(BorderFactory.createEmptyBorder(30, 44, 30, 44));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.gridy = 0; g.fill = GridBagConstraints.HORIZONTAL; g.weightx = 1;
        g.insets = new Insets(0, 0, 4, 0);
        JLabel t = new JLabel("Bienvenido");
        t.setFont(t.getFont().deriveFont(Font.BOLD, 24f));
        form.add(t, g);
        g.gridy++; g.insets = new Insets(0, 0, 22, 0);
        JLabel st = new JLabel("Inicie sesión para continuar");
        st.setForeground(Tema.TEXTO_SUAVE);
        form.add(st, g);

        txtUsuario.putClientProperty("JTextField.placeholderText", "Usuario");
        txtClave.putClientProperty("JTextField.placeholderText", "Contraseña");
        txtClave.putClientProperty("JPasswordField.showRevealButton", true);
        g.gridy++; g.insets = new Insets(0, 0, 10, 0);
        form.add(new JLabel("Usuario"), g);
        g.gridy++; form.add(txtUsuario, g);
        g.gridy++; form.add(new JLabel("Contraseña"), g);
        g.gridy++; g.insets = new Insets(0, 0, 8, 0); form.add(txtClave, g);
        lblMensaje.setForeground(new Color(0xDC2626));
        g.gridy++; form.add(lblMensaje, g);
        g.gridy++; g.insets = new Insets(8, 0, 0, 0);
        btnEntrar.setFont(btnEntrar.getFont().deriveFont(Font.BOLD, 14f));
        btnEntrar.setMargin(new Insets(10, 14, 10, 14));
        form.add(btnEntrar, g);

        JPanel raiz = new JPanel(new BorderLayout());
        raiz.add(marca, BorderLayout.WEST);
        raiz.add(form, BorderLayout.CENTER);
        setContentPane(raiz);
        getRootPane().setDefaultButton(btnEntrar);
        btnEntrar.addActionListener(e -> entrar());
        pack();
        setMinimumSize(new Dimension(760, 440));
        setLocationRelativeTo(null);
    }

    private void entrar() {
        if (txtUsuario.getText().isBlank() || txtClave.getPassword().length == 0) {
            lblMensaje.setText("Escriba usuario y contraseña.");
            return;
        }
        btnEntrar.setEnabled(false);
        lblMensaje.setText("Verificando...");
        char[] clave = txtClave.getPassword();
        String usuario = txtUsuario.getText();
        Ui.enSegundoPlano(this, () -> Autenticacion.iniciar(usuario, clave), error -> {
            java.util.Arrays.fill(clave, '0');
            btnEntrar.setEnabled(true);
            if (error == null) {
                dispose();
                new MainFrame().setVisible(true);
            } else {
                lblMensaje.setText("<html><div style='width:260px'>" + error.replace("\n", "<br>") + "</div></html>");
                txtClave.setText("");
                txtClave.requestFocus();
            }
        });
    }
}
