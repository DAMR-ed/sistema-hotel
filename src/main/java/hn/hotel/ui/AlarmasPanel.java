package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Alarmas automáticas (llegadas, salidas, créditos vencidos, stock, CAI) y recordatorios propios. */
public class AlarmasPanel extends JPanel implements Actualizable {
    private final DefaultTableModel mAuto = Ui.modelo("Tipo", "Prioridad", "Fecha", "Detalle");
    private final DefaultTableModel mRec = Ui.modelo("Id", "Fecha y hora", "Recordatorio", "Estado");
    private final JTable tRec = Ui.tabla(mRec);

    public AlarmasPanel() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Ui.titulo("Alarmas y recordatorios"), BorderLayout.NORTH);

        JTable tAuto = Ui.tabla(mAuto);
        tAuto.getColumnModel().getColumn(0).setPreferredWidth(140);
        tAuto.getColumnModel().getColumn(1).setPreferredWidth(70);
        tAuto.getColumnModel().getColumn(2).setPreferredWidth(90);
        tAuto.getColumnModel().getColumn(3).setPreferredWidth(600);
        tAuto.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                if (!sel) comp.setForeground("ALTA".equals(t.getValueAt(r, 1)) ? new Color(0xB00020) : Color.DARK_GRAY);
                return comp;
            }
        });
        JPanel automaticas = new JPanel(new BorderLayout());
        automaticas.setBorder(BorderFactory.createTitledBorder("Automáticas (se calculan solas)"));
        automaticas.add(new JScrollPane(tAuto));

        JPanel manuales = new JPanel(new BorderLayout());
        manuales.setBorder(BorderFactory.createTitledBorder("Recordatorios"));
        tRec.getColumnModel().getColumn(0).setMaxWidth(55);
        manuales.add(new JScrollPane(tRec), BorderLayout.CENTER);
        manuales.add(Ui.fila(Ui.boton("Nuevo recordatorio", this::nuevo),
                Ui.boton("Marcar atendido", () -> cambiar("ATENDIDA")),
                Ui.boton("Descartar", () -> cambiar("DESCARTADA"))), BorderLayout.SOUTH);

        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, automaticas, manuales);
        sp.setResizeWeight(0.62);
        add(sp, BorderLayout.CENTER);
    }

    private void nuevo() {
        JSpinner fecha = Campos.fecha(LocalDate.now());
        JTextField hora = new JTextField("08:00", 6);
        JTextField msg = new JTextField(30);
        Campos.Formulario f = new Campos.Formulario();
        f.agregar("Fecha:", fecha).agregar("Hora (hh:mm):", hora).agregar("Mensaje:", msg);
        Campos.dialogo(this, "Nuevo recordatorio", f, () -> {
            if (msg.getText().isBlank()) throw new IllegalArgumentException("Escriba el mensaje.");
            LocalTime h;
            try { h = LocalTime.parse(hora.getText().trim()); } catch (Exception e) { throw new IllegalArgumentException("La hora debe ser hh:mm, por ejemplo 14:30."); }
            Db.insert("INSERT INTO recordatorios (fecha_hora, mensaje, id_usuario) VALUES (?,?,?)",
                    LocalDateTime.of(Campos.valor(fecha), h), msg.getText().trim(), Sesion.idUsuario);
            return true;
        });
        alMostrar();
    }

    private void cambiar(String estado) {
        int id = Ui.idSeleccionado(tRec);
        if (id < 0) { Ui.info(this, "Seleccione un recordatorio."); return; }
        Db.exec("UPDATE recordatorios SET estado = ? WHERE id_recordatorio = ?", estado, id);
        alMostrar();
    }

    @Override public void alMostrar() {
        Ui.enSegundoPlano(this, () -> Db.sin(tx -> new Object[]{
                tx.query("SELECT tipo, severidad, fecha, mensaje FROM v_alertas ORDER BY FIELD(severidad,'ALTA','MEDIA'), fecha"),
                tx.query("SELECT id_recordatorio, fecha_hora, mensaje, estado FROM recordatorios ORDER BY estado = 'PENDIENTE' DESC, fecha_hora DESC LIMIT 200")}),
            r -> {
                @SuppressWarnings("unchecked") var a = (java.util.List<java.util.Map<String, Object>>) r[0];
                @SuppressWarnings("unchecked") var b = (java.util.List<java.util.Map<String, Object>>) r[1];
                Ui.llenar(mAuto, a, "tipo", "severidad", "fecha", "mensaje");
                Ui.llenar(mRec, b, "id_recordatorio", "fecha_hora", "mensaje", "estado");
            });
    }
}
