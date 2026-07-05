package com.neatstudio.tmuxandroid;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMAGE_PICK_REQUEST = 2001;
    private static final long AUTO_UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int TERMINAL_COLS = 96;
    private static final int TERMINAL_ROWS = 32;
    private static final int MAX_TERMINAL_CHARS = 120_000;
    private static final String OLD_LOCAL_DEFAULT_URL = "http://127.0.0.1:3000";
    private static final String DEFAULT_TAILSCALE_URL = "http://100.89.0.116:3000";
    private static final String[] SERVER_PROFILES = {
            "http://100.89.0.116:3000",
            "http://100.89.0.2:3000",
            "http://100.89.0.4:3000",
            "http://100.89.0.9:3000",
            "http://100.89.0.11:3000"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private UpdateManager updateManager;
    private SessionApiClient api;
    private TerminalSocketClient terminalSocket;
    private AppEventSocketClient eventSocket;
    private LinearLayout root;
    private EditText urlField;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView terminalText;
    private ScrollView terminalScroll;
    private EditText inputField;
    private String activeSessionName;
    private String pendingImageUploadSession;
    private final StringBuilder terminalBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tmux_android", MODE_PRIVATE);
        if (!prefs.contains("server_url")) {
            prefs.edit()
                    .putString("server_url", BuildConfig.DEFAULT_SERVER_URL)
                    .putString("update_url", BuildConfig.DEFAULT_UPDATE_URL)
                    .apply();
        } else if (
                OLD_LOCAL_DEFAULT_URL.equals(prefs.getString("server_url", ""))
                        && !prefs.getBoolean("tailscale_defaults_applied_v1", false)
        ) {
            prefs.edit()
                    .putString("server_url", DEFAULT_TAILSCALE_URL)
                    .putBoolean("tailscale_defaults_applied_v1", true)
                    .apply();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.rgb(17, 20, 24));
            getWindow().setNavigationBarColor(Color.rgb(17, 20, 24));
        }
        api = new SessionApiClient(getServerUrl());
        setContentView(createRoot());
        updateManager = new UpdateManager(this, prefs, new UpdateManager.Callback() {
            @Override
            public void onChecking(boolean checking) {
                progressBar.setVisibility(checking ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onMessage(String message) {
                showMessage(message);
            }
        });
        renderSessionScreen();
        refreshSessions();
        connectAppEvents();
        maybeCheckForUpdates();
    }

    private View createRoot() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 20, 24));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);

        statusText = new TextView(this);
        statusText.setTextColor(Color.rgb(210, 215, 224));
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(8), 0, dp(8), 0);
        statusText.setBackgroundColor(Color.rgb(29, 34, 41));
        statusText.setSingleLine(true);
        setStatus("Ready");
        return root;
    }

    private void renderSessionScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        root.removeAllViews();
        root.addView(createServerBar(), matchWrap());
        root.addView(createServerProfileBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
        ));
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        list.setTag("session-list");
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
        ));
    }

    private LinearLayout createServerBar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(Color.rgb(17, 20, 24));

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextColor(Color.WHITE);
        urlField.setHintTextColor(Color.rgb(150, 158, 168));
        urlField.setTextSize(14);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setText(getServerUrl());
        urlField.setSelectAllOnFocus(true);
        urlField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                saveServerAndRefresh();
                return true;
            }
            return false;
        });
        toolbar.addView(urlField, new LinearLayout.LayoutParams(0, dp(42), 1));
        toolbar.addView(toolbarButton("Go", view -> saveServerAndRefresh()));
        toolbar.addView(toolbarButton("New", view -> promptCreateSession()));
        toolbar.addView(toolbarButton("Update", view -> updateManager.check(true)));
        toolbar.addView(toolbarButton("More", view -> showMainActions()));
        return toolbar;
    }

    private HorizontalScrollView createServerProfileBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(Color.rgb(22, 27, 34));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));

        for (String url : SERVER_PROFILES) {
            String label = url.replace("http://", "").replace(":3000", "");
            Button button = toolbarButton(label, view -> selectServer(url));
            button.setMinWidth(dp(92));
            button.setMinimumWidth(dp(92));
            row.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
        }
        row.addView(toolbarButton("Custom", view -> {
            urlField.requestFocus();
            urlField.selectAll();
        }));

        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return scroller;
    }

    private void selectServer(String url) {
        prefs.edit().putString("server_url", url).apply();
        api = new SessionApiClient(url);
        urlField.setText(url);
        setStatus("Selected " + url);
        connectAppEvents();
        refreshSessions();
    }

    private void refreshSessions() {
        setStatus("Loading sessions from " + api.getBaseUrl());
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                List<SessionSummary> sessions = api.getSessions();
                runOnUiThread(() -> renderSessionList(sessions));
            } catch (Exception error) {
                runOnUiThread(() -> showMessage("Session load failed: " + error.getMessage()));
            } finally {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }
        });
    }

    private void renderSessionList(List<SessionSummary> sessions) {
        LinearLayout list = root.findViewWithTag("session-list");
        if (list == null) {
            return;
        }
        list.removeAllViews();
        if (sessions.isEmpty()) {
            TextView empty = bodyText("No tmux sessions");
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            list.addView(empty);
        }
        for (SessionSummary session : sessions) {
            list.addView(sessionRow(session), matchWrap());
        }
        setStatus("Loaded " + sessions.size() + " sessions");
    }

    private View sessionRow(SessionSummary session) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackgroundColor(Color.rgb(29, 34, 41));

        TextView title = new TextView(this);
        title.setText(session.name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        String detail = session.status
                + "  windows:" + session.windows
                + "  panes:" + session.paneCount
                + textPart(session.currentCommand)
                + textPart(session.currentPath);
        TextView meta = bodyText(detail);
        meta.setPadding(0, dp(4), 0, dp(8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(toolbarButton("Open", view -> openTerminal(session.name)));
        actions.addView(toolbarButton("Kill", view -> confirmKill(session.name)));
        actions.addView(toolbarButton("More", view -> showSessionActions(session.name)));

        row.addView(title);
        row.addView(meta);
        row.addView(actions);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private void promptCreateSession() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("session-name");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("Create tmux session")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.matches("[A-Za-z0-9._-]+")) {
                        showMessage("Invalid session name");
                        return;
                    }
                    executor.execute(() -> {
                        try {
                            api.createSession(name);
                            runOnUiThread(() -> {
                                showMessage("Created " + name);
                                refreshSessions();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> showMessage("Create failed: " + error.getMessage()));
                        }
                    });
                })
                .show();
    }

    private void confirmKill(String sessionName) {
        new AlertDialog.Builder(this)
                .setTitle("Kill session")
                .setMessage("Kill tmux session \"" + sessionName + "\"?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Kill", (dialog, which) -> executor.execute(() -> {
                    try {
                        api.killSession(sessionName);
                        runOnUiThread(() -> {
                            showMessage("Killed " + sessionName);
                            refreshSessions();
                        });
                    } catch (Exception error) {
                        runOnUiThread(() -> showMessage("Kill failed: " + error.getMessage()));
                    }
                }))
                .show();
    }

    private void openTerminal(String sessionName) {
        activeSessionName = sessionName;
        terminalBuffer.setLength(0);
        root.removeAllViews();
        root.addView(createTerminalTopBar(sessionName), matchWrap());

        terminalScroll = new ScrollView(this);
        terminalText = new TextView(this);
        terminalText.setTextColor(Color.rgb(230, 235, 242));
        terminalText.setTextSize(12);
        terminalText.setTypeface(Typeface.MONOSPACE);
        terminalText.setTextIsSelectable(true);
        terminalText.setPadding(dp(8), dp(8), dp(8), dp(8));
        terminalText.setBackgroundColor(Color.BLACK);
        terminalScroll.addView(terminalText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        root.addView(terminalScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        root.addView(createInputBar(), matchWrap());
        root.addView(createSoftKeyBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
        ));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
        ));
        connectTerminal(sessionName);
    }

    private LinearLayout createTerminalTopBar(String sessionName) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(Color.rgb(17, 20, 24));
        bar.addView(toolbarButton("Back", view -> {
            renderSessionScreen();
            refreshSessions();
        }));
        TextView title = new TextView(this);
        title.setText(sessionName);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(10), 0, dp(10), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        bar.addView(toolbarButton("Reconnect", view -> connectTerminal(sessionName)));
        bar.addView(toolbarButton("More", view -> showTerminalActions(sessionName)));
        return bar;
    }

    private void showMainActions() {
        String[] items = {
                "Health",
                "Server status",
                "Timeline",
                "Preferences",
                "All session details",
                "Pane details",
                "Kanban projects",
                "Create kanban project",
                "Delete kanban project",
                "Remove kanban session",
                "Group messages",
                "Send group message",
                "Scan group message",
                "Post hook event",
                "Upload image file",
                "Upload image URL",
                "Image preview info",
                "Open image preview",
                "Permissions / update status"
        };
        new AlertDialog.Builder(this)
                .setTitle("Native API actions")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showRaw("Health", () -> api.health());
                            break;
                        case 1:
                            showRaw("Server status", () -> api.serverStatus());
                            break;
                        case 2:
                            showRaw("Timeline", () -> api.timeline(50));
                            break;
                        case 3:
                            showRaw("Preferences", () -> api.preferences());
                            break;
                        case 4:
                            showRaw("All session details", () -> api.sessionsAll());
                            break;
                        case 5:
                            showRaw("Pane details", () -> api.sessionsPanes());
                            break;
                        case 6:
                            showRaw("Kanban projects", () -> api.kanbanProjects());
                            break;
                        case 7:
                            promptCreateKanbanProject();
                            break;
                        case 8:
                            promptDeleteKanbanProject();
                            break;
                        case 9:
                            promptRemoveKanbanSession();
                            break;
                        case 10:
                            promptGroupMessages();
                            break;
                        case 11:
                            promptSendGroupMessage();
                            break;
                        case 12:
                            promptScanGroupMessage();
                            break;
                        case 13:
                            promptPostHookEvent();
                            break;
                        case 14:
                            promptUploadImageFile();
                            break;
                        case 15:
                            promptUploadImageUrl();
                            break;
                        case 16:
                            promptImagePreviewInfo();
                            break;
                        case 17:
                            promptOpenImagePreview();
                            break;
                        case 18:
                            showPermissionsAndUpdateStatus();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void showSessionActions(String sessionName) {
        String[] items = {
                "Status",
                "Rename",
                "Send command",
                "Split horizontal",
                "Split vertical",
                "Select pane",
                "Kill pane",
                "Pin session",
                "Unpin session",
                "Mute session",
                "Unmute session",
                "Session settings",
                "Add to kanban project",
                "Post hook event"
        };
        new AlertDialog.Builder(this)
                .setTitle(sessionName)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showRaw("Session status", () -> api.sessionStatus(sessionName));
                            break;
                        case 1:
                            promptRenameSession(sessionName);
                            break;
                        case 2:
                            promptSendCommand(sessionName);
                            break;
                        case 3:
                            runApiAction("Split horizontal", () -> api.splitPane(sessionName, "horizontal"));
                            break;
                        case 4:
                            runApiAction("Split vertical", () -> api.splitPane(sessionName, "vertical"));
                            break;
                        case 5:
                            promptPaneId("Select pane", paneId -> api.selectPane(sessionName, paneId));
                            break;
                        case 6:
                            promptPaneId("Kill pane", paneId -> api.killPane(sessionName, paneId));
                            break;
                        case 7:
                            runApiAction("Pin session", () -> api.setPinned(sessionName, true));
                            break;
                        case 8:
                            runApiAction("Unpin session", () -> api.setPinned(sessionName, false));
                            break;
                        case 9:
                            runApiAction("Mute session", () -> api.setMuted(sessionName, true));
                            break;
                        case 10:
                            runApiAction("Unmute session", () -> api.setMuted(sessionName, false));
                            break;
                        case 11:
                            promptSessionSettings(sessionName);
                            break;
                        case 12:
                            promptAddKanbanSession(sessionName);
                            break;
                        case 13:
                            promptPostHookEvent(sessionName);
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private void showTerminalActions(String sessionName) {
        String[] items = {
                "Clear local view and tmux history",
                "Split horizontal",
                "Split vertical",
                "Page up",
                "Page down",
                "Session status",
                "Send command"
        };
        new AlertDialog.Builder(this)
                .setTitle(sessionName)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            terminalBuffer.setLength(0);
                            terminalText.setText("");
                            if (terminalSocket != null) {
                                terminalSocket.clearHistory();
                            }
                            break;
                        case 1:
                            runApiAction("Split horizontal", () -> api.splitPane(sessionName, "horizontal"));
                            break;
                        case 2:
                            runApiAction("Split vertical", () -> api.splitPane(sessionName, "vertical"));
                            break;
                        case 3:
                            if (terminalSocket != null) {
                                terminalSocket.scroll(-TERMINAL_ROWS);
                            }
                            break;
                        case 4:
                            if (terminalSocket != null) {
                                terminalSocket.scroll(TERMINAL_ROWS);
                            }
                            break;
                        case 5:
                            showRaw("Session status", () -> api.sessionStatus(sessionName));
                            break;
                        case 6:
                            promptSendCommand(sessionName);
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private LinearLayout createInputBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(5), dp(8), dp(5));
        bar.setBackgroundColor(Color.rgb(17, 20, 24));

        inputField = new EditText(this);
        inputField.setSingleLine(true);
        inputField.setTextColor(Color.WHITE);
        inputField.setHintTextColor(Color.rgb(150, 158, 168));
        inputField.setHint("type command or text");
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        inputField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendLine();
                return true;
            }
            return false;
        });
        bar.addView(inputField, new LinearLayout.LayoutParams(0, dp(42), 1));
        bar.addView(toolbarButton("Send", view -> sendLine()));
        return bar;
    }

    private void promptRenameSession(String sessionName) {
        promptText("Rename session", sessionName, sessionName, nextName -> {
            if (!nextName.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("Invalid session name");
            }
            api.renameSession(sessionName, nextName);
        });
    }

    private void promptSendCommand(String sessionName) {
        promptText("Send command", "command", "", command -> api.sendCommand(sessionName, command));
    }

    private void promptPaneId(String title, TextApiAction action) {
        promptText(title, "%1", "", action);
    }

    private void promptCreateKanbanProject() {
        LinearLayout form = formRoot();
        EditText name = formField(form, "Project name", "project");
        EditText path = formField(form, "Path", "~");
        EditText server = formField(form, "SSH server optional", "");
        new AlertDialog.Builder(this)
                .setTitle("Create kanban project")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> runApiAction("Create kanban project", () ->
                        api.createKanbanProject(
                                name.getText().toString().trim(),
                                defaultValue(path.getText().toString().trim(), "~"),
                                server.getText().toString().trim()
                        )
                ))
                .show();
    }

    private void promptDeleteKanbanProject() {
        promptText("Delete kanban project", "project", "", projectName -> api.deleteKanbanProject(projectName));
    }

    private void promptAddKanbanSession(String sessionName) {
        promptText("Add to kanban project", "project", "", projectName -> api.addKanbanSession(projectName, sessionName));
    }

    private void promptRemoveKanbanSession() {
        LinearLayout form = formRoot();
        EditText project = formField(form, "Project", "");
        EditText agent = formField(form, "Agent/session name", "");
        EditText kill = formField(form, "Kill too: true or false", "false");
        new AlertDialog.Builder(this)
                .setTitle("Remove kanban session")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> runApiAction("Remove kanban session", () ->
                        api.removeKanbanSession(
                                project.getText().toString().trim(),
                                agent.getText().toString().trim(),
                                Boolean.parseBoolean(kill.getText().toString().trim())
                        )
                ))
                .show();
    }

    private void promptSessionSettings(String sessionName) {
        LinearLayout form = formRoot();
        EditText fontSize = formField(form, "Font size", "14");
        EditText fontFamily = formField(form, "Font family", "monospace");
        EditText lineHeight = formField(form, "Line height", "1.25");
        EditText themeId = formField(form, "Theme ID", "dark");
        new AlertDialog.Builder(this)
                .setTitle("Session settings")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> runApiAction("Session settings", () ->
                        api.updateSessionSettings(
                                sessionName,
                                Integer.parseInt(defaultValue(fontSize.getText().toString().trim(), "14")),
                                defaultValue(fontFamily.getText().toString().trim(), "monospace"),
                                Double.parseDouble(defaultValue(lineHeight.getText().toString().trim(), "1.25")),
                                defaultValue(themeId.getText().toString().trim(), "dark")
                        )
                ))
                .show();
    }

    private void promptGroupMessages() {
        promptText("Group messages", "project", "", projectName ->
                runOnUiThread(() -> showRaw("Group messages", () -> api.groupMessages(projectName)))
        );
    }

    private void promptSendGroupMessage() {
        LinearLayout form = formRoot();
        EditText project = formField(form, "Project", "");
        EditText from = formField(form, "From session", "");
        EditText kind = formField(form, "Kind: task or report", "task");
        EditText targetType = formField(form, "Target: session, others, role", "others");
        EditText targetValue = formField(form, "Target value", "");
        EditText body = formField(form, "Message", "");
        body.setMinLines(3);
        body.setSingleLine(false);
        new AlertDialog.Builder(this)
                .setTitle("Send group message")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> runApiAction("Send group message", () ->
                        api.sendGroupMessage(
                                project.getText().toString().trim(),
                                from.getText().toString().trim(),
                                defaultValue(kind.getText().toString().trim(), "task"),
                                defaultValue(targetType.getText().toString().trim(), "others"),
                                targetValue.getText().toString().trim(),
                                body.getText().toString()
                        )
                ))
                .show();
    }

    private void promptScanGroupMessage() {
        LinearLayout form = formRoot();
        EditText project = formField(form, "Project", "");
        EditText message = formField(form, "Message ID", "");
        new AlertDialog.Builder(this)
                .setTitle("Scan group message")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Scan", (dialog, which) -> runApiAction("Scan group message", () ->
                        api.scanGroupMessage(
                                project.getText().toString().trim(),
                                message.getText().toString().trim()
                        )
                ))
                .show();
    }

    private void promptPostHookEvent() {
        promptPostHookEvent("");
    }

    private void promptPostHookEvent(String presetSessionName) {
        LinearLayout form = formRoot();
        EditText session = formField(form, "Session", presetSessionName);
        EditText title = formField(form, "Title", "Android event");
        EditText status = formField(form, "Status", "info");
        EditText body = formField(form, "Body", "");
        body.setMinLines(2);
        body.setSingleLine(false);
        new AlertDialog.Builder(this)
                .setTitle("Post hook event")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Post", (dialog, which) -> runApiAction("Post hook event", () ->
                        api.postHookEvent(
                                session.getText().toString().trim(),
                                title.getText().toString().trim(),
                                defaultValue(status.getText().toString().trim(), "info"),
                                body.getText().toString()
                        )
                ))
                .show();
    }

    private void promptUploadImageUrl() {
        LinearLayout form = formRoot();
        EditText session = formField(form, "Session optional", "");
        EditText url = formField(form, "Image URL", "");
        new AlertDialog.Builder(this)
                .setTitle("Upload image URL")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Upload", (dialog, which) -> showRaw("Image upload", () ->
                        api.uploadImageUrl(
                                session.getText().toString().trim(),
                                url.getText().toString().trim()
                        )
                ))
                .show();
    }

    private void promptUploadImageFile() {
        EditText session = new EditText(this);
        session.setSingleLine(true);
        session.setHint("session optional");
        new AlertDialog.Builder(this)
                .setTitle("Upload image file")
                .setView(session)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Choose", (dialog, which) -> {
                    pendingImageUploadSession = session.getText().toString().trim();
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, "Choose image"), IMAGE_PICK_REQUEST);
                })
                .show();
    }

    private void promptImagePreviewInfo() {
        LinearLayout form = formRoot();
        EditText path = formField(form, "Path", "");
        EditText basePath = formField(form, "Base path optional", "");
        new AlertDialog.Builder(this)
                .setTitle("Image preview info")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Load", (dialog, which) -> showRaw("Image preview info", () ->
                        api.imagePreviewInfo(
                                path.getText().toString().trim(),
                                basePath.getText().toString().trim()
                        )
                ))
                .show();
    }

    private void promptOpenImagePreview() {
        LinearLayout form = formRoot();
        EditText path = formField(form, "Path", "");
        EditText basePath = formField(form, "Base path optional", "");
        new AlertDialog.Builder(this)
                .setTitle("Open image preview")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open", (dialog, which) -> showImagePreview(
                        path.getText().toString().trim(),
                        basePath.getText().toString().trim()
                ))
                .show();
    }

    private void showPermissionsAndUpdateStatus() {
        StringBuilder text = new StringBuilder();
        text.append("Server: ").append(getServerUrl()).append('\n');
        text.append("Update manifest: ")
                .append(prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL))
                .append('\n');
        text.append("Network: manifest permission, no runtime grant required\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            text.append("Install unknown apps: ")
                    .append(getPackageManager().canRequestPackageInstalls() ? "allowed" : "not allowed")
                    .append('\n');
        } else {
            text.append("Install unknown apps: allowed by Android version\n");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            text.append("Notifications: ")
                    .append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "allowed" : "not allowed")
                    .append('\n');
        } else {
            text.append("Notifications: no runtime permission required\n");
        }
        text.append("SMS: not requested; this tmux client does not need SMS permission.\n");

        new AlertDialog.Builder(this)
                .setTitle("Permissions / update")
                .setMessage(text.toString())
                .setNegativeButton("Close", null)
                .setNeutralButton("Install permission", (dialog, which) -> openInstallPermissionSettings())
                .setPositiveButton("Notify permission", (dialog, which) -> requestNotificationPermission())
                .show();
    }

    private void openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showMessage("Install permission is allowed on this Android version");
            return;
        }
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            showMessage("Notification permission is not required on this Android version");
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3001);
    }

    private void showImagePreview(String path, String basePath) {
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                byte[] bytes = api.imagePreview(path, basePath);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) {
                    throw new IllegalStateException("Preview is not a supported bitmap");
                }
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    ImageView image = new ImageView(this);
                    image.setImageBitmap(bitmap);
                    image.setAdjustViewBounds(true);
                    image.setPadding(dp(8), dp(8), dp(8), dp(8));
                    new AlertDialog.Builder(this)
                            .setTitle("Image preview")
                            .setView(image)
                            .setPositiveButton("Close", null)
                            .show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showMessage("Image preview failed: " + error.getMessage());
                });
            }
        });
    }

    private void promptText(String title, String hint, String value, TextApiAction action) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(hint);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    runApiAction(title, () -> action.run(text));
                })
                .show();
    }

    private void runApiAction(String label, ApiAction action) {
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                action.run();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showMessage(label + " done");
                    if (activeSessionName == null) {
                        refreshSessions();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showMessage(label + " failed: " + error.getMessage());
                });
            }
        });
    }

    private void showRaw(String title, Callable<String> loader) {
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String text = loader.call();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showTextDialog(title, text);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showMessage(title + " failed: " + error.getMessage());
                });
            }
        });
    }

    private void showTextDialog(String title, String text) {
        ScrollView scroll = new ScrollView(this);
        TextView content = new TextView(this);
        content.setText(text == null || text.isEmpty() ? "(empty)" : text);
        content.setTextColor(Color.rgb(20, 24, 30));
        content.setTextSize(12);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextIsSelectable(true);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show();
    }

    private LinearLayout formRoot() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), 0);
        return form;
    }

    private EditText formField(LinearLayout form, String label, String value) {
        TextView title = new TextView(this);
        title.setText(label);
        title.setTextColor(Color.DKGRAY);
        title.setTextSize(12);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        form.addView(title);
        form.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return input;
    }

    private HorizontalScrollView createSoftKeyBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(Color.rgb(22, 27, 34));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(4), dp(6), dp(4));
        addSoftKey(row, "Enter", "\r");
        addSoftKey(row, "Esc", "\u001b");
        addSoftKey(row, "Tab", "\t");
        addSoftKey(row, "^C", "\u0003");
        addSoftKey(row, "^D", "\u0004");
        addSoftKey(row, "^L", "\u000c");
        addSoftKey(row, "^R", "\u0012");
        addSoftKey(row, "^A", "\u0001");
        addSoftKey(row, "^E", "\u0005");
        addSoftKey(row, "^V", "\u0016");
        addSoftKey(row, "Left", "\u001b[D");
        addSoftKey(row, "Down", "\u001b[B");
        addSoftKey(row, "Up", "\u001b[A");
        addSoftKey(row, "Right", "\u001b[C");
        addSoftKey(row, "PgUp", "\u001b[5~");
        addSoftKey(row, "PgDn", "\u001b[6~");
        addSoftKey(row, "Home", "\u001b[H");
        addSoftKey(row, "End", "\u001b[F");
        addSoftButton(row, "Paste", view -> pasteClipboard());
        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return scroller;
    }

    private void connectTerminal(String sessionName) {
        closeTerminalSocket();
        setStatus("Connecting " + sessionName);
        appendTerminal("[connecting]\r\n");
        terminalSocket = new TerminalSocketClient(new TerminalSocketClient.Listener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> setStatus("Connected " + sessionName));
            }

            @Override
            public void onOutput(String data) {
                runOnUiThread(() -> appendTerminal(data));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    appendTerminal("\r\n[error] " + message + "\r\n");
                    setStatus("Terminal error: " + message);
                });
            }

            @Override
            public void onClosed() {
                runOnUiThread(() -> setStatus("Disconnected " + sessionName));
            }
        });
        terminalSocket.connect(api.getBaseUrl(), sessionName, TERMINAL_COLS, TERMINAL_ROWS);
    }

    private void sendLine() {
        if (inputField == null) {
            return;
        }
        String text = inputField.getText().toString();
        if (text.isEmpty()) {
            sendTerminalInput("\r");
        } else {
            sendTerminalInput(text + "\r");
            inputField.setText("");
        }
    }

    private void sendTerminalInput(String data) {
        if (activeSessionName == null) {
            return;
        }
        TerminalSocketClient socket = terminalSocket;
        if (socket != null) {
            socket.sendInput(data);
            return;
        }
        executor.execute(() -> {
            try {
                for (int i = 0; i < data.length(); i += 200) {
                    api.sendInput(activeSessionName, data.substring(i, Math.min(i + 200, data.length())));
                }
            } catch (Exception error) {
                runOnUiThread(() -> showMessage("Input failed: " + error.getMessage()));
            }
        });
    }

    private void pasteClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null && text.length() > 0) {
            sendTerminalInput(text.toString());
        }
    }

    private void appendTerminal(String data) {
        terminalBuffer.append(data);
        if (terminalBuffer.length() > MAX_TERMINAL_CHARS) {
            terminalBuffer.delete(0, terminalBuffer.length() - MAX_TERMINAL_CHARS);
        }
        terminalText.setText(stripAnsiForBasicView(terminalBuffer.toString()));
        terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
    }

    private String stripAnsiForBasicView(String text) {
        return text
                .replaceAll("\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replace("\r", "");
    }

    private void saveServerAndRefresh() {
        String url = normalizeServerUrl(urlField.getText().toString());
        prefs.edit().putString("server_url", url).apply();
        api = new SessionApiClient(url);
        urlField.setText(url);
        connectAppEvents();
        refreshSessions();
    }

    private String getServerUrl() {
        return prefs.getString("server_url", BuildConfig.DEFAULT_SERVER_URL);
    }

    private String normalizeServerUrl(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            value = BuildConfig.DEFAULT_SERVER_URL;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        Uri parsed = Uri.parse(value);
        if (parsed.getPort() == -1 && parsed.getHost() != null) {
            Uri.Builder builder = parsed.buildUpon().encodedAuthority(parsed.getHost() + ":3000");
            value = builder.build().toString();
        }
        while (value.endsWith("/") && value.length() > "http://x".length()) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void maybeCheckForUpdates() {
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_update_check_ms", 0L);
        if (now - last < AUTO_UPDATE_INTERVAL_MS) {
            return;
        }
        prefs.edit().putLong("last_update_check_ms", now).apply();
        root.postDelayed(() -> updateManager.check(false), 2000L);
    }

    private Button toolbarButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
        );
        params.leftMargin = dp(5);
        button.setLayoutParams(params);
        return button;
    }

    private void addSoftKey(LinearLayout row, String label, String sequence) {
        addSoftButton(row, label, view -> sendTerminalInput(sequence));
    }

    private void addSoftButton(LinearLayout row, String label, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        button.setMinWidth(dp(50));
        button.setMinimumWidth(dp(50));
        row.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(205, 213, 224));
        view.setTextSize(13);
        return view;
    }

    private String textPart(String value) {
        return value == null || value.isEmpty() ? "" : "  " + value;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void showMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        setStatus(message);
    }

    private void setStatus(String message) {
        if (statusText != null) {
            statusText.setText(message);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void closeTerminalSocket() {
        if (terminalSocket != null) {
            terminalSocket.close();
            terminalSocket = null;
        }
    }

    private void connectAppEvents() {
        if (eventSocket != null) {
            eventSocket.close();
        }
        eventSocket = new AppEventSocketClient(new AppEventSocketClient.Listener() {
            @Override
            public void onMessage(String text) {
                runOnUiThread(() -> handleAppEvent(text));
            }

            @Override
            public void onClosed() {
                runOnUiThread(() -> setStatus("Event stream disconnected"));
            }
        });
        eventSocket.connect(api.getBaseUrl());
    }

    private void handleAppEvent(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String type = event.optString("type", "");
            if ("hello".equals(type)) {
                setStatus("Event stream connected");
                return;
            }
            if ("sessions-invalidated".equals(type)) {
                setStatus("Sessions changed: " + event.optString("reason", "update"));
                if (activeSessionName == null) {
                    refreshSessions();
                }
                return;
            }
            if ("hook-event".equals(type)) {
                showMessage(event.optString("title", "Hook event"));
                return;
            }
            setStatus(type.isEmpty() ? "Event received" : type);
        } catch (Exception error) {
            setStatus("Event received");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMAGE_PICK_REQUEST) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingImageUploadSession = null;
            return;
        }
        Uri uri = data.getData();
        String sessionName = pendingImageUploadSession == null ? "" : pendingImageUploadSession;
        pendingImageUploadSession = null;
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IllegalStateException("Cannot open selected image");
                }
                String response = api.uploadImage(sessionName, readAllBytes(input));
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showTextDialog("Image upload", response);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showMessage("Image upload failed: " + error.getMessage());
                });
            }
        });
    }

    private byte[] readAllBytes(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BufferedInputStream buffered = new BufferedInputStream(input);
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = buffered.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @Override
    public void onBackPressed() {
        if (activeSessionName != null) {
            renderSessionScreen();
            refreshSessions();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        closeTerminalSocket();
        if (eventSocket != null) {
            eventSocket.close();
            eventSocket = null;
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    private interface ApiAction {
        void run() throws Exception;
    }

    private interface TextApiAction {
        void run(String text) throws Exception;
    }
}
