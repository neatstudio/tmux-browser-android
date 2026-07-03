package com.neatstudio.tmuxandroid;

final class SessionSummary {
    final String name;
    final String status;
    final String currentCommand;
    final String currentPath;
    final int windows;
    final int paneCount;

    SessionSummary(String name, String status, String currentCommand, String currentPath, int windows, int paneCount) {
        this.name = name;
        this.status = status;
        this.currentCommand = currentCommand;
        this.currentPath = currentPath;
        this.windows = windows;
        this.paneCount = paneCount;
    }
}

