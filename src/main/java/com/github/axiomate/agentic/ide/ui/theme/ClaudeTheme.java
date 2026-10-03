package com.github.axiomate.agentic.ide.ui.theme;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Claude Code inspired themes: warm neutral surfaces, the terracotta "Claude" accent, and Claude Code's
 * semantic colors for success / error / warning, including colorblind-friendly (daltonized) variants.
 */
public enum ClaudeTheme {
    DARK("Claude Dark", true, false),
    LIGHT("Claude Light", false, false),
    DARK_COLORBLIND("Claude Dark (Colorblind)", true, true),
    LIGHT_COLORBLIND("Claude Light (Colorblind)", false, true);

    private final String displayName;
    private final boolean dark;
    private final boolean daltonized;

    ClaudeTheme(String displayName, boolean dark, boolean daltonized) {
        this.displayName = displayName;
        this.dark = dark;
        this.daltonized = daltonized;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isDark() {
        return dark;
    }

    public static Optional<ClaudeTheme> byName(String name) {
        for (ClaudeTheme t : values()) {
            if (t.displayName.equalsIgnoreCase(name)) return Optional.of(t);
        }
        return Optional.empty();
    }

    // Semantic colors -------------------------------------------------------

    /** The Claude brand accent: selection marks, primary buttons, spinner. */
    public Color claude() {
        return dark ? new Color(0xD97757) : new Color(0xC6613F);
    }

    /** Secondary accent (Claude Code's "permission"/suggestion blue-violet). */
    public Color permission() {
        return dark ? new Color(0xB1B9F9) : new Color(0x5769F7);
    }

    public Color success() {
        if (daltonized) return dark ? new Color(0x3399FF) : new Color(0x0066CC);
        return dark ? new Color(0x4EBA65) : new Color(0x2C7A39);
    }

    public Color error() {
        if (daltonized) return dark ? new Color(0xFF6666) : new Color(0xCC0000);
        return dark ? new Color(0xFF6B80) : new Color(0xAB2B3F);
    }

    public Color warning() {
        if (daltonized) return dark ? new Color(0xFFCC00) : new Color(0xFF9900);
        return dark ? new Color(0xFFC107) : new Color(0x966C1E);
    }

    // Surfaces --------------------------------------------------------------

    private String background() {
        return dark ? "#262624" : "#FAF9F5";
    }

    private String surface() {
        return dark ? "#30302E" : "#FFFFFF";
    }

    private String sunken() {
        return dark ? "#1F1E1D" : "#F0EEE6";
    }

    private String foreground() {
        return dark ? "#ECEAE3" : "#141413";
    }

    private String muted() {
        return dark ? "#9C9A92" : "#73726C";
    }

    private String border() {
        return dark ? "#3E3E38" : "#DEDCD1";
    }

    private String selection() {
        return dark ? "#4A3A33" : "#F3DED5";
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * FlatLaf defaults (variables and component keys) layered over FlatDarkLaf / FlatLightLaf.
     */
    public Map<String, String> lafDefaults() {
        String accent = hex(claude());
        Map<String, String> d = new LinkedHashMap<>();
        d.put("@background", background());
        d.put("@foreground", foreground());
        d.put("@disabledForeground", muted());
        d.put("@accentColor", accent);
        d.put("@selectionBackground", selection());
        d.put("@selectionForeground", foreground());
        d.put("@selectionInactiveBackground", dark ? "#3A3936" : "#ECE9E0");

        for (String k : new String[]{"Panel.background", "RootPane.background", "Viewport.background",
                "ScrollPane.background", "TabbedPane.background", "SplitPane.background", "OptionPane.background",
                "MenuBar.background", "ToolBar.background", "Label.background"}) {
            d.put(k, background());
        }
        for (String k : new String[]{"TextField.background", "PasswordField.background", "FormattedTextField.background",
                "TextArea.background", "TextPane.background", "EditorPane.background", "Spinner.background",
                "ComboBox.background", "ComboBox.buttonBackground", "Button.background", "ToggleButton.background",
                "PopupMenu.background", "Menu.background", "MenuItem.background", "CheckBoxMenuItem.background",
                "RadioButtonMenuItem.background", "ToolTip.background"}) {
            d.put(k, surface());
        }
        for (String k : new String[]{"List.background", "Tree.background", "Table.background", "TableHeader.background"}) {
            d.put(k, sunken());
        }
        for (String k : new String[]{"Label.foreground", "TextField.foreground", "TextArea.foreground", "List.foreground",
                "Tree.foreground", "Table.foreground", "Menu.foreground", "MenuItem.foreground", "Button.foreground",
                "CheckBox.foreground", "RadioButton.foreground", "ComboBox.foreground", "TabbedPane.foreground"}) {
            d.put(k, foreground());
        }
        d.put("Label.disabledForeground", muted());
        d.put("Component.borderColor", border());
        d.put("Component.disabledBorderColor", border());
        d.put("Separator.foreground", border());
        d.put("SplitPaneDivider.draggingColor", accent);
        d.put("Component.focusColor", accent);
        d.put("Component.focusedBorderColor", accent);
        d.put("Button.default.background", accent);
        d.put("Button.default.foreground", "#FFFFFF");
        d.put("Button.default.focusedBorderColor", accent);
        d.put("Button.borderColor", border());
        d.put("CheckBox.icon.selectedBackground", accent);
        d.put("CheckBox.icon.selectedBorderColor", accent);
        d.put("CheckBox.icon.checkmarkColor", "#FFFFFF");
        d.put("ProgressBar.foreground", accent);
        d.put("Slider.thumbColor", accent);
        d.put("Slider.trackValueColor", accent);
        d.put("TabbedPane.underlineColor", accent);
        d.put("TabbedPane.inactiveUnderlineColor", muted());
        d.put("TabbedPane.hoverColor", dark ? "#33332F" : "#F0EEE6");
        d.put("List.selectionBackground", selection());
        d.put("Tree.selectionBackground", selection());
        d.put("Table.selectionBackground", selection());
        d.put("List.selectionForeground", foreground());
        d.put("Tree.selectionForeground", foreground());
        d.put("Table.selectionForeground", foreground());
        d.put("TextComponent.selectionBackground", dark ? "#5A3F33" : "#F5CDBD");
        d.put("Table.gridColor", border());
        d.put("ScrollBar.thumb", dark ? "#4A4944" : "#CFCCC1");
        d.put("ScrollBar.hoverThumbColor", dark ? "#5C5A54" : "#B9B6AA");
        d.put("ScrollBar.track", background());
        d.put("ToolTip.foreground", foreground());
        return d;
    }
}
