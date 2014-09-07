package nars.gui.output;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.util.HashMap;
import java.util.Map;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JList;
import javax.swing.JMenuBar;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import nars.core.NAR;
import nars.entity.Task;
import nars.gui.NARControls;
import nars.gui.NARSwing;
import nars.gui.dock.DockingContent;
import nars.gui.dock.DockingRegionRoot;
import nars.io.Output;

/**
 * TODO queue outputs in non-displayed SwingLogPanel's into ArrayDeque without involving
 * any display methods
 * 
 * @author me
 */
public class MultiOutputPanel extends JPanel implements Output, HierarchyListener {

    DockingRegionRoot dock = new DockingRegionRoot();
    
    final long activityDecayPeriodNS = 100 * 1000 * 1000; //100ms
    
    
    public Map<Object, MultiModePanel> categories = new HashMap();
    private final MultiModePanel rootTaskPanel;
    private final NAR nar;    
    private final JPanel side;
    private final DefaultListModel categoriesListModel;
    private final JCategoryList categoriesList;

    public MultiOutputPanel(NARControls c) {
        super(new BorderLayout());

        JMenuBar menu = new JMenuBar();
        add(menu, BorderLayout.NORTH);
        
        JSplitPane innerPanel = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        
        
        
        
        this.nar = c.nar;

        categoriesListModel = new DefaultListModel();
        
        categoriesList = new JCategoryList(categoriesListModel);
        categoriesList.setBackground(Color.BLACK);

        side = new JPanel(new BorderLayout());
        side.add(categoriesList, BorderLayout.CENTER);
        
        add(innerPanel, BorderLayout.CENTER);
        
        innerPanel.add(new JScrollPane(side), 0);
        innerPanel.add(dock, 1);
        innerPanel.setDividerLocation(0.25f);

        

        rootTaskPanel = getModePanel("Root");//new MultiModePanel(nar, "Root");
        
        showCategory("Root");
    }
    
    protected void onShowing(boolean showing) {
        if (showing) {
            nar.addOutput(this);
        }
        else {
            nar.removeOutput(this);
        }
    }

    
    @Override
    public void output(Class channel, Object o) {
        Object category;
        if (o instanceof Task) {
            Task t = (Task) o;
            category = t.getRootTask();
        } else {
            category = null;
        }
                
        MultiModePanel p = getModePanel(category);
        if (p!=null)
            p.output(channel, o);
        
        decayActivities();
    }

    public MultiModePanel getModePanel(Object category) {
        if (category == null) {
            return categories.get("Root");
        } else {
            MultiModePanel p = categories.get(category);
            if (p == null) {
                
                p = new MultiModePanel(nar, category);
                JButton jc = p.newStatusButton();
                
                jc.addActionListener(new ActionListener() {
                    @Override public void actionPerformed(ActionEvent e) {
                        showCategory(category);
                    }                    
                });
                categories.put(category, p);
                                        
                categoriesListModel.addElement(jc);
            }
            return p;
        }
    }

    public JPanel showCategory(Object category) {
        String title = category.toString();
        
        MultiModePanel p = getModePanel(category);
        
        JMenuBar headerMenu = new JMenuBar();
        headerMenu.setOpaque(false);
        headerMenu.setBorder(null);        
        headerMenu.add(p.newMenu());
        
        //http://stackoverflow.com/questions/4702891/toggling-text-wrap-in-a-jtextpane        
        JPanel ioTextWrap = new JPanel(new BorderLayout());
        ioTextWrap.add(p);        

        JPanel x = new JPanel(new BorderLayout());
        x.add(new JScrollPane(ioTextWrap), BorderLayout.CENTER);
        x.validate();


        DockingContent cont = new DockingContent("view" + category, title, x);
        dock.addRootContent(cont);
        
        cont.getTab().setLabel(p.getLabel());
        cont.getTab().setFont(NARSwing.fontMono(15));
        cont.getTab().setMenuButton(headerMenu);
        
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                revalidate();
                repaint();
            }
        });
        
        return x;
    }

    /**
     * @author http://stackoverflow.com/questions/19766/how-do-i-make-a-list-with-checkboxes-in-java-swing
     */
    @SuppressWarnings("serial")
    public static class JCategoryList extends JList<JButton> {

        protected static Border noFocusBorder = new EmptyBorder(1, 1, 1, 1);

        public JCategoryList() {
            setCellRenderer(new CellRenderer());
            /*addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    int index = locationToIndex(e.getPoint());
                    if (index != -1) {
                        JButton checkbox = (JButton)getModel().getElementAt(index);
                        repaint();
                    }
                }
            });*/
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        }

        public JCategoryList(ListModel<JButton> model) {
            this();
            setModel(model);
        }

        protected class CellRenderer implements ListCellRenderer<JButton> {

            Font f = NARSwing.fontMono(16f);
            private JButton lastCellFocus;

            public Component getListCellRendererComponent(
                    JList<? extends JButton> list, JButton value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JButton b = value;

                
                b.setContentAreaFilled(false);
                b.setHorizontalTextPosition(JButton.LEFT);
                b.setHorizontalAlignment(JButton.LEFT);
                b.setForeground(Color.WHITE);
                b.setFocusPainted(false);
                b.setBorderPainted(false);                                
                b.setFont(f);
                
                if ((cellHasFocus) && (lastCellFocus!=b)) {
                    b.doClick();
                    lastCellFocus = b;
                }
                return b;
            }
            
            
        }
    }
    
    public void hideCategory(JPanel p) {
        dock.getDockingRoot().remove(p);
        
        validate();
        repaint();
    }
    
    @Override
    public void addNotify() {
        super.addNotify();
        addHierarchyListener(this);
    }

    @Override
    public void removeNotify() {
        removeHierarchyListener(this);
        super.removeNotify();
    }

    @Override
    public void hierarchyChanged(HierarchyEvent e) {
        if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
            boolean showing = isShowing();
            onShowing(showing);
        }
    }    

    long lastDecayed = 0;
    protected void decayActivities() {
        long now = System.nanoTime();
        if (now - lastDecayed > activityDecayPeriodNS) {
            for (MultiModePanel c : categories.values()) {
                c.decayActvity();
            }
            lastDecayed = now;
            categoriesList.repaint();
        }
    }
    
}