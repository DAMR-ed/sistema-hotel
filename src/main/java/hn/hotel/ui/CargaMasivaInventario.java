package hn.hotel.ui;

import hn.hotel.app.Sesion;
import hn.hotel.db.Db;
import hn.hotel.service.Almacenes;
import hn.hotel.service.Bitacora;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.List;

/**
 * Carga masiva de inventario desde Excel (.xlsx) o CSV: por almacén, con código (o nombre) del insumo y la cantidad.
 * Todo queda en el kardex como un ajuste con su almacén y su motivo.
 */
public final class CargaMasivaInventario {
    private CargaMasivaInventario() {}

    private record Fila(int linea, Integer idInsumo, String codigo, String nombre, String unidad, BigDecimal actual, BigDecimal archivo,
                        BigDecimal diferencia, BigDecimal costo, String resultado, boolean ok) {}

    private static final String[] MODOS = {"Conteo físico: la cantidad del archivo ES la existencia", "Carga (entrada): sumar la cantidad", "Descarga (salida): restar la cantidad"};

    public static void mostrar(Component padre, Runnable alTerminar) {
        JComboBox<AccionesHotel.Opcion> alm = AccionesHotel.combo(Db.query("SELECT id_almacen, nombre FROM almacenes WHERE activo = 1 ORDER BY es_principal DESC, nombre"), "id_almacen", "nombre", false);
        JComboBox<String> modo = new JComboBox<>(MODOS);
        JCheckBox inicial = new JCheckBox("Es el inventario inicial");
        JTextField motivo = new JTextField("Carga masiva", 24);
        JLabel archivo = new JLabel("Ningún archivo elegido");
        JLabel resumen = new JLabel(" ");
        DefaultTableModel m = Ui.modelo("Fila", "Código", "Insumo", "Unidad", "Existencia actual", "Archivo", "Diferencia", "Resultado");
        JTable t = Ui.tabla(m);
        Ui.alinearDerecha(t, 4, 5, 6);
        List<Fila>[] cargadas = new List[]{List.of()};
        File[] elegido = {null};

        Runnable analizar = () -> {
            m.setRowCount(0);
            cargadas[0] = List.of();
            if (elegido[0] == null) return;
            try {
                cargadas[0] = analizar(elegido[0], AccionesHotel.idDe(alm), modo.getSelectedIndex());
            } catch (Exception e) {
                resumen.setText("No se pudo leer el archivo: " + (e.getMessage() == null ? e : e.getMessage()));
                return;
            }
            int ok = 0, cambios = 0, err = 0;
            for (Fila f : cargadas[0]) {
                m.addRow(new Object[]{f.linea(), f.codigo(), f.nombre(), f.unidad(), f.actual() == null ? "" : Ui.numero(f.actual()), f.archivo() == null ? "" : Ui.numero(f.archivo()),
                        f.diferencia() == null ? "" : Ui.numero(f.diferencia()), f.resultado()});
                if (f.ok()) { ok++; if (f.diferencia().signum() != 0) cambios++; } else err++;
            }
            resumen.setText(ok + " filas válidas (" + cambios + " con cambios), " + err + " con problemas que se omitirán.");
        };
        JButton elegir = Ui.boton("Elegir archivo (Excel o CSV)...", () -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel o CSV", "xlsx", "xlsm", "csv", "txt"));
            if (fc.showOpenDialog(padre) != JFileChooser.APPROVE_OPTION) return;
            elegido[0] = fc.getSelectedFile();
            archivo.setText(elegido[0].getName());
            analizar.run();
        });
        JButton plantilla = Ui.boton("Descargar plantilla...", () -> plantilla(padre, AccionesHotel.idDe(alm)));
        alm.addActionListener(e -> analizar.run());
        modo.addActionListener(e -> { inicial.setEnabled(modo.getSelectedIndex() != 2); analizar.run(); });

        Campos.Formulario cab = new Campos.Formulario();
        cab.agregar("Almacén:", alm).agregar("Tipo de carga:", modo).agregar("", inicial).agregar("Motivo:", motivo)
           .nota("El archivo necesita una columna «Código» (o «Nombre») y una columna de cantidad («Existencia nueva» o «Cantidad»), en la unidad base del insumo "
               + "(g, ml o unidades). Opcional: «Costo» (solo se usa en insumos sin existencia ni costo).");
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.add(cab, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(820, 260));
        p.add(sp, BorderLayout.CENTER);
        JPanel pie = new JPanel(new BorderLayout(4, 4));
        pie.add(Ui.fila(elegir, plantilla, archivo), BorderLayout.NORTH);
        pie.add(resumen, BorderLayout.SOUTH);
        p.add(pie, BorderLayout.SOUTH);

        boolean ok = Campos.dialogo(padre, "Carga masiva de inventario  (Aceptar aplica los cambios)", p, () -> {
            List<Fila> validas = new ArrayList<>();
            for (Fila f : cargadas[0]) if (f.ok() && f.diferencia().signum() != 0) validas.add(f);
            if (validas.isEmpty()) throw new IllegalArgumentException("No hay cambios que aplicar. Elija un archivo con cantidades distintas a la existencia actual.");
            if (motivo.getText().trim().length() < 3) throw new IllegalArgumentException("Escriba el motivo.");
            if (!Ui.confirmar(padre, "Se aplicarán " + validas.size() + " ajustes en el almacén «" + alm.getSelectedItem() + "». ¿Continuar?")) return false;
            int idAlm = AccionesHotel.idDe(alm), md = modo.getSelectedIndex();
            String tipo = md == 2 ? "MERMA" : inicial.isSelected() ? "INVENTARIO_INICIAL" : "AJUSTE";
            String nota = "Carga masiva: " + motivo.getText().trim();
            Db.transaccion(tx -> {
                Almacenes.usar(tx, idAlm);
                for (Fila f : validas) {
                    if (f.costo() != null && f.costo().signum() > 0 && f.diferencia().signum() > 0) {
                        Map<String, Object> i = tx.uno("SELECT stock_actual, costo_promedio FROM insumos WHERE id_insumo = ? FOR UPDATE", f.idInsumo());
                        if (Db.dec(i.get("stock_actual")).signum() <= 0 && Db.dec(i.get("costo_promedio")).signum() == 0)
                            tx.exec("UPDATE insumos SET costo_promedio = ? WHERE id_insumo = ?", f.costo(), f.idInsumo());
                    }
                    tx.call("{CALL sp_ajustar_inventario(?,?,?,?,?)}", f.idInsumo(), f.diferencia(), tipo, nota, Sesion.idUsuario);
                }
                Bitacora.registrar("CARGA_MASIVA", "almacen", idAlm, validas.size() + " ajustes (" + tipo + ") " + motivo.getText().trim());
                return null;
            });
            Ui.info(padre, "Listo: se aplicaron " + validas.size() + " ajustes. Puede verlos en el Kardex de cada insumo.");
            return true;
        });
        if (ok && alTerminar != null) alTerminar.run();
    }

    // ------------------------------------------------------------------ análisis del archivo
    private static List<Fila> analizar(File f, int idAlm, int modo) throws Exception {
        List<List<String>> filas = LectorTabla.leer(f);
        if (filas.size() < 2) throw new IllegalArgumentException("El archivo no tiene datos (solo se encontró " + filas.size() + " fila).");
        List<String> enc = filas.get(0);
        int cCod = -1, cNom = -1, cQty = -1, cCosto = -1, cQtyDebil = -1;
        for (int i = 0; i < enc.size(); i++) {
            String h = norm(enc.get(i));
            if (cCod < 0 && (h.equals("codigo") || h.equals("code") || h.equals("cod") || h.equals("sku"))) cCod = i;
            else if (cNom < 0 && (h.equals("nombre") || h.equals("insumo") || h.equals("producto") || h.equals("descripcion"))) cNom = i;
            else if (cCosto < 0 && h.startsWith("costo")) cCosto = i;
            else if (cQty < 0 && (h.contains("nueva") || h.contains("cantidad") || h.contains("conteo") || h.contains("contad") || h.contains("fisic"))) cQty = i;
            else if (cQtyDebil < 0 && h.startsWith("existencia") && !h.contains("actual")) cQtyDebil = i;
        }
        if (cQty < 0) cQty = cQtyDebil;
        if (cCod < 0 && cNom < 0) throw new IllegalArgumentException("No encontré una columna «Código» ni «Nombre» en la primera fila.");
        if (cQty < 0) throw new IllegalArgumentException("No encontré la columna de cantidad («Existencia nueva» o «Cantidad»).");

        Map<String, Map<String, Object>> porCodigo = new HashMap<>(), porNombre = new HashMap<>();
        for (Map<String, Object> i : Db.query("SELECT id_insumo, codigo, nombre, unidad_base FROM insumos WHERE activo = 1")) {
            if (i.get("codigo") != null && !Db.str(i.get("codigo")).isBlank()) porCodigo.put(Db.str(i.get("codigo")).trim().toLowerCase(), i);
            porNombre.put(Db.str(i.get("nombre")).trim().toLowerCase(), i);
        }
        Map<Integer, BigDecimal> enAlm = new HashMap<>();
        for (Map<String, Object> e : Db.query("SELECT id_insumo, stock FROM existencias WHERE id_almacen = ?", idAlm)) enAlm.put(Db.ent(e.get("id_insumo")), Db.dec(e.get("stock")));

        List<Fila> res = new ArrayList<>();
        Set<Integer> vistos = new HashSet<>();
        for (int r = 1; r < filas.size(); r++) {
            List<String> x = filas.get(r);
            String cod = cCod >= 0 ? dato(x, cCod) : "", nom = cNom >= 0 ? dato(x, cNom) : "", q = dato(x, cQty), cs = cCosto >= 0 ? dato(x, cCosto) : "";
            if (cod.isEmpty() && nom.isEmpty()) continue;
            if (q.isEmpty()) continue;   // fila sin cantidad: se ignora
            Map<String, Object> ins = !cod.isEmpty() ? porCodigo.get(cod.toLowerCase()) : null;
            if (ins == null && !nom.isEmpty()) ins = porNombre.get(nom.toLowerCase());
            if (ins == null) { res.add(new Fila(r + 1, null, cod, nom, "", null, null, null, null, "No existe ese código ni nombre", false)); continue; }
            int id = Db.ent(ins.get("id_insumo"));
            String nombre = Db.str(ins.get("nombre")), un = Db.str(ins.get("unidad_base"));
            BigDecimal actual = enAlm.getOrDefault(id, BigDecimal.ZERO), cant, costo = null;
            try { cant = numero(q); if (!cs.isEmpty()) costo = numero(cs); }
            catch (NumberFormatException e) { res.add(new Fila(r + 1, id, cod, nombre, un, actual, null, null, null, "Cantidad no válida: " + q, false)); continue; }
            if (cant.signum() < 0 || (modo != 0 && cant.signum() == 0)) { res.add(new Fila(r + 1, id, cod, nombre, un, actual, cant, null, null, modo == 0 ? "La existencia no puede ser negativa" : "La cantidad debe ser mayor que cero", false)); continue; }
            if (!vistos.add(id)) { res.add(new Fila(r + 1, id, cod, nombre, un, actual, cant, null, null, "Insumo repetido en el archivo", false)); continue; }
            BigDecimal dif = modo == 0 ? cant.subtract(actual) : modo == 1 ? cant : cant.negate();
            res.add(new Fila(r + 1, id, cod, nombre, un, actual, cant, dif.setScale(3, java.math.RoundingMode.HALF_UP), costo, dif.signum() == 0 ? "Sin cambio" : "OK", true));
        }
        if (res.isEmpty()) throw new IllegalArgumentException("No hay filas con cantidad en el archivo.");
        return res;
    }

    private static String dato(List<String> x, int i) { return i < x.size() ? x.get(i).trim() : ""; }

    private static String norm(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toLowerCase();
    }

    /** Acepta 1500.5, 1,500.5, 1.500,5 y 1500,5. */
    static BigDecimal numero(String s) {
        String t = s.replace(" ", "").replace(" ", "");
        int p = t.lastIndexOf('.'), c = t.lastIndexOf(',');
        if (p >= 0 && c >= 0) t = p > c ? t.replace(",", "") : t.replace(".", "").replace(',', '.');
        else if (c >= 0) t = t.replace(',', '.');
        return new BigDecimal(t);
    }

    // ------------------------------------------------------------------ plantilla
    private static void plantilla(Component padre, int idAlm) {
        DefaultTableModel m = Ui.modelo("Codigo", "Nombre", "Unidad", "Existencia actual", "Existencia nueva", "Costo");
        for (Map<String, Object> i : Db.query("SELECT i.codigo, i.nombre, i.unidad_base, COALESCE(e.stock, 0) AS stock FROM insumos i "
                + "LEFT JOIN existencias e ON e.id_insumo = i.id_insumo AND e.id_almacen = ? WHERE i.activo = 1 ORDER BY i.nombre", idAlm))
            m.addRow(new Object[]{Db.str(i.get("codigo")), i.get("nombre"), i.get("unidad_base"), Db.dec(i.get("stock")).stripTrailingZeros().toPlainString(), "", ""});
        Exportar.csv(padre, m, "plantilla_inventario");
    }
}
