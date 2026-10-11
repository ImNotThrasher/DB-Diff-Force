package studio.dbdiff;

import java.awt.*;

/** Keeps branding and actions on one row, moving actions below when space is tight. */
final class HeaderLayout implements LayoutManager {
    private int lastHeight;
    @Override public void addLayoutComponent(String name, Component component) { }
    @Override public void removeLayoutComponent(Component component) { }

    @Override public Dimension preferredLayoutSize(Container parent) {
        if (parent.getComponentCount() < 2) return new Dimension();
        Insets insets = parent.getInsets();
        Dimension brand = parent.getComponent(0).getPreferredSize();
        Dimension actions = parent.getComponent(1).getPreferredSize();
        int width = brand.width + actions.width + 16;
        boolean stacked = parent.getWidth() > 0 && parent.getWidth() - insets.left - insets.right < width;
        int height = stacked ? brand.height + actions.height + 6 : Math.max(brand.height, actions.height);
        return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
    }

    @Override public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, preferredLayoutSize(parent).height); }

    @Override public void layoutContainer(Container parent) {
        if (parent.getComponentCount() < 2) return;
        Insets insets = parent.getInsets();
        Component brand = parent.getComponent(0), actions = parent.getComponent(1);
        Dimension a = brand.getPreferredSize(), b = actions.getPreferredSize();
        int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
        if (width < a.width + b.width + 16) {
            brand.setBounds(insets.left, insets.top, width, a.height);
            actions.setBounds(insets.left, insets.top + a.height + 6, width, b.height);
        } else {
            int height = Math.max(a.height, b.height);
            brand.setBounds(insets.left, insets.top + (height - a.height) / 2, a.width, a.height);
            actions.setBounds(insets.left + width - b.width, insets.top + (height - b.height) / 2, b.width, b.height);
        }
        int height = preferredLayoutSize(parent).height;
        if (lastHeight != height) { lastHeight = height; parent.invalidate(); parent.revalidate(); }
    }
}
