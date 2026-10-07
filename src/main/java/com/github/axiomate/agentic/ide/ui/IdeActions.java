package com.github.axiomate.agentic.ide.ui;

import java.io.File;

/**
 * Frame-level IDE actions that panels, dialogs and menus can trigger without depending on {@link MainFrame}.
 * Every method has a no-op default so lightweight hosts (and tests) only implement what they need.
 */
public interface IdeActions {

    String VIEW_EXPLORER = "explorer";
    String VIEW_SESSIONS = "sessions";
    String VIEW_AGENT_SYNC = "agent-sync";
    String VIEW_PLUGINS = "plugins";

    IdeActions NONE = new IdeActions() {
    };

    default void showSidebarView(String viewId) {
    }

    default void openProject(File dir) {
    }

    default void openSessionManager() {
    }

    /** Opens the session's folder in the Explorer (switching project if needed) and activates the session. */
    default void showSessionFolder(com.github.axiomate.agentic.ide.agent.session.AgentSession session) {
    }

    default void openExternalSessionImport() {
    }

    default void openMemoryImport() {
    }

    default void openMemoryExport() {
    }

    default void openMcpInterop(boolean exportTab) {
    }

    /**
     * @param tab 0 = marketplace, 1 = installed, 2 = install from folder/ZIP/URL, 3 = commands
     */
    default void openPluginManager(int tab) {
    }

    default void openCommandPalette() {
    }

    default void focusMemoryTab() {
    }

    default void reloadAgentCommands() {
    }
}
