package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.Map;

/**
 * Validación opcional de RTN / identidad contra el SAR.
 * La consulta del SAR exige un captcha (comprobación de seguridad), que el sistema no puede ni debe saltarse:
 * el botón abre la página oficial con el número ya copiado, el usuario resuelve el captcha y confirma el nombre.
 * Una vez confirmado queda guardado y la próxima factura con ese número se completa sola.
 */
public final class RtnValidacion {
    private RtnValidacion() {}

    public static final String URL_SAR = "https://www.sar.gob.hn/registro-tributario-nacional-rtn/";

    /** Solo dígitos. */
    public static String limpiar(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }

    public static Map<String, Object> buscar(String numero) {
        String n = limpiar(numero);
        if (n.isEmpty()) return null;
        try { return Db.uno("SELECT nombre, DATE_FORMAT(validado_en, '%d/%m/%Y') AS fecha FROM rtn_validados WHERE numero = ?", n); }
        catch (RuntimeException e) { return null; }   // el script 08 aún no se ha ejecutado
    }

    /** Panel con el botón "Validar en SAR" y el estado, ligado a los campos de RTN y nombre de la factura. */
    public static JPanel panel(Component padre, JTextField rtn, JTextField nombre) {
        JLabel estado = new JLabel(" ");
        Runnable refrescar = () -> {
            String n = limpiar(rtn.getText());
            if (n.isEmpty()) { estado.setText(" "); return; }
            Map<String, Object> v = buscar(n);
            if (v != null) {
                estado.setForeground(new Color(0x2E9E5B));
                estado.setText("✔ Validado en SAR el " + v.get("fecha") + ": " + v.get("nombre"));
                if (nombre.getText().isBlank() || nombre.getText().equalsIgnoreCase("Consumidor final")) nombre.setText(Db.str(v.get("nombre")));
            } else {
                estado.setForeground(Tema.TEXTO_SUAVE);
                estado.setText(n.length() == 14 || n.length() == 13 ? "Sin validar (opcional)" : "RTN: 14 dígitos · Identidad: 13 dígitos");
            }
        };
        rtn.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refrescar.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refrescar.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refrescar.run(); }
        });
        JButton validar = Ui.boton("Validar en SAR", () -> {
            String n = limpiar(rtn.getText());
            if (n.length() != 14 && n.length() != 13) { Ui.info(padre, "Escriba un RTN (14 dígitos) o una identidad (13 dígitos)."); return; }
            if (buscar(n) != null) { refrescar.run(); return; }
            if (verificar(padre, n)) { rtn.setText(n); refrescar.run(); }
        });
        refrescar.run();
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        p.add(validar);
        p.add(estado);
        return p;
    }

    /** Abre el SAR, espera la confirmación del usuario y guarda el resultado. Devuelve true si quedó validado. */
    private static boolean verificar(Component padre, String numero) {
        try {
            return verificarIntegrado(padre, numero);
        } catch (LinkageError | RuntimeException e) {
            // JavaFX no disponible en esta instalación: se usa el navegador externo
            return verificarExterno(padre, numero);
        }
    }

    /** La página del SAR se abre DENTRO del sistema: el cajero resuelve el captcha ahí mismo y confirma el nombre. */
    private static boolean verificarIntegrado(Component padre, String numero) {
        javafx.embed.swing.JFXPanel web = new javafx.embed.swing.JFXPanel();
        final javafx.scene.web.WebEngine[] motor = new javafx.scene.web.WebEngine[1];
        javafx.application.Platform.setImplicitExit(false);
        javafx.application.Platform.runLater(() -> {
            javafx.scene.web.WebView v = new javafx.scene.web.WebView();
            motor[0] = v.getEngine();
            motor[0].load(URL_SAR);
            web.setScene(new javafx.scene.Scene(v));
        });
        web.setPreferredSize(new Dimension(920, 520));

        JTextField nom = new JTextField(30);
        JLabel ayuda = new JLabel("<html>Número a consultar: <b>" + numero + "</b> (ya copiado: péguelo con Cmd/Ctrl+V). "
                + "Elija el tipo de búsqueda, resuelva la comprobación de seguridad y consulte.</html>");
        JButton leer = Ui.boton("Leer nombre de la página", () -> {
            final String[] texto = new String[1];
            try {
                java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
                javafx.application.Platform.runLater(() -> {
                    try { Object r = motor[0].executeScript("document.body.innerText"); texto[0] = r == null ? "" : r.toString(); }
                    catch (RuntimeException e) { texto[0] = ""; }
                    l.countDown();
                });
                l.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String n = extraerNombre(texto[0]);
            if (n == null) Ui.info(padre, "No pude leer el nombre automáticamente. Escríbalo tal como lo muestra el SAR.");
            else nom.setText(n);
        });
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        abajo.add(leer);
        abajo.add(new JLabel("Nombre:"));
        abajo.add(nom);
        JPanel todo = new JPanel(new BorderLayout(0, 6));
        todo.add(ayuda, BorderLayout.NORTH);
        todo.add(web, BorderLayout.CENTER);
        todo.add(abajo, BorderLayout.SOUTH);
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(numero), null); } catch (Exception ignorado) { }
        return Campos.dialogo(padre, "Validar " + numero + " en el SAR", todo, () -> guardar(numero, nom.getText().trim()));
    }

    /** Busca en el texto de la página la línea «Nombre / Razón social / Contribuyente». */
    static String extraerNombre(String texto) {
        if (texto == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?im)^\\s*(?:nombre(?: completo)?|raz[oó]n social|contribuyente|nombre o raz[oó]n social)\\s*[:\\-]?\\s*(.{3,120})$").matcher(texto);
        if (m.find()) return m.group(1).trim();
        m = java.util.regex.Pattern.compile("(?is)(?:nombre(?: completo)?|raz[oó]n social|contribuyente)\\s*[:\\-]?\\s*\\n\\s*([^\\n]{3,120})").matcher(texto);
        return m.find() ? m.group(1).trim() : null;
    }

    private static boolean guardar(String numero, String nombre) {
        if (nombre.length() < 3) throw new IllegalArgumentException("Escriba el nombre que mostró el SAR.");
        Db.exec("INSERT INTO rtn_validados (numero, nombre, id_usuario) VALUES (?,?,?) "
              + "ON DUPLICATE KEY UPDATE nombre = VALUES(nombre), validado_en = NOW(), id_usuario = VALUES(id_usuario)", numero, nombre, Sesion.idUsuario);
        Bitacora.registrar("VALIDAR_RTN", "rtn_validados", numero, nombre);
        return true;
    }

    private static boolean verificarExterno(Component padre, String numero) {
        try { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(numero), null); } catch (Exception ignorado) { }
        try { Desktop.getDesktop().browse(java.net.URI.create(URL_SAR)); }
        catch (Exception e) { Ui.info(padre, "No se pudo abrir el navegador. Entre manualmente a:\n" + URL_SAR); }

        JTextField nom = new JTextField(26);
        Campos.Formulario f = new Campos.Formulario()
                .nota("Se abrió la página del SAR y el número " + numero + " ya está copiado.")
                .nota("1) Elija «por RTN» o «por identificación»  2) Resuelva la comprobación de seguridad  3) Pegue el número y consulte.")
                .agregar("Nombre que muestra el SAR:", nom)
                .nota("Escriba el nombre tal como aparece. Al confirmar, queda guardado y la próxima vez se completa solo. Si el SAR no encuentra el número, cancele.");
        return Campos.dialogo(padre, "Validar " + numero + " en el SAR", f, () -> {
            String nombre = nom.getText().trim();
            if (nombre.length() < 3) throw new IllegalArgumentException("Escriba el nombre que mostró el SAR.");
            Db.exec("INSERT INTO rtn_validados (numero, nombre, id_usuario) VALUES (?,?,?) "
                  + "ON DUPLICATE KEY UPDATE nombre = VALUES(nombre), validado_en = NOW(), id_usuario = VALUES(id_usuario)", numero, nombre, Sesion.idUsuario);
            Bitacora.registrar("VALIDAR_RTN", "rtn_validados", numero, nombre);
            return true;
        });
    }
}
