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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.InputType;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
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
    private static final String PAGE_SESSIONS = "Sessions";
    private static final String PAGE_TOOLS = "Tools";
    private static final String PAGE_UPDATE = "Update";
    private static final String PAGE_ABOUT = "About";
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
    private String activeMainPage = PAGE_SESSIONS;
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
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
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
        statusText.setPadding(dp(10), 0, dp(10), 0);
        statusText.setBackground(rounded(Color.rgb(22, 27, 34), 0, Color.TRANSPARENT, 0));
        statusText.setSingleLine(true);
        setStatus("Ready");
        applySystemBarInsets(root);
        return root;
    }

    private void applySystemBarInsets(View view) {
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = Math.max(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            }
            target.setPadding(
                    0,
                    insets.getSystemWindowInsetTop(),
                    0,
                    bottom
            );
            return insets;
        });
        view.requestApplyInsets();
        view.post(view::requestApplyInsets);
    }

    private void renderSessionScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_SESSIONS;
        root.removeAllViews();
        root.addView(createServerBar(), matchWrap());
        root.addView(createServerProfileBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
        ));
        root.addView(createMainTabs(PAGE_SESSIONS), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
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

    private void renderToolsScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_TOOLS;
        root.removeAllViews();
        root.addView(createServerBar(), matchWrap());
        root.addView(createMainTabs(PAGE_TOOLS), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = pageContent();
        content.addView(sectionTitle("Server"));
        content.addView(actionPanel(
                actionButton("Health", view -> showRaw("Health", () -> api.health())),
                actionButton("Probe Tailscale", view -> probeServerProfiles()),
                actionButton("Status", view -> showRaw("Server status", () -> api.serverStatus())),
                actionButton("Preferences", view -> showRaw("Preferences", () -> api.preferences()))
        ));
        content.addView(sectionTitle("Sessions"));
        content.addView(actionPanel(
                actionButton("Timeline", view -> showRaw("Timeline", () -> api.timeline(50))),
                actionButton("All details", view -> showRaw("All session details", () -> api.sessionsAll())),
                actionButton("Pane details", view -> showRaw("Pane details", () -> api.sessionsPanes()))
        ));
        content.addView(sectionTitle("Kanban / Messages"));
        content.addView(actionPanel(
                actionButton("Projects", view -> showRaw("Kanban projects", () -> api.kanbanProjects())),
                actionButton("New project", view -> promptCreateKanbanProject()),
                actionButton("Delete project", view -> promptDeleteKanbanProject()),
                actionButton("Remove session", view -> promptRemoveKanbanSession())
        ));
        content.addView(actionPanel(
                actionButton("Messages", view -> promptGroupMessages()),
                actionButton("Send message", view -> promptSendGroupMessage()),
                actionButton("Scan message", view -> promptScanGroupMessage()),
                actionButton("Post hook", view -> promptPostHookEvent())
        ));
        content.addView(sectionTitle("Images"));
        content.addView(actionPanel(
                actionButton("Upload file", view -> promptUploadImageFile()),
                actionButton("Upload URL", view -> promptUploadImageUrl()),
                actionButton("Preview info", view -> promptImagePreviewInfo()),
                actionButton("Preview", view -> promptOpenImagePreview())
        ));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
        ));
        setStatus("Tools");
    }

    private void renderUpdateScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_UPDATE;
        root.removeAllViews();
        root.addView(createServerBar(), matchWrap());
        root.addView(createMainTabs(PAGE_UPDATE), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = pageContent();
        content.addView(infoBlock(
                "Installed",
                "Version " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                        + "Update source:\n" + prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL)
        ));
        content.addView(sectionTitle("Update"));
        content.addView(actionPanel(
                actionButton("Check now", view -> updateManager.check(true)),
                actionButton("Source", view -> showUpdateSourcePicker()),
                actionButton("APK", view -> updateManager.openApkDownload())
        ));
        content.addView(sectionTitle("Permissions"));
        content.addView(infoBlock("Android", permissionSummary()));
        content.addView(actionPanel(
                actionButton("Install permission", view -> openInstallPermissionSettings()),
                actionButton("Notifications", view -> requestNotificationPermission()),
                actionButton("Details", view -> showPermissionsAndUpdateStatus())
        ));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
        ));
        setStatus("Update and permissions");
    }

    private void renderAboutScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_ABOUT;
        root.removeAllViews();
        root.addView(createServerBar(), matchWrap());
        root.addView(createMainTabs(PAGE_ABOUT), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
        ));
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = pageContent();
        content.addView(infoBlock(
                "tmux-browser Android",
                "Native Android client for the remote tmux-browser API.\n"
                        + "Version " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                        + "Package " + getPackageName()
        ));
        content.addView(infoBlock(
                "Protocol",
                "API base: " + getServerUrl() + "\n"
                        + "HTTP API on port 3000; terminal input/output uses the app's native socket client."
        ));
        content.addView(infoBlock(
                "Update policy",
                "The app checks only the selected update source. APK downloads are cached by version and reused after Android install permission is granted."
        ));
        content.addView(actionPanel(
                actionButton("Release page", view -> updateManager.openReleasePage()),
                actionButton("Update source", view -> showUpdateSourcePicker()),
                actionButton("Permissions", view -> showPermissionsAndUpdateStatus())
        ));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        root.addView(statusText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(28)
        ));
        setStatus("About");
    }

    private LinearLayout createServerBar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(Color.rgb(17, 20, 24));

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextColor(Color.WHITE);
        urlField.setHintTextColor(Color.rgb(150, 158, 168));
        urlField.setTextSize(14);
        urlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlField.setText(getServerUrl());
        urlField.setSelectAllOnFocus(true);
        styleInput(urlField);
        urlField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                saveServerAndRefresh();
                return true;
            }
            return false;
        });
        urlRow.addView(urlField, new LinearLayout.LayoutParams(0, dp(44), 1));
        urlRow.addView(toolbarButton("Go", view -> saveServerAndRefresh()));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(6), 0, 0);
        addContextActions(actionRow);

        toolbar.addView(urlRow, matchWrap());
        if (actionRow.getChildCount() > 0) {
            toolbar.addView(actionRow, matchWrap());
        }
        return toolbar;
    }

    private void addContextActions(LinearLayout actionRow) {
        if (PAGE_SESSIONS.equals(activeMainPage)) {
            actionRow.addView(toolbarButton("New", view -> promptCreateSession()));
            actionRow.addView(toolbarButton("Refresh", view -> refreshSessions()));
            actionRow.addView(toolbarButton("Probe", view -> probeServerProfiles()));
            return;
        }
        if (PAGE_TOOLS.equals(activeMainPage)) {
            actionRow.addView(toolbarButton("Health", view -> showRaw("Health", () -> api.health())));
            actionRow.addView(toolbarButton("Probe", view -> probeServerProfiles()));
            return;
        }
        if (PAGE_UPDATE.equals(activeMainPage)) {
            actionRow.addView(toolbarButton("Check now", view -> updateManager.check(true)));
            actionRow.addView(toolbarButton("Source", view -> showUpdateSourcePicker()));
            return;
        }
        if (PAGE_ABOUT.equals(activeMainPage)) {
            actionRow.addView(toolbarButton("Release", view -> updateManager.openReleasePage()));
            actionRow.addView(toolbarButton("Permissions", view -> showPermissionsAndUpdateStatus()));
        }
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

    private HorizontalScrollView createMainTabs(String selected) {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(Color.rgb(17, 20, 24));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.addView(navButton(PAGE_SESSIONS, selected, view -> {
            renderSessionScreen();
            refreshSessions();
        }));
        row.addView(navButton(PAGE_TOOLS, selected, view -> renderToolsScreen()));
        row.addView(navButton(PAGE_UPDATE, selected, view -> renderUpdateScreen()));
        row.addView(navButton(PAGE_ABOUT, selected, view -> renderAboutScreen()));

        scroller.addView(row, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return scroller;
    }

    private Button navButton(String label, String selected, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        boolean active = label.equals(selected);
        button.setTextColor(active ? Color.rgb(8, 12, 18) : Color.rgb(235, 241, 248));
        button.setBackground(active
                ? rounded(Color.rgb(86, 211, 219), 8, Color.rgb(86, 211, 219), 1)
                : buttonBackground());
        button.setMinWidth(dp(82));
        button.setMinimumWidth(dp(82));
        return button;
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(10), dp(10), dp(14));
        return content;
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(Color.rgb(139, 148, 158));
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), dp(12), dp(2), dp(6));
        return title;
    }

    private View infoBlock(String title, String body) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        block.setBackground(rounded(Color.rgb(27, 33, 40), 8, Color.rgb(45, 54, 64), 1));

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(16);
        heading.setTypeface(Typeface.DEFAULT_BOLD);

        TextView text = bodyText(body);
        text.setPadding(0, dp(6), 0, 0);
        block.addView(heading);
        block.addView(text);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        block.setLayoutParams(params);
        return block;
    }

    private LinearLayout actionPanel(Button... buttons) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(7), dp(7), dp(7), dp(2));
        panel.setBackground(rounded(Color.rgb(24, 30, 37), 8, Color.rgb(42, 51, 61), 1));
        LinearLayout row = null;
        for (int index = 0; index < buttons.length; index++) {
            if (index % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                panel.addView(row, matchWrap());
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
            params.leftMargin = dp(3);
            params.rightMargin = dp(3);
            params.bottomMargin = dp(6);
            row.addView(buttons[index], params);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        panel.setLayoutParams(params);
        return panel;
    }

    private Button actionButton(String label, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return button;
    }

    private void selectServer(String url) {
        prefs.edit().putString("server_url", url).apply();
        api = new SessionApiClient(url);
        urlField.setText(url);
        setStatus("Selected " + url);
        connectAppEvents();
        if (PAGE_SESSIONS.equals(activeMainPage)) {
            refreshSessions();
        }
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
        row.setPadding(dp(12), dp(11), dp(12), dp(11));
        row.setBackground(rounded(Color.rgb(27, 33, 40), 8, Color.rgb(45, 54, 64), 1));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            row.setElevation(dp(1));
        }

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
        terminalScroll.setFillViewport(true);
        terminalScroll.setBackgroundColor(Color.rgb(4, 7, 10));
        terminalText = new TextView(this);
        terminalText.setTextColor(Color.rgb(230, 235, 242));
        terminalText.setTextSize(13);
        terminalText.setTypeface(Typeface.MONOSPACE);
        terminalText.setIncludeFontPadding(false);
        terminalText.setLineSpacing(0, 1.05f);
        terminalText.setGravity(Gravity.BOTTOM | Gravity.START);
        terminalText.setTextIsSelectable(false);
        terminalText.setPadding(dp(10), dp(10), dp(10), dp(10));
        terminalText.setBackgroundColor(Color.rgb(4, 7, 10));
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
                "Send command",
                "Tmux prefix",
                "Detach tmux client",
                "New tmux window",
                "Next tmux window",
                "Previous tmux window"
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
                        case 7:
                            sendTerminalInput("\u0002");
                            break;
                        case 8:
                            sendTerminalInput("\u0002d");
                            break;
                        case 9:
                            sendTerminalInput("\u0002c");
                            break;
                        case 10:
                            sendTerminalInput("\u0002n");
                            break;
                        case 11:
                            sendTerminalInput("\u0002p");
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
        styleInput(inputField);
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
        text.append("Installed app: ")
                .append(BuildConfig.VERSION_NAME)
                .append(" (")
                .append(BuildConfig.VERSION_CODE)
                .append(")\n");
        text.append("Server: ").append(getServerUrl()).append('\n');
        text.append('\n');
        text.append("Manual APK download:\n");
        text.append("Tap APK on the Update page. The app resolves the APK from the selected update source.\n");
        text.append("Release page:\n");
        text.append("Tap Release on the About page. The app resolves the page from the selected update source.\n");
        text.append('\n');
        text.append("In-app update:\n");
        text.append("Tap Update. The app checks only the selected source, downloads one APK per version, verifies SHA-256, then opens Android's installer.\n");
        text.append("Selected manifest:\n")
                .append(prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL))
                .append('\n');
        text.append("Available sources:\n");
        text.append("GitHub: ").append(BuildConfig.DEFAULT_UPDATE_URL).append('\n');
        text.append("Gitea: ").append(BuildConfig.DEFAULT_GITEA_UPDATE_URL).append('\n');
        text.append('\n');
        text.append(permissionSummary());

        new AlertDialog.Builder(this)
                .setTitle("Permissions / update")
                .setMessage(text.toString())
                .setNegativeButton("Close", null)
                .setNeutralButton("Install permission", (dialog, which) -> openInstallPermissionSettings())
                .setPositiveButton("Check update", (dialog, which) -> updateManager.check(true))
                .show();
    }

    private String permissionSummary() {
        StringBuilder text = new StringBuilder();
        text.append("Network: declared in manifest; Android does not show a runtime grant.\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            text.append("Install unknown apps: ")
                    .append(getPackageManager().canRequestPackageInstalls() ? "allowed" : "not allowed")
                    .append(". Android requires you to allow this per app before in-app APK updates can install.\n");
        } else {
            text.append("Install unknown apps: allowed by this Android version.\n");
        }
        if (Build.VERSION.SDK_INT >= 33) {
            text.append("Notifications: ")
                    .append(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ? "allowed" : "not allowed")
                    .append(". This only affects optional status notifications, not tmux control.\n");
        } else {
            text.append("Notifications: no runtime permission required.\n");
        }
        text.append("SMS: not requested. This tmux client does not need SMS permission.");
        return text.toString();
    }

    private void showUpdateSourcePicker() {
        String[] items = {
                "GitHub: " + BuildConfig.DEFAULT_UPDATE_URL,
                "Gitea: " + BuildConfig.DEFAULT_GITEA_UPDATE_URL,
                "Custom URL"
        };
        new AlertDialog.Builder(this)
                .setTitle("Update source")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        setUpdateUrl(BuildConfig.DEFAULT_UPDATE_URL);
                    } else if (which == 1) {
                        setUpdateUrl(BuildConfig.DEFAULT_GITEA_UPDATE_URL);
                    } else {
                        promptText("Custom update manifest", "https://.../latest.json", prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL), this::setUpdateUrl);
                    }
                })
                .show();
    }

    private void setUpdateUrl(String url) {
        prefs.edit().putString("update_url", url).apply();
        showMessage("Update source: " + url);
        if (PAGE_UPDATE.equals(activeMainPage)) {
            renderUpdateScreen();
        } else if (PAGE_ABOUT.equals(activeMainPage)) {
            renderAboutScreen();
        }
    }

    private void probeServerProfiles() {
        progressBar.setVisibility(View.VISIBLE);
        setStatus("Probing Tailscale APIs...");
        executor.execute(() -> {
            StringBuilder text = new StringBuilder();
            for (String url : SERVER_PROFILES) {
                text.append(url).append('\n');
                try {
                    SessionApiClient client = new SessionApiClient(url);
                    JSONObject health = new JSONObject(client.health());
                    List<SessionSummary> sessions = client.getSessions();
                    text.append("  ok: true\n");
                    text.append("  version: ")
                            .append(health.optString("version", "unknown"))
                            .append(" ")
                            .append(health.optString("commit", ""))
                            .append('\n');
                    text.append("  sessions: ").append(sessions.size()).append('\n');
                } catch (Exception error) {
                    text.append("  failed: ").append(error.getMessage()).append('\n');
                }
                text.append('\n');
            }
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                showTextDialog("Tailscale APIs", text.toString());
            });
        });
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
        styleInput(input);
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
        addSoftKey(row, "^Z", "\u001a");
        addSoftKey(row, "^\\", "\u001c");
        addSoftKey(row, "Tmux", "\u0002");
        addSoftKey(row, "Detach", "\u0002d");
        addSoftKey(row, "NewWin", "\u0002c");
        addSoftKey(row, "NextWin", "\u0002n");
        addSoftKey(row, "PrevWin", "\u0002p");
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
        terminalText.setText(renderAnsiForTerminal(terminalBuffer.toString()));
        terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
    }

    private CharSequence renderAnsiForTerminal(String text) {
        SpannableStringBuilder output = new SpannableStringBuilder();
        int fg = Color.rgb(230, 235, 242);
        int bg = Color.TRANSPARENT;
        boolean bold = false;
        int index = 0;
        while (index < text.length()) {
            char item = text.charAt(index);
            if (item == '\r') {
                index++;
                continue;
            }
            if (item == '\u001b' && index + 1 < text.length() && text.charAt(index + 1) == '[') {
                int end = findAnsiEnd(text, index + 2);
                if (end == -1) {
                    break;
                }
                char command = text.charAt(end);
                if (command == 'm') {
                    int[] state = applySgr(text.substring(index + 2, end), fg, bg, bold);
                    fg = state[0];
                    bg = state[1];
                    bold = state[2] == 1;
                }
                index = end + 1;
                continue;
            }

            int start = output.length();
            output.append(item);
            int finish = output.length();
            output.setSpan(new ForegroundColorSpan(fg), start, finish, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bg != Color.TRANSPARENT) {
                output.setSpan(new BackgroundColorSpan(bg), start, finish, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (bold) {
                output.setSpan(new StyleSpan(Typeface.BOLD), start, finish, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            index++;
        }
        return output;
    }

    private int findAnsiEnd(String text, int start) {
        for (int index = start; index < text.length(); index++) {
            char item = text.charAt(index);
            if (item >= '@' && item <= '~') {
                return index;
            }
        }
        return -1;
    }

    private int[] applySgr(String params, int fg, int bg, boolean bold) {
        if (params.isEmpty()) {
            params = "0";
        }
        String[] parts = params.split(";");
        for (String part : parts) {
            int value;
            try {
                value = part.isEmpty() ? 0 : Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (value == 0) {
                fg = Color.rgb(230, 235, 242);
                bg = Color.TRANSPARENT;
                bold = false;
            } else if (value == 1) {
                bold = true;
            } else if (value == 22) {
                bold = false;
            } else if (value == 39) {
                fg = Color.rgb(230, 235, 242);
            } else if (value == 49) {
                bg = Color.TRANSPARENT;
            } else if ((value >= 30 && value <= 37) || (value >= 90 && value <= 97)) {
                fg = ansiColor(value, false);
            } else if ((value >= 40 && value <= 47) || (value >= 100 && value <= 107)) {
                bg = ansiColor(value, true);
            }
        }
        return new int[]{fg, bg, bold ? 1 : 0};
    }

    private int ansiColor(int code, boolean background) {
        int base = background ? (code >= 100 ? code - 100 : code - 40) : (code >= 90 ? code - 90 : code - 30);
        boolean bright = code >= 90;
        switch (base) {
            case 0:
                return bright ? Color.rgb(80, 88, 100) : Color.rgb(33, 38, 45);
            case 1:
                return bright ? Color.rgb(255, 123, 114) : Color.rgb(248, 81, 73);
            case 2:
                return bright ? Color.rgb(86, 211, 100) : Color.rgb(63, 185, 80);
            case 3:
                return bright ? Color.rgb(234, 179, 8) : Color.rgb(210, 153, 34);
            case 4:
                return bright ? Color.rgb(121, 192, 255) : Color.rgb(88, 166, 255);
            case 5:
                return bright ? Color.rgb(210, 168, 255) : Color.rgb(188, 140, 255);
            case 6:
                return bright ? Color.rgb(86, 211, 219) : Color.rgb(57, 197, 187);
            case 7:
            default:
                return bright ? Color.rgb(240, 246, 252) : Color.rgb(201, 209, 217);
        }
    }

    private void saveServerAndRefresh() {
        String url = normalizeServerUrl(urlField.getText().toString());
        prefs.edit().putString("server_url", url).apply();
        api = new SessionApiClient(url);
        urlField.setText(url);
        connectAppEvents();
        if (PAGE_SESSIONS.equals(activeMainPage)) {
            refreshSessions();
        } else {
            setStatus("Server saved: " + url);
        }
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
        button.setTextColor(Color.rgb(235, 241, 248));
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(buttonBackground());
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(42)
        );
        params.leftMargin = dp(5);
        button.setLayoutParams(params);
        return button;
    }

    private void styleInput(EditText input) {
        input.setTextColor(Color.rgb(240, 246, 252));
        input.setHintTextColor(Color.rgb(139, 148, 158));
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(rounded(Color.rgb(12, 17, 23), 8, Color.rgb(48, 58, 70), 1));
    }

    private StateListDrawable buttonBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(Color.rgb(64, 78, 94), 8, Color.rgb(91, 108, 128), 1));
        states.addState(new int[]{android.R.attr.state_focused}, rounded(Color.rgb(48, 61, 76), 8, Color.rgb(98, 128, 164), 1));
        states.addState(new int[]{}, rounded(Color.rgb(34, 43, 53), 8, Color.rgb(55, 66, 80), 1));
        return states;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
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
    protected void onResume() {
        super.onResume();
        if (updateManager != null) {
            updateManager.resumePendingInstall();
        }
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
