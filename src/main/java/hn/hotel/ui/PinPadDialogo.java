package hn.hotel.ui;

import hn.hotel.service.VposService;
import hn.hotel.service.VposService.Resultado;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;

/** Cuadro de espera mientras el cliente usa el PinPad. */
public final class PinPadDialogo {
    private PinPadDialogo() {}

    /** Cobra en el PinPad. Devuelve el resultado (aprobado o no) o null si hubo un error ya mostrado al cajero. */
    public static Resultado cobrar(Component padre, BigDecimal monto, BigDecimal totalCuenta, BigDecimal isvCuenta,
                                   String origen, Integer idComanda, Integer idEstancia) {
        if (!VposService.activo()) { Ui.info(padre, "El vPOS no está configurado (Configuración › vpos_url)."); return null; }
        return esperar(padre, "Cobro con tarjeta", "<html><div style='text-align:center;width:300px'><span style='font-size:22px'><b>" + Ui.lempiras(monto)
                + "</b></span><br><br>Pida al cliente que use la tarjeta en el PinPad.<br><span style='color:#64748B'>No cierre esta ventana.</span></div></html>",
                () -> VposService.cobrar(monto, totalCuenta, isvCuenta, origen, idComanda, idEstancia), true);
    }

    /** Ejecuta una operación del PinPad con ventana de espera (usado también por anulación y cierre). */
    public static <T> T esperar(Component padre, String titulo, String mensaje, java.util.function.Supplier<T> trabajo, boolean mostrarErrores) {
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(w, titulo, Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel p = new JPanel(new BorderLayout(0, 14));
        p.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
        p.add(new JLabel(mensaje, SwingConstants.CENTER), BorderLayout.CENTER);
        JProgressBar barra = new JProgressBar();
        barra.setIndeterminate(true);
        p.add(barra, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(padre);

        SwingWorker<T, Void> sw = new SwingWorker<>() {
            @Override protected T doInBackground() { return trabajo.get(); }
            @Override protected void done() { d.dispose(); }
        };
        sw.execute();
        d.setVisible(true);          // bloquea hasta que termine el trabajo
        try {
            return sw.get();
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            if (mostrarErrores) Ui.error(padre, c.getMessage() == null ? c.toString() : c.getMessage());
            else throw new IllegalStateException(c.getMessage(), c);
            return null;
        }
    }
}
