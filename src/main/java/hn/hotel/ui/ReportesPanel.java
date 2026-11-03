package hn.hotel.ui;

import hn.hotel.app.Sesion;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;

/** Reportes: árbol de categorías a la izquierda y el visor dinámico (filtros, agrupar, totales, gráfico) a la derecha. */
public class ReportesPanel extends JPanel implements Actualizable {
    private final JTree arbol;
    private final ReporteDinamico visor = new ReporteDinamico();
    private boolean primeraVez = true;

    public ReportesPanel() {
        setLayout(new BorderLayout());
        DefaultMutableTreeNode raiz = new DefaultMutableTreeNode("Reportes");
        for (String cat : CatalogoReportes.CATEGORIAS) {
            DefaultMutableTreeNode nodo = new DefaultMutableTreeNode(cat);
            for (ReporteDef r : CatalogoReportes.todos())
                if (r.categoria().equals(cat) && (!r.soloAdmin() || Sesion.esAdmin())) nodo.add(new DefaultMutableTreeNode(r));
            if (nodo.getChildCount() > 0) raiz.add(nodo);
        }
        arbol = new JTree(new DefaultTreeModel(raiz));
        arbol.setRootVisible(false);
        arbol.setRowHeight(28);
        arbol.setShowsRootHandles(true);
        for (int i = 0; i < arbol.getRowCount(); i++) arbol.expandRow(i);
        arbol.addTreeSelectionListener(e -> {
            TreePath p = e.getNewLeadSelectionPath();
            if (p == null) return;
            Object o = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
            if (o instanceof ReporteDef d) visor.abrir(d, null);
        });
        JScrollPane sp = new JScrollPane(arbol);
        sp.setPreferredSize(new Dimension(290, 0));
        sp.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Tema.BORDE));
        add(sp, BorderLayout.WEST);
        add(visor, BorderLayout.CENTER);
    }

    @Override public void alMostrar() {
        if (!primeraVez) return;
        primeraVez = false;
        for (int i = 0; i < arbol.getRowCount(); i++) {
            Object o = ((DefaultMutableTreeNode) arbol.getPathForRow(i).getLastPathComponent()).getUserObject();
            if (o instanceof ReporteDef) { arbol.setSelectionRow(i); break; }
        }
    }
}
