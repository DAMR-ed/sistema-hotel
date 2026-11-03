package hn.hotel.ui;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lee una hoja de Excel (.xlsx) o un CSV (; , o tabulador) y la devuelve como filas de texto. Sin librerías externas. */
public final class LectorTabla {
    private LectorTabla() {}

    public static List<List<String>> leer(File f) throws Exception {
        String n = f.getName().toLowerCase();
        if (n.endsWith(".xlsx") || n.endsWith(".xlsm")) return xlsx(f);
        if (n.endsWith(".xls")) throw new IllegalArgumentException("El formato .xls (Excel antiguo) no se puede leer. Guarde el archivo como .xlsx o como CSV.");
        return csv(f);
    }

    // ------------------------------------------------------------------ CSV
    static List<List<String>> csv(File f) throws IOException {
        byte[] b = Files.readAllBytes(f.toPath());
        String t = new String(b, StandardCharsets.UTF_8);
        if (t.indexOf('�') >= 0) t = new String(b, Charset.forName("windows-1252"));
        if (t.startsWith("﻿")) t = t.substring(1);
        String primera = t.lines().findFirst().orElse("");
        char sep = count(primera, ';') >= count(primera, ',') && count(primera, ';') >= count(primera, '\t') ? ';' : count(primera, '\t') > count(primera, ',') ? '\t' : ',';
        List<List<String>> filas = new ArrayList<>();
        List<String> fila = new ArrayList<>();
        StringBuilder c = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (comillas) {
                if (ch == '"') { if (i + 1 < t.length() && t.charAt(i + 1) == '"') { c.append('"'); i++; } else comillas = false; }
                else c.append(ch);
            } else if (ch == '"') comillas = true;
            else if (ch == sep) { fila.add(c.toString().trim()); c.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < t.length() && t.charAt(i + 1) == '\n') i++;
                fila.add(c.toString().trim()); c.setLength(0);
                if (!vacia(fila)) filas.add(fila);
                fila = new ArrayList<>();
            } else c.append(ch);
        }
        fila.add(c.toString().trim());
        if (!vacia(fila)) filas.add(fila);
        return filas;
    }

    private static int count(String s, char c) { int n = 0; for (char x : s.toCharArray()) if (x == c) n++; return n; }
    private static boolean vacia(List<String> f) { for (String s : f) if (!s.isEmpty()) return false; return true; }

    // ------------------------------------------------------------------ XLSX
    static List<List<String>> xlsx(File f) throws Exception {
        try (ZipFile z = new ZipFile(f)) {
            List<String> compartidas = new ArrayList<>();
            ZipEntry ss = z.getEntry("xl/sharedStrings.xml");
            if (ss != null) {
                Document d = parse(z.getInputStream(ss));
                NodeList sis = d.getElementsByTagName("si");
                for (int i = 0; i < sis.getLength(); i++) {
                    StringBuilder sb = new StringBuilder();
                    NodeList ts = ((Element) sis.item(i)).getElementsByTagName("t");
                    for (int k = 0; k < ts.getLength(); k++) sb.append(ts.item(k).getTextContent());
                    compartidas.add(sb.toString());
                }
            }
            String hoja = null;
            List<String> hojas = new ArrayList<>();
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                String nm = en.nextElement().getName();
                if (nm.matches("xl/worksheets/sheet\\d+\\.xml")) hojas.add(nm);
            }
            Collections.sort(hojas, Comparator.comparingInt((String s) -> Integer.parseInt(s.replaceAll("\\D", ""))));
            if (hojas.isEmpty()) throw new IllegalArgumentException("El archivo de Excel no tiene hojas.");
            hoja = hojas.get(0);
            Document d = parse(z.getInputStream(z.getEntry(hoja)));
            List<List<String>> filas = new ArrayList<>();
            NodeList rows = d.getElementsByTagName("row");
            for (int i = 0; i < rows.getLength(); i++) {
                List<String> fila = new ArrayList<>();
                NodeList cs = ((Element) rows.item(i)).getElementsByTagName("c");
                for (int k = 0; k < cs.getLength(); k++) {
                    Element c = (Element) cs.item(k);
                    int col = columna(c.getAttribute("r"));
                    while (fila.size() < col) fila.add("");
                    String tipo = c.getAttribute("t"), valor = "";
                    NodeList vs = c.getElementsByTagName("v");
                    if (tipo.equals("inlineStr")) {
                        NodeList ts = c.getElementsByTagName("t");
                        StringBuilder sb = new StringBuilder();
                        for (int q = 0; q < ts.getLength(); q++) sb.append(ts.item(q).getTextContent());
                        valor = sb.toString();
                    } else if (vs.getLength() > 0) {
                        valor = vs.item(0).getTextContent();
                        if (tipo.equals("s")) { int ix = Integer.parseInt(valor.trim()); valor = ix < compartidas.size() ? compartidas.get(ix) : ""; }
                    }
                    fila.add(valor.trim());
                }
                if (!vacia(fila)) filas.add(fila);
            }
            return filas;
        }
    }

    private static int columna(String ref) {
        int n = 0;
        for (char ch : ref.toCharArray()) { if (!Character.isLetter(ch)) break; n = n * 26 + (Character.toUpperCase(ch) - 'A' + 1); }
        return Math.max(0, n - 1);
    }

    private static Document parse(InputStream is) throws Exception {
        DocumentBuilderFactory fab = DocumentBuilderFactory.newInstance();
        fab.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder b = fab.newDocumentBuilder();
        try (is) { return b.parse(is); }
    }
}
