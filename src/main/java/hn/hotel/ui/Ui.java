package hn.hotel.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Utilidades de interfaz compartidas por todas las pantallas. */
public final class Ui {
    private Ui() {}

    public static final Color COLOR_PRIMARIO = new Color(0x0F172A);
    public static final Color COLOR_LIBRE = new Color(0x2E9E5B);
    public static final Color COLOR_OCUPADA = new Color(0xD64545);
    public static final Color COLOR_RESERVADA = new Color(0xE0A100);
    public static final Color COLOR_LIMPIEZA = new Color(0x3B8EDB);
    public static final Color COLOR_REPARACION = new Color(0x7A7A7A);

    public static final DateTimeFormatter F_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final DateTimeFormatter F_FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DecimalFormat F_DINERO = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    public static String lempiras(Object monto) {
        BigDecimal b = monto == null ? BigDecimal.ZERO : (monto instanceof BigDecimal d ? d : new BigDecimal(monto.toString()));
        synchronized (F_DINERO) { return "L " + F_DINERO.format(b); }
    }

    public static String numero(Object monto) {
        BigDecimal b = monto == null ? BigDecimal.ZERO : (monto instanceof BigDecimal d ? d : new BigDecimal(monto.toString()));
        synchronized (F_DINERO) { return F_DINERO.format(b); }
    }

    /** Convierte lo que devuelve JDBC (Date, LocalDate, Timestamp, texto) a LocalDate. */
    public static LocalDate fecha(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDateTime d) return d.toLocalDate();
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime().toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    public static LocalDateTime fechaHora(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDateTime d) return d;
        if (o instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (o instanceof LocalDate d) return d.atStartOfDay();
        if (o instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        return LocalDateTime.parse(o.toString().replace(' ', 'T'));
    }

    public static String texto(LocalDate d) { return d == null ? "" : d.format(F_FECHA); }
    public static String texto(LocalDateTime d) { return d == null ? "" : d.format(F_FECHA_HORA); }

    /** Lee "dd/MM/yyyy" (también acepta yyyy-MM-dd). Lanza IllegalArgumentException con mensaje claro. */
    public static LocalDate leerFecha(String s, String campo) {
        String t = s == null ? "" : s.trim();
        try {
            if (t.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(t);
            return LocalDate.parse(t, F_FECHA);
        } catch (Exception e) {
            throw new IllegalArgumentException("La fecha de \"" + campo + "\" no es válida. Use el formato dd/mm/aaaa.");
        }
    }

    public static BigDecimal leerDinero(String s, String campo) {
        try {
            BigDecimal b = new BigDecimal(s.trim().replace(",", "").replace("L", "").trim());
            return b;
        } catch (Exception e) {
            throw new IllegalArgumentException("El valor de \"" + campo + "\" no es un número válido.");
        }
    }

    // ---- Mensajes ----
    public static void error(Component padre, String mensaje) {
        JOptionPane.showMessageDialog(padre, mensaje, "Error", JOptionPane.ERROR_MESSAGE);
    }

    public static void info(Component padre, String mensaje) {
        JOptionPane.showMessageDialog(padre, mensaje, "Información", JOptionPane.INFORMATION_MESSAGE);
    }

    public static boolean confirmar(Component padre, String pregunta) {
        return JOptionPane.showConfirmDialog(padre, pregunta, "Confirmar",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    // ---- Componentes ----
    public static JLabel titulo(String texto) {
        JLabel l = new JLabel(texto);
        l.setFont(l.getFont().deriveFont(Font.BOLD, 22f));
        l.setForeground(Tema.TEXTO);
        return l;
    }

    public static JButton boton(String texto, Runnable accion) {
        JButton b = new JButton(texto);
        b.addActionListener(e -> accion.run());
        return b;
    }

    /** Botón de acción principal (azul, relleno). */
    public static JButton botonPrimario(String texto, Runnable accion) {
        JButton b = boton(texto, accion);
        b.setBackground(Tema.ACENTO);
        b.setForeground(Color.WHITE);
        b.setFont(b.getFont().deriveFont(Font.BOLD));
        return b;
    }

    public static JPanel fila(Component... comps) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        for (Component c : comps) p.add(c);
        return p;
    }

    public static DefaultTableModel modelo(String... columnas) {
        return new DefaultTableModel(columnas, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    public static JTable tabla(DefaultTableModel m) {
        JTable t = new JTable(m);
        t.setRowHeight(32);
        t.setShowHorizontalLines(true);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 1));
        t.setAutoCreateRowSorter(true);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.getTableHeader().setReorderingAllowed(false);
        t.setFillsViewportHeight(true);
        return t;
    }

    public static void alinearDerecha(JTable t, int... columnas) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer();
        r.setHorizontalAlignment(SwingConstants.RIGHT);
        for (int c : columnas) t.getColumnModel().getColumn(c).setCellRenderer(r);
    }

    /** Llena el modelo con las filas; "claves" son los alias de las columnas del SELECT. Fechas y dinero se formatean. */
    public static void llenar(DefaultTableModel m, List<Map<String, Object>> filas, String... claves) {
        m.setRowCount(0);
        for (Map<String, Object> f : filas) {
            Object[] fila = new Object[claves.length];
            for (int i = 0; i < claves.length; i++) fila[i] = formatear(f.get(claves[i]));
            m.addRow(fila);
        }
    }

    public static Object formatear(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal b) return numero(b);
        if (v instanceof java.sql.Timestamp t) return texto(t.toLocalDateTime());
        if (v instanceof LocalDateTime d) return texto(d);
        if (v instanceof java.sql.Date d) return texto(d.toLocalDate());
        if (v instanceof LocalDate d) return texto(d);
        return v;
    }

    /** Valor de la columna "0" (id) de la fila seleccionada, o -1. Convención: la primera columna es el id. */
    public static int idSeleccionado(JTable t) {
        int fila = t.getSelectedRow();
        if (fila < 0) return -1;
        Object v = t.getValueAt(fila, 0);
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** Ejecuta el trabajo fuera del hilo de pantalla y entrega el resultado en él. Los errores salen en un mensaje. */
    public static <T> void enSegundoPlano(Component padre, Supplier<T> trabajo, Consumer<T> alTerminar) {
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() { return trabajo.get(); }
            @Override protected void done() {
                try { alTerminar.accept(get()); }
                catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    error(padre, c.getMessage() == null ? c.toString() : c.getMessage());
                }
            }
        }.execute();
    }

    /** Ejecuta una acción que puede fallar mostrando el error en un cuadro. */
    public static void seguro(Component padre, Runnable accion) {
        try { accion.run(); }
        catch (IllegalArgumentException e) { error(padre, e.getMessage()); }
        catch (RuntimeException e) { error(padre, e.getMessage() == null ? e.toString() : e.getMessage()); }
    }

    public static Color colorEstado(String estado) {
        return switch (estado) {
            case "LIBRE" -> COLOR_LIBRE;
            case "OCUPADA" -> COLOR_OCUPADA;
            case "RESERVADA" -> COLOR_RESERVADA;
            case "LIMPIEZA" -> COLOR_LIMPIEZA;
            case "REPARACION", "INACTIVA" -> COLOR_REPARACION;
            default -> Color.GRAY;
        };
    }

    public static Color colorHex(String hex, Color defecto) {
        try { return Color.decode(hex); } catch (Exception e) { return defecto; }
    }
}
