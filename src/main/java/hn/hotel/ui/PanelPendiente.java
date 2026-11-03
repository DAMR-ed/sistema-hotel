package hn.hotel.ui;

import javax.swing.*;
import java.awt.*;

/** Marcador para módulos que todavía no están construidos. */
public class PanelPendiente extends JPanel {
    public PanelPendiente(String nombre) {
        setLayout(new GridBagLayout());
        JLabel l = new JLabel("<html><div style='text-align:center'><h2>" + nombre
                + "</h2>Este módulo se construye en la siguiente etapa.</div></html>");
        add(l);
    }
}
