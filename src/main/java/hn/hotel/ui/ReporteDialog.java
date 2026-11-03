package hn.hotel.ui;

import javax.swing.*;
import java.awt.*;
import java.util.Map;

/** Abre un reporte del catálogo en una ventana (por ejemplo, el estado de cuenta desde la ficha del cliente). */
public final class ReporteDialog {
    private ReporteDialog() {}

    public static void mostrar(Component padre, String nombreReporte, Map<String, Integer> presets) {
        ReporteDef d = CatalogoReportes.buscar(nombreReporte);
        if (d == null) { Ui.error(padre, "El reporte «" + nombreReporte + "» no existe."); return; }
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        JDialog dlg = new JDialog(w, d.nombre(), Dialog.ModalityType.APPLICATION_MODAL);
        ReporteDinamico r = new ReporteDinamico();
        dlg.add(r);
        dlg.setSize(1100, 680);
        dlg.setLocationRelativeTo(padre);
        r.abrir(d, presets);
        dlg.setVisible(true);
    }
}
