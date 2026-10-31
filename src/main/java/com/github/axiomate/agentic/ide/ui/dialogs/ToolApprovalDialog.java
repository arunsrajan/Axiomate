package com.github.axiomate.agentic.ide.ui.dialogs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.axiomate.agentic.ide.agent.ToolPermissions;
import com.github.axiomate.agentic.ide.ui.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Manual mode: asks whether the agent may run one tool call, showing exactly what it will run.
 */
public class ToolApprovalDialog extends JDialog {

    static final int MAX_SHOWN_CHARS = 6_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolPermissions.Answer answer = ToolPermissions.Answer.deny("closed without an answer");
    private final JTextField reasonField = new JTextField();

    public ToolApprovalDialog(Window owner, ToolPermissions.Request request) {
        super(owner, "Allow " + request.toolName() + "?", ModalityType.APPLICATION_MODAL);
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(new EmptyBorder(14, 16, 12, 16));

        JLabel title = new JLabel("<html><b>The agent wants to run " + escape(request.toolName()) + "</b>"
                + "<br><span style='color:gray'>Session: " + escape(request.sessionName()) + " · Manual mode</span></html>");
        title.setFont(UIUtils.uiFont(Font.PLAIN, 13f));
        root.add(title, BorderLayout.NORTH);

        JTextArea args = new JTextArea(display(request.arguments()));
        args.setEditable(false);
        args.setLineWrap(true);
        args.setWrapStyleWord(false);
        args.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        args.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(args);
        scroll.setPreferredSize(new Dimension(560, 220));
        root.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        reasonField.putClientProperty("JTextField.placeholderText", "Reason if you deny (optional, sent to the agent)");
        south.add(reasonField, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton deny = new JButton("Deny");
        JButton always = new JButton("Always allow " + request.toolName() + " in this session");
        JButton once = new JButton("Allow once");
        deny.addActionListener(e -> close(ToolPermissions.Answer.deny(reasonField.getText())));
        always.addActionListener(e -> close(ToolPermissions.Answer.allowAlways()));
        once.addActionListener(e -> close(ToolPermissions.Answer.allowOnce()));
        buttons.add(deny);
        buttons.add(always);
        buttons.add(once);
        south.add(buttons, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);

        getRootPane().setDefaultButton(once);
        getRootPane().registerKeyboardAction(e -> close(ToolPermissions.Answer.deny(reasonField.getText())),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close(ToolPermissions.Answer.deny(reasonField.getText()));
            }
        });
        pack();
        setLocationRelativeTo(owner);
    }

    /** Shows the dialog on the event thread and waits for the answer; denies when there is no screen. */
    public static ToolPermissions.Answer ask(Window owner, ToolPermissions.Request request) {
        if (GraphicsEnvironment.isHeadless()) return ToolPermissions.Answer.deny("no display to ask on");
        ToolPermissions.Answer[] result = {ToolPermissions.Answer.deny("not answered")};
        Runnable show = () -> {
            ToolApprovalDialog d = new ToolApprovalDialog(owner, request);
            d.setVisible(true);
            result[0] = d.answer;
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) show.run();
            else SwingUtilities.invokeAndWait(show);
        } catch (Exception e) {
            return ToolPermissions.Answer.deny("the approval prompt failed");
        }
        return result[0];
    }

    /** The arguments as indented JSON when they are JSON, cut to a readable length. */
    static String display(String arguments) {
        String text = arguments == null ? "" : arguments;
        try {
            if (text.trim().startsWith("{")) {
                text = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(MAPPER.readTree(text));
            }
        } catch (Exception ignored) {
            // not JSON: show as given
        }
        if (text.length() > MAX_SHOWN_CHARS) {
            text = text.substring(0, MAX_SHOWN_CHARS) + "\n… (" + (text.length() - MAX_SHOWN_CHARS) + " more characters)";
        }
        return text;
    }

    private void close(ToolPermissions.Answer a) {
        answer = a;
        dispose();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
