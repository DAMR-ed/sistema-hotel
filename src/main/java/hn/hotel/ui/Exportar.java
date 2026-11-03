package hn.hotel.ui;

import javax.swing.*;
import javax.swing.table.TableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Exporta una tabla a CSV (se abre en Excel). */
public final class Exportar {
    private Exportar() {}

    public static void csv(Component padre, TableModel m, String nombreSugerido) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(nombreSugerido + ".csv"));
        if (fc.showSaveDialog(padre) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".csv")) f = new File(f.getPath() + ".csv");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write('﻿');   // para que Excel respete los acentos
            StringBuilder sb = new StringBuilder();
            for (int c = 0; c < m.getColumnCount(); c++) { if (c > 0) sb.append(';'); sb.append(celda(m.getColumnName(c))); }
            sb.append("\r\n");
            for (int r = 0; r < m.getRowCount(); r++) {
                for (int c = 0; c < m.getColumnCount(); c++) { if (c > 0) sb.append(';'); sb.append(celda(m.getValueAt(r, c))); }
                sb.append("\r\n");
            }
            w.write(sb.toString());
            Ui.info(padre, "Archivo guardado:\n" + f.getAbsolutePath());
        } catch (IOException e) {
            Ui.error(padre, "No se pudo guardar el archivo: " + e.getMessage());
        }
    }

    private static String celda(Object o) {
        String s = o == null ? "" : o.toString();
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
