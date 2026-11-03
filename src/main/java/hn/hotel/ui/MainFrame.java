package hn.hotel.ui;

import hn.hotel.app.Config;
import hn.hotel.app.Sesion;
import hn.hotel.db.Db;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;

public class MainFrame extends JFrame {
    private final CardLayout cartas = new CardLayout();
    private final JPanel centro = new JPanel(cartas);
    private final Map<String, JPanel> creados = new HashMap<>();
    private final Map<String, JButton> botones = new LinkedHashMap<>();
    private final Map<String, Modulos.Modulo> modulos = new LinkedHashMap<>();
    private final JButton btnAlertas = new JButton("Alertas: 0");
    private final JLabel lblTitulo = new JLabel(" ");
    private String actual;

    public MainFrame() {
        super("Sistema Hotel y Restaurante");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(crearBarraSuperior(), BorderLayout.NORTH);
        add(crearMenu(), BorderLayout.WEST);
        add(centro, BorderLayout.CENTER);
        setSize(1280, 800);
        setMinimumSize(new Dimension(1000, 650));
        setLocationRelativeTo(null);
        mostrar("inicio");

        javax.swing.Timer t = new javax.swing.Timer(60_000, e -> actualizarAlertas());
        t.setInitialDelay(1000);
        t.start();
    }

    private JComponent crearBarraSuperior() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Tema.BORDE),
                BorderFactory.createEmptyBorder(10, 20, 10, 20)));
        lblTitulo.setFont(lblTitulo.getFont().deriveFont(Font.BOLD, 18f));
        lblTitulo.setForeground(Tema.TEXTO);
        p.add(lblTitulo, BorderLayout.WEST);

        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        der.setOpaque(false);
        btnAlertas.putClientProperty("JButton.buttonType", "roundRect");
        btnAlertas.addActionListener(e -> { if (Sesion.puede("alarmas")) mostrar("alarmas"); });
        der.add(btnAlertas);
        JLabel usuario = new JLabel("<html><b>" + Sesion.nombre + "</b><br><span style='color:#64748B;font-size:10px'>"
                + Sesion.rol + "</span></html>");
        der.add(usuario);
        JButton clave = Ui.boton("Mi contraseña", this::cambiarMiClave);
        clave.putClientProperty("JButton.buttonType", "borderless");
        der.add(clave);
        JButton salir = Ui.boton("Cerrar sesión", () -> {
            if (Ui.confirmar(this, "¿Cerrar la sesión?")) {
                Sesion.cerrar();
                dispose();
                new LoginFrame().setVisible(true);
            }
        });
        salir.putClientProperty("JButton.buttonType", "borderless");
        der.add(salir);
        p.add(der, BorderLayout.EAST);
        actualizarAlertas();
        return p;
    }

    private JComponent crearMenu() {
        JPanel menu = new JPanel();
        menu.setBackground(Tema.SIDEBAR);
        menu.setLayout(new BoxLayout(menu, BoxLayout.Y_AXIS));
        menu.setBorder(BorderFactory.createEmptyBorder(16, 12, 12, 12));

        JLabel marca = new JLabel(Config.texto("hotel_nombre", "Hotel"));
        marca.setFont(marca.getFont().deriveFont(Font.BOLD, 17f));
        marca.setForeground(Color.WHITE);
        marca.setBorder(BorderFactory.createEmptyBorder(2, 8, 10, 0));
        marca.setAlignmentX(Component.LEFT_ALIGNMENT);
        menu.add(marca);

        String grupoActual = "";
        for (Modulos.Modulo m : Modulos.todos()) {
            if (!Sesion.puede(m.id())) continue;
            modulos.put(m.id(), m);
            if (!m.grupo().equals(grupoActual)) {
                grupoActual = m.grupo();
                JLabel g = new JLabel(grupoActual);
                g.setFont(g.getFont().deriveFont(Font.BOLD, 10f));
                g.setForeground(new Color(0x64748B));
                g.setBorder(BorderFactory.createEmptyBorder(16, 8, 6, 0));
                g.setAlignmentX(Component.LEFT_ALIGNMENT);
                menu.add(g);
            }
            JButton b = new JButton(m.titulo());
            b.putClientProperty("JButton.buttonType", "borderless");
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
            b.setMargin(new Insets(6, 12, 6, 12));
            b.setFocusable(false);
            b.setContentAreaFilled(false);
            b.setOpaque(false);
            b.setForeground(new Color(0xCBD5E1));
            b.putClientProperty("FlatLaf.style", "arc: 10; hoverBackground: #1E293B; pressedBackground: #334155");
            b.addActionListener(e -> mostrar(m.id()));
            botones.put(m.id(), b);
            menu.add(b);
            menu.add(Box.createVerticalStrut(2));
        }
        JScrollPane sp = new JScrollPane(menu, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Tema.SIDEBAR);
        sp.setPreferredSize(new Dimension(236, 0));
        return sp;
    }

    private void cambiarMiClave() {
        JPasswordField a = new JPasswordField(16), n1 = new JPasswordField(16), n2 = new JPasswordField(16);
        Campos.Formulario f = new Campos.Formulario().agregar("Contraseña actual:", a).agregar("Nueva contraseña:", n1).agregar("Repetir nueva:", n2);
        Campos.dialogo(this, "Cambiar mi contraseña", f, () -> {
            String nueva = new String(n1.getPassword());
            if (!nueva.equals(new String(n2.getPassword()))) throw new IllegalArgumentException("Las contraseñas nuevas no coinciden.");
            String err = hn.hotel.service.Autenticacion.cambiarMiClave(new String(a.getPassword()), nueva);
            if (err != null) throw new IllegalArgumentException(err);
            Ui.info(this, "Contraseña cambiada.");
            return true;
        });
    }

    public void mostrar(String id) {
        Modulos.Modulo m = modulos.get(id);
        if (m == null) return;
        JPanel p = creados.get(id);
        if (p == null) {
            try {
                p = m.fabrica().get();
            } catch (RuntimeException e) {
                p = new PanelPendiente(m.titulo() + " (error: " + e.getMessage() + ")");
            }
            creados.put(id, p);
            centro.add(p, id);
        }
        cartas.show(centro, id);
        actual = id;
        lblTitulo.setText(m.titulo());
        botones.forEach((k, b) -> {
            boolean sel = k.equals(id);
            b.setFont(b.getFont().deriveFont(sel ? Font.BOLD : Font.PLAIN));
            b.setForeground(sel ? Color.WHITE : new Color(0xCBD5E1));
            b.putClientProperty("FlatLaf.style", sel
                    ? "arc: 10; background: #2563EB; hoverBackground: #2563EB"
                    : "arc: 10; background: null; hoverBackground: #1E293B; pressedBackground: #334155");
            b.setContentAreaFilled(sel);
        });
        if (p instanceof Actualizable a) a.alMostrar();
        actualizarAlertas();
    }

    public void actualizarAlertas() {
        Ui.enSegundoPlano(this, () -> {
            try { return Db.ent(Db.scalar("SELECT COUNT(*) FROM v_alertas")); }
            catch (RuntimeException e) { return -1; }
        }, n -> {
            btnAlertas.setText(n < 0 ? "Alertas: ?" : "Alertas: " + n);
            btnAlertas.setForeground(n > 0 ? new Color(0xDC2626) : null);
        });
    }
}
