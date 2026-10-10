package com.github.axiomate.agentic.ide.ui.components;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompts sent from the agent chat, recalled with Ctrl+↑ / Ctrl+↓ (or ↑ in an empty prompt box) like a shell
 * history. Whatever was typed before browsing is kept and comes back after the newest entry.
 */
public class PromptHistory {

    static final int MAX_ENTRIES = 200;

    /** Oldest first. */
    private final List<String> entries = new ArrayList<>();
    /** Entry being shown, or -1 when not browsing. */
    private int index = -1;
    private String draft = "";

    /** Records a sent prompt; sending one again moves it to the newest position instead of repeating it. */
    public synchronized void add(String prompt) {
        String p = prompt == null ? "" : prompt.strip();
        reset();
        if (p.isEmpty()) return;
        entries.remove(p);
        entries.add(p);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
    }

    /** Adds earlier prompts (oldest first) behind the ones already recorded, e.g. from a reopened session. */
    public synchronized void seed(List<String> older) {
        List<String> merged = new ArrayList<>();
        for (String s : older) {
            String p = s == null ? "" : s.strip();
            if (!p.isEmpty() && !entries.contains(p)) {
                merged.remove(p);
                merged.add(p);
            }
        }
        entries.addAll(0, merged);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        reset();
    }

    /**
     * Steps back one entry.
     *
     * @param current the prompt box's text, kept as the draft when browsing starts
     * @return the text to show, or null when there is nothing older
     */
    public synchronized String previous(String current) {
        if (entries.isEmpty()) return null;
        if (index == -1) {
            draft = current == null ? "" : current;
            index = entries.size() - 1;
        } else if (index > 0) {
            index--;
        } else {
            return null;
        }
        return entries.get(index);
    }

    /** Steps forward one entry; past the newest the draft comes back. Null when not browsing. */
    public synchronized String next() {
        if (index == -1) return null;
        index++;
        if (index >= entries.size()) {
            String d = draft;
            reset();
            return d;
        }
        return entries.get(index);
    }

    public synchronized boolean isBrowsing() {
        return index != -1;
    }

    /** Stops browsing, e.g. when the user edits the recalled text. */
    public synchronized void reset() {
        index = -1;
        draft = "";
    }

    public synchronized List<String> entries() {
        return List.copyOf(entries);
    }
}
