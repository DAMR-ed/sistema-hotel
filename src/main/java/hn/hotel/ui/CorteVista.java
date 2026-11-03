package hn.hotel.ui;

import hn.hotel.service.CorteService;
import hn.hotel.service.Impresion;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Muestra un corte (X o Z): para imprimir en el ticket, guardar como PDF (cuadro de impresión) o guardar como archivo de texto. */
public final class CorteVista {
    private CorteVista() {}

    public static void mostrar(Component padre, int idCorte) {
        Map<String, Object> c = CorteService.obtener(idCorte);
        mostrar(padre, "Corte " + c.get("tipo") + " #" + idCorte, Impresion.corte(c), idCorte, "corte_" + c.get("tipo") + "_" + idCorte);
    }

    /** idCorte = 0 para una vista previa que todavía no se guardó (sin botón de ticket). */
    public static void mostrar(Component padre, String titulo, String texto, int idCorte, String nombreArchivo) {
        JTextArea ta = new JTextArea(texto, 34, Impresion.ancho() + 6);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        ta.setEditable(false);
        ta.setCaretPosition(0);
        Window w = padre == null ? null : SwingUtilities.getWindowAncestor(padre);
        JDialog d = new JDialog(w, titulo, Dialog.ModalityType.APPLICATION_MODAL);
        JPanel sur = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        if (idCorte > 0) sur.add(Ui.boton("Imprimir ticket 80 mm", () -> {
            try {
                if (Impresion.imprimirCorte(idCorte)) Ui.info(d, "Corte enviado a la impresora de caja.");
                else Ui.info(d, "No hay impresora configurada en «impresora_cuenta» (Configuración). Use «Imprimir / Guardar PDF».");
            } catch (RuntimeException e) { Ui.error(d, e.getMessage()); }
        }));
        sur.add(Ui.boton("Imprimir / Guardar PDF", () -> {
            try { ta.print(); } catch (Exception e) { Ui.error(d, "No se pudo imprimir: " + e.getMessage()); }
        }));
        sur.add(Ui.boton("Guardar archivo (.txt)", () -> {
            JFileChooser ch = new JFileChooser();
            ch.setSelectedFile(new File(nombreArchivo + ".txt"));
            if (ch.showSaveDialog(d) != JFileChooser.APPROVE_OPTION) return;
            try { Files.writeString(ch.getSelectedFile().toPath(), ta.getText(), StandardCharsets.UTF_8); Ui.info(d, "Guardado en " + ch.getSelectedFile()); }
            catch (Exception e) { Ui.error(d, "No se pudo guardar: " + e.getMessage()); }
        }));
        sur.add(Ui.boton("Cerrar", d::dispose));
        d.setLayout(new BorderLayout());
        d.add(new JScrollPane(ta), BorderLayout.CENTER);
        d.add(sur, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(padre);
        d.setVisible(true);
    }
}
