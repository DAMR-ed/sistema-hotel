package hn.hotel.ui;

import hn.hotel.service.Impresion;
import hn.hotel.service.TicketService;
import hn.hotel.service.TicketService.Ticket;

import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pantalla de cocina y barra (KDS): tickets en tres columnas, como las comandas de Odoo. */
public class CocinaPanel extends JPanel implements Actualizable {
    private final JPanel colNuevo = columna(), colPrep = columna(), colListo = columna();
    private final JLabel lblNuevo = new JLabel(), lblPrep = new JLabel(), lblListo = new JLabel();
    private final JComboBox<String> cmbEstacion = new JComboBox<>(new String[]{"Todas las estaciones"});
    private boolean cargandoEstaciones;
    private final Set<Integer> vistos = new HashSet<>();
    private boolean primeraCarga = true;

    public CocinaPanel() {
        setLayout(new BorderLayout(0, 12));
        setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel norte = new JPanel(new BorderLayout());
        norte.setOpaque(false);
        norte.add(new JLabel("<html><span style='font-size:20px'><b>Pantalla de comandas</b></span></html>"), BorderLayout.WEST);
        JPanel filtro = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filtro.setOpaque(false);
        filtro.add(new JLabel("Pantalla de:"));
        cmbEstacion.addActionListener(e -> { if (!cargandoEstaciones) alMostrar(); });
        filtro.add(cmbEstacion);
        filtro.add(Ui.boton("Actualizar", this::alMostrar));
        norte.add(filtro, BorderLayout.EAST);
        add(norte, BorderLayout.NORTH);

        JPanel cols = new JPanel(new GridLayout(1, 3, 14, 0));
        cols.setOpaque(false);
        cols.add(envolver("NUEVOS", lblNuevo, colNuevo, "#2563EB"));
        cols.add(envolver("EN PREPARACIÓN", lblPrep, colPrep, "#E0A100"));
        cols.add(envolver("LISTOS PARA SERVIR", lblListo, colListo, "#2E9E5B"));
        add(cols, BorderLayout.CENTER);

        javax.swing.Timer reloj = new javax.swing.Timer(8_000, e -> { if (isShowing()) alMostrar(); });
        reloj.start();
    }

    private static JPanel columna() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private JComponent envolver(String titulo, JLabel cuenta, JPanel contenido, String color) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setOpaque(false);
        JLabel t = new JLabel(titulo);
        t.setFont(t.getFont().deriveFont(Font.BOLD, 12f));
        t.setForeground(Color.decode(color));
        JPanel cab = new JPanel(new BorderLayout());
        cab.setOpaque(false);
        cab.add(t, BorderLayout.WEST);
        cuenta.setForeground(Tema.TEXTO_SUAVE);
        cab.add(cuenta, BorderLayout.EAST);
        cab.setBorder(BorderFactory.createMatteBorder(0, 0, 3, 0, Color.decode(color)));
        p.add(cab, BorderLayout.NORTH);
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.setOpaque(false);
        arriba.add(contenido, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(arriba);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private String destino() { return cmbEstacion.getSelectedIndex() <= 0 ? null : (String) cmbEstacion.getSelectedItem(); }

    @Override public void alMostrar() {
        String elegida = destino();
        Ui.enSegundoPlano(this, () -> new Object[]{TicketService.estacionesConPantalla(), TicketService.activos(elegida)}, r -> {
            @SuppressWarnings("unchecked") List<TicketService.Estacion> ests = (List<TicketService.Estacion>) r[0];
            @SuppressWarnings("unchecked") List<Ticket> tickets = (List<Ticket>) r[1];
            cargandoEstaciones = true;
            cmbEstacion.removeAllItems();
            cmbEstacion.addItem("Todas las estaciones");
            for (TicketService.Estacion e : ests) cmbEstacion.addItem(e.nombre());
            cmbEstacion.setSelectedItem(elegida == null ? "Todas las estaciones" : elegida);
            cargandoEstaciones = false;
            pintar(tickets);
        });
    }

    private void pintar(List<Ticket> tickets) {
        colNuevo.removeAll(); colPrep.removeAll(); colListo.removeAll();
        int n = 0, p = 0, l = 0;
        boolean sonar = false;
        for (Ticket t : tickets) {
            if (!vistos.contains(t.id())) { vistos.add(t.id()); if (!primeraCarga && t.estado().equals("NUEVO")) sonar = true; }
            JComponent tarjeta = tarjeta(t);
            switch (t.estado()) {
                case "NUEVO" -> { colNuevo.add(tarjeta); n++; }
                case "PREPARANDO" -> { colPrep.add(tarjeta); p++; }
                default -> { colListo.add(tarjeta); l++; }
            }
            tarjeta.setAlignmentX(Component.LEFT_ALIGNMENT);
            ((JComponent) tarjeta).setMaximumSize(new Dimension(Integer.MAX_VALUE, tarjeta.getPreferredSize().height));
        }
        primeraCarga = false;
        lblNuevo.setText(String.valueOf(n)); lblPrep.setText(String.valueOf(p)); lblListo.setText(String.valueOf(l));
        for (JPanel c : new JPanel[]{colNuevo, colPrep, colListo}) { c.revalidate(); c.repaint(); }
        if (sonar) Toolkit.getDefaultToolkit().beep();
    }

    private JComponent tarjeta(Ticket t) {
        String tiempo = t.minutos() >= 20 ? "#DC2626" : t.minutos() >= 10 ? "#D97706" : "#2E9E5B";
        Tema.Tarjeta c = new Tema.Tarjeta(new BorderLayout(0, 6), Color.decode(tiempo));
        c.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 14));

        StringBuilder h = new StringBuilder("<html><span style='font-size:15px'><b>#" + t.numero() + " · " + t.donde() + "</b></span><br>"
                + "<span style='color:#64748B;font-size:11px'>" + t.destino() + " · " + t.hora() + " · " + t.mesero() + " · </span>"
                + "<span style='color:" + tiempo + ";font-size:11px'><b>" + t.minutos() + " min</b></span></html>");
        c.add(new JLabel(h.toString()), BorderLayout.NORTH);

        StringBuilder cuerpo = new StringBuilder("<html><div style='width:230px'>");
        for (TicketService.LineaTicket l : t.lineas()) {
            if (l.estado().equals("ANULADO"))
                cuerpo.append("<div style='color:#DC2626'><s>").append(l.cantidad()).append(" × ").append(l.descripcion()).append("</s> <b>ANULADO</b></div>");
            else
                cuerpo.append("<div style='font-size:14px;margin-top:3px'><b>").append(l.cantidad()).append(" ×</b> ").append(l.descripcion()).append("</div>");
            if (!l.notas().isBlank() && !l.estado().equals("ANULADO"))
                cuerpo.append("<div style='color:#B45309;font-size:12px'>&nbsp;&nbsp;» ").append(l.notas()).append("</div>");
        }
        cuerpo.append("</div></html>");
        c.add(new JLabel(cuerpo.toString()), BorderLayout.CENTER);

        JPanel acciones = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        acciones.setOpaque(false);
        String sig = switch (t.estado()) { case "NUEVO" -> "PREPARANDO"; case "PREPARANDO" -> "LISTO"; default -> "ENTREGADO"; };
        String txt = switch (t.estado()) { case "NUEVO" -> "Preparar"; case "PREPARANDO" -> "Listo"; default -> "Entregado"; };
        JButton ok = Ui.botonPrimario(txt, () -> Ui.seguro(this, () -> { TicketService.cambiarEstado(t.id(), sig); alMostrar(); }));
        acciones.add(ok);
        if (!t.estado().equals("NUEVO")) {
            String ant = t.estado().equals("LISTO") ? "PREPARANDO" : "NUEVO";
            JButton atras = Ui.boton("↩", () -> Ui.seguro(this, () -> { TicketService.cambiarEstado(t.id(), ant); alMostrar(); }));
            atras.setToolTipText("Regresar un paso");
            acciones.add(atras);
        }
        JButton imp = Ui.boton("Imprimir", () -> imprimir(t));
        imp.putClientProperty("JButton.buttonType", "borderless");
        acciones.add(imp);
        c.add(acciones, BorderLayout.SOUTH);
        c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0), c.getBorder()));
        return c;
    }

    private void imprimir(Ticket t) {
        Ui.enSegundoPlano(this, () -> {
            try { return Impresion.imprimirTicket(t.id()); }
            catch (RuntimeException e) { throw e; }
        }, impreso -> {
            if (impreso) return;
            // No hay impresora configurada: imprimir con el cuadro normal del sistema
            JTextArea ta = new JTextArea(Impresion.ticketCocina(t));
            ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            try { ta.print(); } catch (Exception ex) { Ui.error(this, ex.getMessage()); }
        });
    }
}
