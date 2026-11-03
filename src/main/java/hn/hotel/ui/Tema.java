package hn.hotel.ui;

import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;

/** Aspecto visual moderno de todo el sistema. Se aplica una vez al arrancar. */
public final class Tema {
    private Tema() {}

    public static final Color ACENTO   = new Color(0x2563EB);
    public static final Color SIDEBAR  = new Color(0x0F172A);
    public static final Color SIDEBAR_HOVER = new Color(0x1E293B);
    public static final Color FONDO    = new Color(0xF4F6FA);
    public static final Color TARJETA  = Color.WHITE;
    public static final Color BORDE    = new Color(0xE2E8F0);
    public static final Color TEXTO    = new Color(0x0F172A);
    public static final Color TEXTO_SUAVE = new Color(0x64748B);

    public static void aplicar() {
        FlatLightLaf.setup();
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("ProgressBar.arc", 10);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusColor", ACENTO);
        UIManager.put("Component.borderColor", BORDE);
        UIManager.put("Button.default.background", ACENTO);
        UIManager.put("Button.default.foreground", Color.WHITE);
        UIManager.put("Button.default.focusColor", ACENTO);
        UIManager.put("Button.margin", new Insets(6, 14, 6, 14));
        UIManager.put("TextField.margin", new Insets(5, 8, 5, 8));
        UIManager.put("PasswordField.margin", new Insets(5, 8, 5, 8));
        UIManager.put("ComboBox.padding", new Insets(4, 8, 4, 8));
        UIManager.put("Spinner.padding", new Insets(4, 8, 4, 8));

        UIManager.put("Panel.background", FONDO);
        UIManager.put("TextField.background", Color.WHITE);
        UIManager.put("List.selectionBackground", new Color(0xDBEAFE));
        UIManager.put("List.selectionForeground", TEXTO);

        // Tablas: sin líneas verticales, filas altas, cabecera limpia
        UIManager.put("Table.rowHeight", 32);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new Dimension(0, 1));
        UIManager.put("Table.gridColor", new Color(0xEEF2F7));
        UIManager.put("Table.background", Color.WHITE);
        UIManager.put("Table.selectionBackground", new Color(0xDBEAFE));
        UIManager.put("Table.selectionForeground", TEXTO);
        UIManager.put("Table.selectionInactiveBackground", new Color(0xE8F0FE));
        UIManager.put("Table.alternateRowColor", new Color(0xF8FAFC));
        UIManager.put("TableHeader.background", new Color(0xF1F5F9));
        UIManager.put("TableHeader.foreground", TEXTO_SUAVE);
        UIManager.put("TableHeader.bottomSeparatorColor", BORDE);
        UIManager.put("TableHeader.separatorColor", new Color(0xF1F5F9));
        UIManager.put("TableHeader.height", 34);
        UIManager.put("TableHeader.font", UIManager.getFont("TableHeader.font").deriveFont(Font.BOLD, 12f));
        UIManager.put("ScrollPane.border", BorderFactory.createLineBorder(BORDE));

        UIManager.put("TabbedPane.showTabSeparators", false);
        UIManager.put("TabbedPane.selectedBackground", Color.WHITE);
        UIManager.put("TabbedPane.underlineColor", ACENTO);
        UIManager.put("TabbedPane.selectedForeground", ACENTO);
        UIManager.put("TabbedPane.tabHeight", 38);
        UIManager.put("TabbedPane.tabInsets", new Insets(4, 16, 4, 16));

        UIManager.put("TitledBorder.border", BorderFactory.createLineBorder(BORDE));
        UIManager.put("TitledBorder.titleColor", TEXTO_SUAVE);
        UIManager.put("TitledBorder.font", UIManager.getFont("Label.font").deriveFont(Font.BOLD, 12f));
        UIManager.put("OptionPane.buttonOrientation", SwingConstants.RIGHT);
        UIManager.put("SplitPane.dividerSize", 6);
    }

    /** Panel blanco con esquinas redondeadas y un borde fino. */
    public static class Tarjeta extends JPanel {
        private final Color franja;
        public Tarjeta(LayoutManager lm, Color franja) {
            super(lm);
            this.franja = franja;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));
        }
        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 1, h = getHeight() - 1;
            g.setColor(TARJETA);
            g.fillRoundRect(0, 0, w, h, 16, 16);
            g.setColor(BORDE);
            g.drawRoundRect(0, 0, w, h, 16, 16);
            if (franja != null) {
                g.setColor(franja);
                g.fillRoundRect(0, 14, 5, h - 28, 5, 5);
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }
}
