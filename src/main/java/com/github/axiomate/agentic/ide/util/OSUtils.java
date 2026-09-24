package com.github.axiomate.agentic.ide.util;

import java.util.Locale;

/**
 * Utility for determining the host operating system and selecting appropriate shell tools
 * (PowerShell for Windows, Bash for Linux/macOS).
 */
public final class OSUtils {

    private OSUtils() {}

    /**
     * Checks if the host operating system is Windows.
     */
    public static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Checks if the host operating system is macOS.
     */
    public static boolean isMac() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * Checks if the host operating system is Linux or Unix (excluding macOS).
     */
    public static boolean isLinux() {
        return !isWindows() && !isMac();
    }

    /**
     * Returns the raw operating system name from System properties.
     */
    public static String getOsName() {
        return System.getProperty("os.name", "Unknown OS");
    }

    /**
     * Returns the host architecture from System properties.
     */
    public static String getOsArch() {
        return System.getProperty("os.arch", "Unknown Arch");
    }

    /**
     * Returns the primary shell tool name appropriate for the host OS:
     * "powershell" on Windows, "bash" on Linux / macOS.
     */
    public static String getPreferredShellTool() {
        return isWindows() ? "powershell" : "bash";
    }

    /**
     * Returns a human-friendly shell display name.
     */
    public static String getPreferredShellDisplayName() {
        return isWindows() ? "PowerShell" : "Bash";
    }

    /**
     * Returns a concise description of the host environment for LLM context injection.
     */
    public static String getHostEnvironmentSummary() {
        return String.format("%s (%s), Primary Shell: %s", getOsName(), getOsArch(), getPreferredShellDisplayName());
    }
}
