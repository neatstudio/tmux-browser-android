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
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int IMAGE_PICK_REQUEST = 2001;
    private static final long AUTO_UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int DEFAULT_TERMINAL_COLS = 80;
    private static final int DEFAULT_TERMINAL_ROWS = 24;
    private static final int MIN_TERMINAL_COLS = 36;
    private static final int MAX_TERMINAL_COLS = 140;
    private static final int MIN_TERMINAL_ROWS = 8;
    private static final int MAX_TERMINAL_ROWS = 80;
    private static final int TERMINAL_KEYS_HEIGHT_DP = 92;
    private static final int STATUS_NORMAL = 0;
    private static final int STATUS_BUSY = 1;
    private static final int STATUS_SUCCESS = 2;
    private static final int STATUS_ERROR = 3;
    private static final long TERMINAL_RENDER_INTERVAL_MS = 80L;
    private static final long[] SOCKET_RECONNECT_DELAYS_MS = {1000L, 2000L, 4000L, 8000L, 15000L};
    private static final int COLOR_APP_BG = Color.rgb(18, 20, 24);
    private static final int COLOR_BAR = Color.rgb(20, 23, 27);
    private static final int COLOR_PANEL = Color.rgb(25, 28, 33);
    private static final int COLOR_CARD = Color.rgb(29, 33, 39);
    private static final int COLOR_CARD_ALT = Color.rgb(35, 39, 46);
    private static final int COLOR_FIELD = Color.rgb(14, 17, 21);
    private static final int COLOR_TERMINAL_BG = Color.rgb(11, 14, 19);
    private static final int COLOR_BORDER = Color.rgb(54, 59, 67);
    private static final int COLOR_BORDER_SOFT = Color.rgb(43, 48, 55);
    private static final int COLOR_TEXT = Color.rgb(242, 244, 247);
    private static final int COLOR_TEXT_MUTED = Color.rgb(158, 166, 177);
    private static final int COLOR_TEXT_DIM = Color.rgb(112, 121, 133);
    private static final int COLOR_ACCENT = Color.rgb(103, 218, 145);
    private static final int COLOR_ACCENT_DARK = Color.rgb(25, 67, 43);
    private static final int COLOR_ACCENT_WARM = Color.rgb(243, 185, 82);
    private static final int COLOR_SUCCESS = Color.rgb(91, 213, 139);
    private static final int COLOR_DANGER = Color.rgb(235, 103, 111);
    private static final String OLD_LOCAL_DEFAULT_URL = "http://127.0.0.1:3000";
    private static final String OLD_GITHUB_DEFAULT_UPDATE_URL = "https://github.com/neatstudio/tmux-browser-android/releases/latest/download/latest.json";
    private static final String DEFAULT_TAILSCALE_URL = "http://100.89.0.116:3000";
    private static final String PAGE_SERVERS = "Servers";
    private static final String PAGE_SESSIONS = "Sessions";
    private static final String PAGE_PROJECTS = "Projects";
    private static final String PAGE_TOOLS = "Tools";
    private static final String PAGE_UPDATE = "Update";
    private static final String PAGE_ABOUT = "About";
    private static final String TERMINAL_ENTER = "\r";
    private static final String[] SERVER_PROFILES = {
            "http://100.89.0.116:3000",
            "http://100.89.0.2:3000",
            "http://100.89.0.4:3000",
            "http://100.89.0.9:3000",
            "http://100.89.0.11:3000"
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private UpdateManager updateManager;
    private SessionApiClient api;
    private TerminalSocketClient terminalSocket;
    private AppEventSocketClient eventSocket;
    private LinearLayout root;
    private EditText urlField;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView sessionSummaryText;
    private LinearLayout projectList;
    private LinearLayout sessionGroupList;
    private TextView terminalText;
    private TextView terminalMetaText;
    private ScrollView terminalScroll;
    private EditText inputField;
    private View terminalAccessoryBar;
    private View terminalComposerBar;
    private LinearLayout terminalGroupRow;
    private String activeSessionName;
    private String terminalPathStatus = "";
    private String terminalSocketStatus = "";
    private String terminalEventStatus = "";
    private String activeMainPage = PAGE_SERVERS;
    private String pendingImageUploadSession;
    private int lastSessionCount = -1;
    private int lastActiveSessionCount = -1;
    private TerminalScreenBuffer terminalScreen = new TerminalScreenBuffer(DEFAULT_TERMINAL_COLS, DEFAULT_TERMINAL_ROWS);
    private final StringBuilder queuedTerminalInput = new StringBuilder();
    private boolean terminalConnected;
    private boolean terminalConnecting;
    private boolean terminalRenderPending;
    private boolean terminalSelectionEnabled;
    private boolean terminalFollowOutput = true;
    private int terminalKeyPage;
    private int terminalReconnectAttempt;
    private int terminalConnectionGeneration;
    private int eventReconnectAttempt;
    private int eventConnectionGeneration;
    private boolean activityDestroyed;
    private Runnable terminalReconnectTask;
    private Runnable eventReconnectTask;
    private long lastTerminalRenderMs;
    private int terminalCols = DEFAULT_TERMINAL_COLS;
    private int terminalRows = DEFAULT_TERMINAL_ROWS;

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
        migrateDefaultUpdateSourceToGitea();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(COLOR_APP_BG);
            getWindow().setNavigationBarColor(COLOR_APP_BG);
        }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
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
        renderServerScreen();
        connectAppEvents();
        maybeCheckForUpdates();
    }

    private View createRoot() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_APP_BG);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        progressBar.setVisibility(View.GONE);

        statusText = new TextView(this);
        statusText.setTextColor(COLOR_TEXT_MUTED);
        statusText.setTextSize(11);
        statusText.setGravity(Gravity.CENTER_VERTICAL);
        statusText.setPadding(dp(12), 0, dp(12), 0);
        statusText.setSingleLine(true);
        setStatus("Ready");
        applySystemBarInsets(root);
        return root;
    }

    private void applySystemBarInsets(View view) {
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int systemBottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
                int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
                bottom = Math.max(systemBottom, imeBottom);
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

    private void renderServerScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_SERVERS;
        projectList = null;
        sessionGroupList = null;
        root.removeAllViews();
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = pageContent();
        List<String> serverUrls = savedServerUrls();
        content.addView(createPageHeader(
                serverUrls.size() + " nodes",
                "Servers",
                "＋ Add",
                view -> promptCustomServer()
        ));
        for (String url : serverUrls) {
            content.addView(serverProfileCard(url, !isPresetServer(url)), matchWrap());
        }

        content.addView(createServerUtilityRow());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        setStatus("Servers");
    }

    private View serverProfileCard(String url, boolean removable) {
        String label = url.replace("http://", "").replace(":3000", "");
        boolean selected = url.equals(getServerUrl());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(13), 0, 0, 0);
        card.setBackground(cardBackground());
        card.setHapticFeedbackEnabled(true);
        card.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            selectServer(url);
            openSessionPage();
        });

        TextView icon = new TextView(this);
        icon.setText("▥");
        icon.setTextColor(COLOR_TEXT_MUTED);
        icon.setTextSize(20);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(COLOR_CARD_ALT, 8, Color.TRANSPARENT, 0));
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setPadding(dp(11), 0, dp(6), 0);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextColor(selected ? COLOR_ACCENT : COLOR_TEXT_DIM);
        dot.setTextSize(10);
        nameRow.addView(dot);
        TextView title = new TextView(this);
        title.setText("  " + serverDisplayName(url));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        nameRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView meta = bodyText(url);
        meta.setTextSize(11);
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        textBlock.addView(nameRow);
        textBlock.addView(meta);
        card.addView(textBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout stateBlock = new LinearLayout(this);
        stateBlock.setOrientation(LinearLayout.VERTICAL);
        stateBlock.setGravity(Gravity.END);
        TextView active = new TextView(this);
        active.setText(selected && lastActiveSessionCount >= 0 ? String.valueOf(lastActiveSessionCount) : "0");
        active.setTextColor(COLOR_ACCENT);
        active.setTextSize(13);
        active.setTypeface(Typeface.MONOSPACE);
        TextView total = new TextView(this);
        total.setText("of " + (selected && lastSessionCount >= 0 ? lastSessionCount : 0));
        total.setTextColor(COLOR_TEXT_DIM);
        total.setTextSize(9);
        total.setTypeface(Typeface.MONOSPACE);
        stateBlock.addView(active);
        stateBlock.addView(total);
        card.addView(stateBlock);
        if (removable) {
            Button remove = terminalToolButton("×", view -> confirmRemoveSavedServer(url));
            remove.setContentDescription("Remove " + label);
            remove.setTextColor(COLOR_DANGER);
            card.addView(remove);
        }

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextColor(COLOR_TEXT_DIM);
        chevron.setTextSize(22);
        chevron.setGravity(Gravity.CENTER);
        card.addView(chevron, new LinearLayout.LayoutParams(dp(24), dp(36)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(68)
        );
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private void renderSessionScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_SESSIONS;
        projectList = null;
        root.removeAllViews();
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3)
        ));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = pageContent();
        content.addView(createSessionPageHeader());
        sessionGroupList = new LinearLayout(this);
        sessionGroupList.setOrientation(LinearLayout.VERTICAL);
        sessionGroupList.addView(projectStateText("Loading sessions and groups..."), matchWrap());
        content.addView(sessionGroupList, matchWrap());
        content.addView(createNewGroupCard(), matchWrap());
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        setStatus("Sessions");
    }

    private void openSessionPage() {
        renderSessionScreen();
        refreshSessions();
    }

    private View sessionSummaryBlock() {
        View block = infoBlock(
                "Active API",
                "Server: " + getServerUrl() + "\nSessions: loading"
        );
        sessionSummaryText = findBodyText(block);
        return block;
    }

    private View createPageHeader(
            String eyebrow,
            String titleText,
            String actionLabel,
            View.OnClickListener action
    ) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.BOTTOM);
        header.setPadding(dp(2), dp(6), dp(2), dp(18));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView eyebrowView = new TextView(this);
        eyebrowView.setText(eyebrow.toUpperCase(java.util.Locale.ROOT));
        eyebrowView.setTextColor(COLOR_TEXT_MUTED);
        eyebrowView.setTextSize(10);
        eyebrowView.setTypeface(Typeface.MONOSPACE);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        titleBlock.addView(eyebrowView);
        titleBlock.addView(title);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button button = primaryButton(actionLabel, action);
        header.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        ));
        return header;
    }

    private View createServerUtilityRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, 0);
        row.addView(compactButton("Probe all", view -> probeServerProfiles()));
        row.addView(compactButton("Update", view -> renderUpdateScreen()));
        row.addView(compactButton("More", view -> showMainNavigation()));
        return row;
    }

    private View createSessionPageHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(2), dp(4), dp(2), dp(16));

        Button back = toolbarButton("‹  Servers", view -> renderServerScreen());
        back.setTextColor(COLOR_TEXT_MUTED);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setPadding(0, 0, dp(10), 0);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(34)
        );
        backParams.bottomMargin = dp(5);
        header.addView(back, backParams);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView endpoint = new TextView(this);
        endpoint.setText(getServerUrl().replace("http://", "").replace("https://", ""));
        endpoint.setTextColor(COLOR_TEXT_MUTED);
        endpoint.setTextSize(10);
        endpoint.setTypeface(Typeface.MONOSPACE);
        TextView title = new TextView(this);
        title.setText(serverDisplayName(getServerUrl()));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setIncludeFontPadding(false);
        titleBlock.addView(endpoint);
        titleBlock.addView(title);
        row.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button refresh = terminalToolButton("↻", view -> refreshSessions());
        refresh.setContentDescription("Refresh sessions");
        row.addView(refresh);
        row.addView(primaryButton("＋ Session", view -> promptCreateSession()), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        ));
        header.addView(row, matchWrap());
        return header;
    }

    private View createNewGroupCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(11), dp(12), dp(12));
        card.setBackground(cardBackground());

        TextView label = new TextView(this);
        label.setText("NEW GROUP");
        label.setTextColor(COLOR_TEXT_DIM);
        label.setTextSize(9);
        label.setTypeface(Typeface.MONOSPACE);
        card.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(7), 0, 0);
        EditText input = new EditText(this);
        input.setHint("group name");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        styleInput(input);
        row.addView(input, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button create = compactButton("Create", view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                showMessage("Group name is empty");
                return;
            }
            runApiAction("Create kanban project", () -> api.createKanbanProject(name, "~", ""));
        });
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
        );
        createParams.leftMargin = dp(8);
        row.addView(create, createParams);
        card.addView(row, matchWrap());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.topMargin = dp(8);
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);
        return card;
    }

    private Button primaryButton(String label, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        button.setTextColor(Color.rgb(14, 38, 24));
        button.setTextSize(12);
        button.setBackground(rounded(COLOR_ACCENT, 8, COLOR_ACCENT, 1));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private String serverDisplayName(String url) {
        Uri parsed = Uri.parse(url);
        String host = parsed.getHost();
        if (host == null || host.isEmpty()) {
            return "tmux server";
        }
        if (host.startsWith("100.")) {
            int separator = host.lastIndexOf('.');
            return separator >= 0 ? "node-" + host.substring(separator + 1) : "Tailscale node";
        }
        return host;
    }

    private void promptCustomServer() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("100.64.0.x:3000");
        input.setText(getServerUrl());
        input.setSelectAllOnFocus(true);
        styleInput(input);
        new AlertDialog.Builder(this)
                .setTitle("Add server")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String url = normalizeServerUrl(input.getText().toString());
                    saveCustomServer(url);
                    selectServer(url);
                    openSessionPage();
                })
                .show();
    }

    private List<String> savedServerUrls() {
        List<String> urls = new ArrayList<>();
        Collections.addAll(urls, SERVER_PROFILES);
        Set<String> custom = prefs.getStringSet("custom_server_urls", Collections.emptySet());
        List<String> sortedCustom = new ArrayList<>(custom);
        Collections.sort(sortedCustom);
        for (String url : sortedCustom) {
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        String active = getServerUrl();
        if (!urls.contains(active)) {
            urls.add(0, active);
        }
        return urls;
    }

    private boolean isPresetServer(String url) {
        for (String preset : SERVER_PROFILES) {
            if (preset.equals(url)) {
                return true;
            }
        }
        return false;
    }

    private void saveCustomServer(String url) {
        if (isPresetServer(url)) {
            return;
        }
        Set<String> custom = new HashSet<>(
                prefs.getStringSet("custom_server_urls", Collections.emptySet())
        );
        custom.add(url);
        prefs.edit().putStringSet("custom_server_urls", custom).apply();
    }

    private void confirmRemoveSavedServer(String url) {
        new AlertDialog.Builder(this)
                .setTitle("Remove server")
                .setMessage("Remove " + url + " from this device?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    Set<String> custom = new HashSet<>(
                            prefs.getStringSet("custom_server_urls", Collections.emptySet())
                    );
                    custom.remove(url);
                    SharedPreferences.Editor editor = prefs.edit()
                            .putStringSet("custom_server_urls", custom);
                    if (url.equals(getServerUrl())) {
                        editor.putString("server_url", DEFAULT_TAILSCALE_URL);
                        api = new SessionApiClient(DEFAULT_TAILSCALE_URL);
                        connectAppEvents();
                    }
                    editor.apply();
                    renderServerScreen();
                })
                .show();
    }

    private void showMainNavigation() {
        String[] items = {"Projects", "Tools", "Update", "About"};
        new AlertDialog.Builder(this)
                .setTitle("tmuxctl")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        renderProjectsScreen();
                    } else if (which == 1) {
                        renderToolsScreen();
                    } else if (which == 2) {
                        renderUpdateScreen();
                    } else {
                        renderAboutScreen();
                    }
                })
                .show();
    }

    private void renderToolsScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_TOOLS;
        projectList = null;
        sessionGroupList = null;
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

    private void renderProjectsScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_PROJECTS;
        sessionGroupList = null;
        root.removeAllViews();
        root.addView(createServerBar(), matchWrap());
        root.addView(createMainTabs(PAGE_PROJECTS), new LinearLayout.LayoutParams(
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
                "Project groups",
                "Kanban projects group tmux sessions and group messages through the server API."
        ));
        content.addView(sectionTitle("Projects"));
        content.addView(actionPanel(
                actionButton("Refresh", view -> refreshProjects()),
                actionButton("New project", view -> promptCreateKanbanProject()),
                actionButton("Delete project", view -> promptDeleteKanbanProject()),
                actionButton("Remove session", view -> promptRemoveKanbanSession())
        ));
        projectList = new LinearLayout(this);
        projectList.setOrientation(LinearLayout.VERTICAL);
        content.addView(projectList, matchWrap());

        content.addView(sectionTitle("Group messages"));
        content.addView(actionPanel(
                actionButton("Messages", view -> promptGroupMessages()),
                actionButton("Send message", view -> promptSendGroupMessage()),
                actionButton("Scan message", view -> promptScanGroupMessage()),
                actionButton("Post hook", view -> promptPostHookEvent())
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
        setStatus("Projects");
        refreshProjects();
    }

    private void renderUpdateScreen() {
        closeTerminalSocket();
        activeSessionName = null;
        activeMainPage = PAGE_UPDATE;
        projectList = null;
        sessionGroupList = null;
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
                appIdentityText() + "\n"
                        + "Update source: " + updateSourceHost() + "\n"
                        + prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL)
        ));
        content.addView(sectionTitle("Update"));
        content.addView(actionPanel(
                actionButton("Auto check", view -> updateManager.check(true)),
                actionButton("Gitea", view -> updateManager.checkGitea(true)),
                actionButton("GitHub", view -> updateManager.checkGithub(true)),
                actionButton("Preview", view -> updateManager.checkPreview(true)),
                actionButton("Selected", view -> updateManager.checkSelected(true)),
                actionButton("Source", view -> showUpdateSourcePicker()),
                actionButton("APK", view -> updateManager.openApkDownload())
        ));
        content.addView(sectionTitle("Permissions"));
        content.addView(infoBlock("Android", permissionSummary()));
        content.addView(actionPanel(
                actionButton("Install permission", view -> openInstallPermissionSettings()),
                actionButton("Notifications", view -> requestNotificationPermission()),
                actionButton("App settings", view -> openAppSettings()),
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
        projectList = null;
        sessionGroupList = null;
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
                        + appIdentityText() + "\n"
                        + "Package " + getPackageName()
        ));
        content.addView(infoBlock(
                "Protocol",
                "API base: " + getServerUrl() + "\n"
                        + "HTTP on port 3000: /api/health, /api/sessions, session actions, kanban, messages, images.\n"
                        + "WebSocket: /ws/terminal uses attach/input/resize/scroll/clear-history; /ws/events streams session and hook events."
        ));
        content.addView(infoBlock(
                "Update policy",
                "Selected source: " + updateSourceHost() + "\n"
                        + prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL) + "\n"
                        + "Auto check tries Gitea first, then GitHub if Gitea cannot be reached. Manual Gitea/GitHub/Selected checks are also available. APK downloads are cached by version and reused after Android install permission is granted."
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
        toolbar.setBackgroundColor(COLOR_BAR);

        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setOrientation(LinearLayout.HORIZONTAL);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);

        urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setTextColor(COLOR_TEXT);
        urlField.setHintTextColor(COLOR_TEXT_DIM);
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
        if (PAGE_SERVERS.equals(activeMainPage)) {
            actionRow.addView(toolbarButton("Use", view -> saveServerAndRefresh()));
            actionRow.addView(toolbarButton("Probe", view -> probeSingleServer(currentUrlInput())));
            return;
        }
        if (PAGE_SESSIONS.equals(activeMainPage)) {
            actionRow.addView(toolbarButton("New", view -> promptCreateSession()));
            actionRow.addView(toolbarButton("Refresh", view -> refreshSessions()));
            actionRow.addView(toolbarButton("Probe", view -> probeServerProfiles()));
            return;
        }
        if (PAGE_PROJECTS.equals(activeMainPage)) {
            actionRow.addView(toolbarButton("Refresh", view -> refreshProjects()));
            actionRow.addView(toolbarButton("New", view -> promptCreateKanbanProject()));
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
        scroller.setBackgroundColor(COLOR_BAR);

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
        scroller.setBackgroundColor(COLOR_APP_BG);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(5), dp(8), dp(5));
        row.addView(navButton(PAGE_SERVERS, selected, view -> renderServerScreen()));
        row.addView(navButton(PAGE_SESSIONS, selected, view -> openSessionPage()));
        row.addView(navButton(PAGE_PROJECTS, selected, view -> renderProjectsScreen()));
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
        button.setTextColor(active ? Color.rgb(5, 18, 18) : COLOR_TEXT);
        button.setBackground(active
                ? rounded(COLOR_ACCENT, 8, COLOR_ACCENT, 1)
                : buttonBackground());
        button.setMinWidth(dp(74));
        button.setMinimumWidth(dp(74));
        return button;
    }

    private LinearLayout pageContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(24));
        return content;
    }

    private TextView sectionTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(COLOR_ACCENT_WARM);
        title.setTextSize(12);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), dp(12), dp(2), dp(6));
        return title;
    }

    private View infoBlock(String title, String body) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(12), dp(10), dp(12), dp(10));
        block.setBackground(cardBackground());

        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(COLOR_TEXT);
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

    private TextView findBodyText(View block) {
        if (!(block instanceof LinearLayout)) {
            return null;
        }
        LinearLayout layout = (LinearLayout) block;
        if (layout.getChildCount() < 2 || !(layout.getChildAt(1) instanceof TextView)) {
            return null;
        }
        return (TextView) layout.getChildAt(1);
    }

    private LinearLayout actionPanel(Button... buttons) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(6), dp(6), dp(1));
        panel.setBackground(panelBackground());
        LinearLayout row = null;
        for (int index = 0; index < buttons.length; index++) {
            if (index % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                panel.addView(row, matchWrap());
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
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
        if (urlField != null) {
            urlField.setText(url);
        }
        setStatus("Selected " + url);
        connectAppEvents();
        if (PAGE_SESSIONS.equals(activeMainPage)) {
            refreshSessions();
        } else if (PAGE_PROJECTS.equals(activeMainPage)) {
            refreshProjects();
        }
    }

    private String currentUrlInput() {
        if (urlField == null) {
            return getServerUrl();
        }
        String text = urlField.getText().toString().trim();
        return text.isEmpty() ? getServerUrl() : text;
    }

    private void refreshSessions() {
        setStatus("Loading sessions from " + api.getBaseUrl());
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                List<SessionSummary> sessions = api.getSessions();
                String projects = "";
                if (sessionGroupList != null) {
                    try {
                        projects = api.kanbanProjects();
                    } catch (Exception ignored) {
                    }
                }
                String finalProjects = projects;
                runOnUiThread(() -> {
                    lastSessionCount = sessions.size();
                    lastActiveSessionCount = 0;
                    for (SessionSummary session : sessions) {
                        String status = session.status == null ? "" : session.status.toLowerCase(java.util.Locale.ROOT);
                        if (status.contains("attach") || status.contains("active")) {
                            lastActiveSessionCount++;
                        }
                    }
                    if (sessionGroupList != null) {
                        renderGroupedSessionList(sessions, finalProjects);
                    } else {
                        renderSessionList(sessions);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (sessionSummaryText != null) {
                        sessionSummaryText.setText("Server: " + getServerUrl() + "\nSessions: failed - " + error.getMessage());
                    }
                    if (sessionGroupList != null) {
                        sessionGroupList.removeAllViews();
                        sessionGroupList.addView(projectStateText("Session load failed:\n" + error.getMessage()), matchWrap());
                    }
                    showMessage("Session load failed: " + error.getMessage());
                });
            } finally {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }
        });
    }

    private void renderGroupedSessionList(List<SessionSummary> sessions, String projectsText) {
        if (sessionGroupList == null) {
            return;
        }
        sessionGroupList.removeAllViews();
        Set<String> grouped = new HashSet<>();
        try {
            JSONObject object = new JSONObject(projectsText == null || projectsText.isEmpty() ? "{}" : projectsText);
            JSONArray projects = object.optJSONArray("projects");
            for (int projectIndex = 0; projects != null && projectIndex < projects.length(); projectIndex++) {
                JSONObject project = projects.optJSONObject(projectIndex);
                if (project == null) {
                    continue;
                }
                JSONArray agents = project.optJSONArray("agents");
                List<SessionSummary> groupSessions = new ArrayList<>();
                for (int agentIndex = 0; agents != null && agentIndex < agents.length(); agentIndex++) {
                    String sessionName = agentSessionName(agents.optJSONObject(agentIndex));
                    SessionSummary summary = findSession(sessions, sessionName);
                    if (summary != null) {
                        groupSessions.add(summary);
                        grouped.add(summary.name);
                    }
                }
                sessionGroupList.addView(sessionGroupSection(
                        project.optString("name", "Project"),
                        groupSessions,
                        true
                ), matchWrap());
            }
        } catch (Exception ignored) {
        }

        List<SessionSummary> ungrouped = new ArrayList<>();
        for (SessionSummary session : sessions) {
            if (!grouped.contains(session.name)) {
                ungrouped.add(session);
            }
        }
        sessionGroupList.addView(sessionGroupSection("Ungrouped", ungrouped, false), matchWrap());
        setStatus("Loaded " + sessions.size() + " sessions");
    }

    private SessionSummary findSession(List<SessionSummary> sessions, String name) {
        for (SessionSummary session : sessions) {
            if (session.name.equals(name)) {
                return session;
            }
        }
        return null;
    }

    private View sessionGroupSection(String name, List<SessionSummary> sessions, boolean project) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(2), dp(9), dp(2), dp(7));
        TextView icon = new TextView(this);
        icon.setText(project ? "▰" : "≡");
        icon.setTextColor(project ? COLOR_ACCENT_WARM : COLOR_TEXT_DIM);
        icon.setTextSize(13);
        heading.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(24)));
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(project ? COLOR_TEXT : COLOR_TEXT_MUTED);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(title);
        TextView count = new TextView(this);
        count.setText("  " + sessions.size());
        count.setTextColor(COLOR_TEXT_DIM);
        count.setTextSize(11);
        count.setTypeface(Typeface.MONOSPACE);
        heading.addView(count);
        section.addView(heading);

        if (sessions.isEmpty()) {
            TextView empty = bodyText(project ? "No sessions in this group" : "Nothing ungrouped");
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(11);
            empty.setPadding(dp(12), dp(14), dp(12), dp(14));
            empty.setBackground(rounded(Color.TRANSPARENT, 8, COLOR_BORDER_SOFT, 1));
            section.addView(empty, matchWrap());
        } else {
            for (SessionSummary session : sessions) {
                section.addView(sessionRow(session), matchWrap());
            }
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(7);
        section.setLayoutParams(params);
        return section;
    }

    private void refreshProjects() {
        if (projectList != null) {
            projectList.removeAllViews();
            projectList.addView(projectStateText("Loading projects..."), matchWrap());
        }
        setStatus("Loading projects from " + api.getBaseUrl());
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                String text = api.kanbanProjects();
                runOnUiThread(() -> {
                    renderProjectList(text);
                    setStatus("Loaded projects");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (projectList != null) {
                        projectList.removeAllViews();
                        projectList.addView(projectStateText("Project load failed:\n" + error.getMessage()), matchWrap());
                    }
                    showMessage("Project load failed: " + error.getMessage());
                });
            } finally {
                runOnUiThread(() -> progressBar.setVisibility(View.GONE));
            }
        });
    }

    private void renderProjectList(String text) {
        if (projectList == null) {
            return;
        }
        projectList.removeAllViews();
        try {
            JSONObject rootObject = new JSONObject(text == null || text.isEmpty() ? "{}" : text);
            JSONArray projects = rootObject.optJSONArray("projects");
            if (projects == null || projects.length() == 0) {
                projectList.addView(projectStateText("No projects"), matchWrap());
                return;
            }
            for (int index = 0; index < projects.length(); index++) {
                projectList.addView(projectCard(projects.getJSONObject(index)), matchWrap());
            }
        } catch (Exception error) {
            projectList.addView(projectStateText(text == null || text.isEmpty() ? "(empty)" : text), matchWrap());
        }
    }

    private TextView projectStateText(String text) {
        TextView view = bodyText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(rounded(COLOR_FIELD, 8, COLOR_BORDER_SOFT, 1));
        return view;
    }

    private View projectCard(JSONObject project) {
        String name = project.optString("name", "(unnamed)");
        String path = project.optString("path", "");
        String server = project.isNull("server") ? "" : project.optString("server", "");
        JSONArray agents = project.optJSONArray("agents");
        int agentCount = agents == null ? 0 : agents.length();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackground(cardBackground());

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        String detail = "path:" + defaultValue(path, "~")
                + (server.isEmpty() ? "" : "  server:" + server)
                + "  agents:" + agentCount;
        TextView meta = bodyText(detail);
        meta.setPadding(0, dp(4), 0, dp(8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(toolbarButton("Messages", view -> showRaw("Group messages: " + name, () -> api.groupMessages(name))));
        actions.addView(toolbarButton("Add", view -> promptAddKanbanSessionToProject(name)));
        actions.addView(toolbarButton("Delete", view -> runApiAction("Delete kanban project", () -> api.deleteKanbanProject(name))));

        card.addView(title);
        card.addView(meta);
        card.addView(actions);

        if (agents != null && agents.length() > 0) {
            TextView agentsTitle = bodyText("Agents");
            agentsTitle.setTextColor(COLOR_TEXT_DIM);
            agentsTitle.setTypeface(Typeface.DEFAULT_BOLD);
            agentsTitle.setPadding(0, dp(10), 0, dp(4));
            card.addView(agentsTitle);
            for (int index = 0; index < agents.length(); index++) {
                JSONObject agent = agents.optJSONObject(index);
                if (agent != null) {
                    card.addView(projectAgentRow(name, agent), matchWrap());
                }
            }
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        return card;
    }

    private View projectAgentRow(String projectName, JSONObject agent) {
        String sessionName = defaultValue(agent.optString("sessionName", ""), agent.optString("name", ""));
        String label = defaultValue(agent.optString("name", ""), sessionName);
        String kind = agent.optString("kind", "session");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView name = bodyText(kind + "  " + label);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(0, dp(38), 1));
        if (!sessionName.isEmpty()) {
            row.addView(toolbarButton("Open", view -> openTerminal(sessionName)));
            row.addView(toolbarButton("Remove", view -> runApiAction("Remove kanban session", () ->
                    api.removeKanbanSession(projectName, sessionName, false))));
        }
        return row;
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
        if (sessionSummaryText != null) {
            sessionSummaryText.setText("Server: " + getServerUrl() + "\nSessions: " + sessions.size());
        }
        setStatus("Loaded " + sessions.size() + " sessions");
    }

    private View sessionRow(SessionSummary session) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(6), dp(9));
        row.setBackground(cardBackground());
        row.setHapticFeedbackEnabled(true);
        row.setOnClickListener(view -> openTerminal(session.name));
        row.setOnLongClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            showSessionActions(session.name);
            return true;
        });

        boolean attached = session.status != null
                && (session.status.toLowerCase(java.util.Locale.ROOT).contains("attach")
                || session.status.toLowerCase(java.util.Locale.ROOT).contains("active"));

        TextView icon = new TextView(this);
        icon.setText(">_");
        icon.setTextColor(attached ? COLOR_ACCENT : COLOR_TEXT_MUTED);
        icon.setTextSize(12);
        icon.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(attached ? COLOR_ACCENT_DARK : COLOR_CARD_ALT, 8, Color.TRANSPARENT, 0));
        row.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        textBlock.setPadding(dp(10), 0, dp(4), 0);
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(session.name);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (attached) {
            TextView chip = new TextView(this);
            chip.setText("attached");
            chip.setTextColor(COLOR_ACCENT);
            chip.setTextSize(9);
            chip.setTypeface(Typeface.MONOSPACE);
            chip.setPadding(dp(5), dp(1), dp(5), dp(1));
            chip.setBackground(rounded(COLOR_ACCENT_DARK, 5, Color.TRANSPARENT, 0));
            titleRow.addView(chip);
        }
        TextView meta = bodyText(
                session.windows + (session.windows == 1 ? " window" : " windows")
                        + "  ·  " + session.paneCount + (session.paneCount == 1 ? " pane" : " panes")
                        + textPart(session.currentCommand)
        );
        meta.setTextSize(11);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        textBlock.addView(titleRow, matchWrap());
        textBlock.addView(meta);
        row.addView(textBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button move = terminalToolButton("⇄", view -> promptAddKanbanSession(session.name));
        move.setContentDescription("Move " + session.name + " to project");
        row.addView(move);
        Button kill = terminalToolButton("×", view -> confirmKill(session.name));
        kill.setContentDescription("Kill session");
        kill.setTextColor(COLOR_DANGER);
        row.addView(kill);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.bottomMargin = dp(7);
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
        closeTerminalSocket();
        activeSessionName = sessionName;
        projectList = null;
        sessionGroupList = null;
        terminalScreen = new TerminalScreenBuffer(DEFAULT_TERMINAL_COLS, DEFAULT_TERMINAL_ROWS);
        queuedTerminalInput.setLength(0);
        terminalConnected = false;
        terminalRenderPending = false;
        terminalSelectionEnabled = false;
        terminalFollowOutput = true;
        terminalKeyPage = 0;
        terminalPathStatus = "path loading";
        terminalSocketStatus = "terminal idle";
        terminalEventStatus = "events listening";
        lastTerminalRenderMs = 0L;
        terminalCols = DEFAULT_TERMINAL_COLS;
        terminalRows = DEFAULT_TERMINAL_ROWS;
        root.removeAllViews();
        root.addView(createTerminalTopBar(sessionName), matchWrap());
        root.addView(createTerminalGroupBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(40)
        ));

        terminalScroll = new ScrollView(this);
        terminalScroll.setFillViewport(true);
        terminalScroll.setBackgroundColor(COLOR_TERMINAL_BG);
        terminalText = new TextView(this);
        terminalText.setTextColor(COLOR_TEXT);
        terminalText.setTextSize(13);
        terminalText.setTypeface(Typeface.MONOSPACE);
        terminalText.setIncludeFontPadding(false);
        terminalText.setLineSpacing(0, 1.05f);
        terminalText.setGravity(Gravity.BOTTOM | Gravity.START);
        terminalText.setTextIsSelectable(terminalSelectionEnabled);
        terminalText.setPadding(dp(2), dp(8), dp(2), dp(8));
        terminalText.setBackgroundColor(COLOR_TERMINAL_BG);
        terminalScroll.addView(terminalText, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        terminalScroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                resizeTerminalToViewport(false));
        terminalScroll.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) ->
                terminalFollowOutput = !view.canScrollVertically(1));
        root.addView(terminalScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        ));
        terminalAccessoryBar = createAccessoryBar();
        root.addView(terminalAccessoryBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(TERMINAL_KEYS_HEIGHT_DP)
        ));
        terminalComposerBar = createComposerBar();
        root.addView(terminalComposerBar, matchWrap());
        terminalScroll.post(() -> {
            resizeTerminalToViewport(false);
            connectTerminal(sessionName);
            refreshTerminalGroupSessions(sessionName);
            refreshTerminalSessionMeta(sessionName);
        });
        inputField.post(() -> {
            inputField.requestFocus();
            hideKeyboard();
        });
    }

    private LinearLayout createTerminalTopBar(String sessionName) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(7), dp(6), dp(7), dp(6));
        bar.setBackgroundColor(COLOR_BAR);
        bar.addView(terminalToolButton("‹", view -> openSessionPage()));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setGravity(Gravity.CENTER_VERTICAL);
        titleBlock.setPadding(dp(9), 0, dp(7), 0);

        TextView title = new TextView(this);
        title.setText(sessionName);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(13);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setIncludeFontPadding(false);

        terminalMetaText = new TextView(this);
        terminalMetaText.setTextColor(COLOR_TEXT_DIM);
        terminalMetaText.setTextSize(9);
        terminalMetaText.setTypeface(Typeface.MONOSPACE);
        terminalMetaText.setSingleLine(true);
        terminalMetaText.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        terminalMetaText.setIncludeFontPadding(false);
        updateTerminalMeta();

        titleBlock.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(17)
        ));
        titleBlock.addView(terminalMetaText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(13)
        ));
        bar.addView(titleBlock, new LinearLayout.LayoutParams(0, dp(32), 1));
        bar.addView(terminalToolButton("☰", view -> showTerminalActions(sessionName)));
        return bar;
    }

    private HorizontalScrollView createTerminalGroupBar() {
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(COLOR_PANEL);

        terminalGroupRow = new LinearLayout(this);
        terminalGroupRow.setOrientation(LinearLayout.HORIZONTAL);
        terminalGroupRow.setGravity(Gravity.CENTER_VERTICAL);
        terminalGroupRow.setPadding(dp(9), dp(5), dp(9), dp(5));
        terminalGroupRow.addView(groupLabel("group..."));

        scroller.addView(terminalGroupRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        return scroller;
    }

    private void refreshTerminalGroupSessions(String sessionName) {
        executor.execute(() -> {
            try {
                String text = api.kanbanProjects();
                runOnUiThread(() -> renderTerminalGroupSessions(sessionName, text));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (sessionName.equals(activeSessionName) && terminalGroupRow != null) {
                        terminalGroupRow.removeAllViews();
                        terminalGroupRow.addView(groupLabel("group failed"));
                    }
                });
            }
        });
    }

    private void refreshTerminalSessionMeta(String sessionName) {
        executor.execute(() -> {
            try {
                List<SessionSummary> sessions = api.getSessions();
                String path = "";
                String command = "";
                for (SessionSummary session : sessions) {
                    if (sessionName.equals(session.name)) {
                        path = defaultValue(session.currentPath, "");
                        command = defaultValue(session.currentCommand, "");
                        break;
                    }
                }
                String meta = path.isEmpty() ? "path unavailable" : path;
                if (!command.isEmpty()) {
                    meta = meta + "  ·  " + command;
                }
                String finalMeta = meta;
                runOnUiThread(() -> {
                    if (sessionName.equals(activeSessionName)) {
                        terminalPathStatus = finalMeta;
                        updateTerminalMeta();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (sessionName.equals(activeSessionName)) {
                        terminalPathStatus = "path unavailable";
                        updateTerminalMeta();
                    }
                });
            }
        });
    }

    private void updateTerminalMeta() {
        if (terminalMetaText == null) {
            return;
        }
        StringBuilder meta = new StringBuilder();
        meta.append(defaultValue(terminalPathStatus, "path loading"));
        if (!terminalSocketStatus.isEmpty()) {
            meta.append("  ·  ").append(terminalSocketStatus);
        }
        if (!terminalEventStatus.isEmpty()) {
            meta.append("  ·  ").append(terminalEventStatus);
        }
        terminalMetaText.setText(meta.toString());
    }

    private void renderTerminalGroupSessions(String sessionName, String text) {
        if (!sessionName.equals(activeSessionName) || terminalGroupRow == null) {
            return;
        }
        terminalGroupRow.removeAllViews();
        try {
            JSONObject rootObject = new JSONObject(text == null || text.isEmpty() ? "{}" : text);
            JSONArray projects = rootObject.optJSONArray("projects");
            if (projects == null) {
                renderUngroupedTerminalSessions(sessionName, null);
                return;
            }
            Set<String> groupedSessions = collectGroupedSessions(projects);
            for (int projectIndex = 0; projectIndex < projects.length(); projectIndex++) {
                JSONObject project = projects.optJSONObject(projectIndex);
                if (project == null) {
                    continue;
                }
                JSONArray agents = project.optJSONArray("agents");
                if (!projectContainsSession(agents, sessionName)) {
                    continue;
                }
                terminalGroupRow.addView(groupLabel(compactLabel(project.optString("name", "group"), 14)));
                for (int agentIndex = 0; agents != null && agentIndex < agents.length(); agentIndex++) {
                    JSONObject agent = agents.optJSONObject(agentIndex);
                    String agentSession = agentSessionName(agent);
                    if (!agentSession.isEmpty()) {
                        terminalGroupRow.addView(groupSessionButton(agentSession, agentSession.equals(sessionName)));
                    }
                }
                return;
            }
            renderUngroupedTerminalSessions(sessionName, groupedSessions);
        } catch (Exception error) {
            terminalGroupRow.addView(groupLabel("group parse"));
        }
    }

    private Set<String> collectGroupedSessions(JSONArray projects) {
        Set<String> names = new HashSet<>();
        for (int projectIndex = 0; projectIndex < projects.length(); projectIndex++) {
            JSONObject project = projects.optJSONObject(projectIndex);
            JSONArray agents = project == null ? null : project.optJSONArray("agents");
            for (int agentIndex = 0; agents != null && agentIndex < agents.length(); agentIndex++) {
                String sessionName = agentSessionName(agents.optJSONObject(agentIndex));
                if (!sessionName.isEmpty()) {
                    names.add(sessionName);
                }
            }
        }
        return names;
    }

    private void renderUngroupedTerminalSessions(String sessionName, Set<String> groupedSessions) {
        terminalGroupRow.addView(groupLabel("ungrouped..."));
        executor.execute(() -> {
            try {
                List<SessionSummary> sessions = api.getSessions();
                runOnUiThread(() -> {
                    if (!sessionName.equals(activeSessionName) || terminalGroupRow == null) {
                        return;
                    }
                    terminalGroupRow.removeAllViews();
                    terminalGroupRow.addView(groupLabel("ungrouped"));
                    int count = 0;
                    for (SessionSummary session : sessions) {
                        if (groupedSessions != null && groupedSessions.contains(session.name)) {
                            continue;
                        }
                        terminalGroupRow.addView(groupSessionButton(session.name, session.name.equals(sessionName)));
                        count++;
                    }
                    if (count == 0) {
                        terminalGroupRow.removeAllViews();
                        terminalGroupRow.addView(groupLabel("no group"));
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (sessionName.equals(activeSessionName) && terminalGroupRow != null) {
                        terminalGroupRow.removeAllViews();
                        terminalGroupRow.addView(groupLabel("ungrouped failed"));
                    }
                });
            }
        });
    }

    private boolean projectContainsSession(JSONArray agents, String sessionName) {
        if (agents == null) {
            return false;
        }
        for (int index = 0; index < agents.length(); index++) {
            if (sessionName.equals(agentSessionName(agents.optJSONObject(index)))) {
                return true;
            }
        }
        return false;
    }

    private String agentSessionName(JSONObject agent) {
        if (agent == null) {
            return "";
        }
        return defaultValue(agent.optString("sessionName", ""), agent.optString("name", ""));
    }

    private TextView groupLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(COLOR_TEXT_DIM);
        label.setTextSize(9);
        label.setTypeface(Typeface.MONOSPACE);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(3), 0, dp(7), 0);
        return label;
    }

    private Button groupSessionButton(String sessionName, boolean active) {
        Button button = terminalToolButton(compactLabel(sessionName, 12), view -> {
            if (!sessionName.equals(activeSessionName)) {
                openTerminal(sessionName);
            }
        });
        if (active) {
            button.setTextColor(COLOR_ACCENT);
            button.setBackground(rounded(COLOR_ACCENT_DARK, 6, Color.TRANSPARENT, 0));
        } else {
            button.setTextColor(COLOR_TEXT_MUTED);
            button.setBackground(rounded(Color.TRANSPARENT, 6, Color.TRANSPARENT, 0));
        }
        return button;
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
                "Reconnect",
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
                            connectTerminal(sessionName);
                            break;
                        case 1:
                            terminalScreen.clear();
                            terminalText.setText("");
                            if (terminalSocket != null) {
                                terminalSocket.clearHistory();
                            }
                            break;
                        case 2:
                            runApiAction("Split horizontal", () -> api.splitPane(sessionName, "horizontal"));
                            break;
                        case 3:
                            runApiAction("Split vertical", () -> api.splitPane(sessionName, "vertical"));
                            break;
                        case 4:
                            if (terminalSocket != null) {
                                terminalSocket.scroll(-terminalRows);
                            }
                            break;
                        case 5:
                            if (terminalSocket != null) {
                                terminalSocket.scroll(terminalRows);
                            }
                            break;
                        case 6:
                            showRaw("Session status", () -> api.sessionStatus(sessionName));
                            break;
                        case 7:
                            promptSendCommand(sessionName);
                            break;
                        case 8:
                            sendTerminalInput("\u0002");
                            break;
                        case 9:
                            sendTerminalInput("\u0002d");
                            break;
                        case 10:
                            sendTerminalInput("\u0002c");
                            break;
                        case 11:
                            sendTerminalInput("\u0002n");
                            break;
                        case 12:
                            sendTerminalInput("\u0002p");
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private LinearLayout createComposerBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(8), dp(5), dp(8), dp(7));
        bar.setBackgroundColor(COLOR_BAR);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);

        inputField = new EditText(this);
        inputField.setTextColor(COLOR_TEXT);
        inputField.setHintTextColor(COLOR_TEXT_DIM);
        inputField.setHint("type command or text");
        inputField.setSingleLine(false);
        inputField.setMinLines(1);
        inputField.setMaxLines(2);
        inputField.setCursorVisible(true);
        inputField.setFocusableInTouchMode(true);
        inputField.setGravity(Gravity.TOP | Gravity.START);
        inputField.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        styleComposerInput(inputField);
        inputField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                sendLine();
                return true;
            }
            if (event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.isShiftPressed()
                    && event.getAction() == KeyEvent.ACTION_UP) {
                sendLine();
                return true;
            }
            return false;
        });
        Button image = composerButton("Img", view -> pickImageForSession(activeSessionName));
        image.setContentDescription("Upload image");
        row.addView(image, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        );
        inputParams.leftMargin = dp(5);
        inputParams.rightMargin = dp(5);
        row.addView(inputField, inputParams);
        Button send = composerPrimaryButton("Send", view -> sendLine());
        send.setContentDescription("Send input");
        row.addView(send, new LinearLayout.LayoutParams(dp(48), dp(38)));
        bar.addView(row, matchWrap());
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

    private void promptAddKanbanSessionToProject(String projectName) {
        promptText("Add session to " + projectName, "session", "", sessionName -> api.addKanbanSession(projectName, sessionName));
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

    private void pickImageForSession(String sessionName) {
        pendingImageUploadSession = sessionName == null ? "" : sessionName;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(intent, "Choose image"), IMAGE_PICK_REQUEST);
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
        text.append("Installed app: ").append(appIdentityText()).append('\n');
        text.append("Server: ").append(getServerUrl()).append('\n');
        text.append('\n');
        text.append("Manual APK download:\n");
        text.append("Tap APK on the Update page. The app resolves the APK from the selected update source.\n");
        text.append("Release page:\n");
        text.append("Tap Release on the About page. The app resolves the page from the selected update source.\n");
        text.append('\n');
        text.append("In-app update:\n");
        text.append("Tap Auto check on the Update page to try Gitea first and GitHub only if Gitea cannot be reached. Use Gitea, GitHub, Preview, or Selected to force one source. Preview resolves the mutable Gitea preview attachment for fast UI testing. Each source retries transient network failures before failing. The app downloads one APK per version, verifies SHA-256, then opens Android's installer.\n");
        text.append("Selected manifest: ")
                .append(updateSourceHost())
                .append('\n')
                .append(prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL))
                .append('\n');
        text.append("Available sources:\n");
        text.append("Gitea default: ").append(BuildConfig.DEFAULT_GITEA_UPDATE_URL).append('\n');
        text.append("GitHub optional: ").append(BuildConfig.DEFAULT_GITHUB_UPDATE_URL).append('\n');
        text.append("Preview manual: ").append(BuildConfig.DEFAULT_PREVIEW_UPDATE_URL).append('\n');
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
        text.append("\nOpen App settings for Android's full per-app permission and storage/network controls.");
        return text.toString();
    }

    private void showUpdateSourcePicker() {
        String[] items = {
                "Gitea default: " + BuildConfig.DEFAULT_GITEA_UPDATE_URL,
                "GitHub optional: " + BuildConfig.DEFAULT_GITHUB_UPDATE_URL,
                "Preview manual: " + BuildConfig.DEFAULT_PREVIEW_UPDATE_URL,
                "Custom URL"
        };
        new AlertDialog.Builder(this)
                .setTitle("Update source")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        setUpdateUrl(BuildConfig.DEFAULT_GITEA_UPDATE_URL);
                    } else if (which == 1) {
                        setUpdateUrl(BuildConfig.DEFAULT_GITHUB_UPDATE_URL);
                    } else if (which == 2) {
                        setUpdateUrl(BuildConfig.DEFAULT_PREVIEW_UPDATE_URL);
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

    private void migrateDefaultUpdateSourceToGitea() {
        String current = prefs.getString("update_url", "");
        if (current == null || current.trim().isEmpty() || OLD_GITHUB_DEFAULT_UPDATE_URL.equals(current.trim())) {
            prefs.edit()
                    .putString("update_url", BuildConfig.DEFAULT_GITEA_UPDATE_URL)
                    .apply();
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

    private void probeSingleServer(String url) {
        String normalized = normalizeServerUrl(url);
        progressBar.setVisibility(View.VISIBLE);
        setStatus("Probing " + normalized);
        executor.execute(() -> {
            try {
                SessionApiClient client = new SessionApiClient(normalized);
                JSONObject health = new JSONObject(client.health());
                List<SessionSummary> sessions = client.getSessions();
                String text = normalized + "\n"
                        + "ok: true\n"
                        + "version: " + health.optString("version", "unknown")
                        + " " + health.optString("commit", "") + "\n"
                        + "sessions: " + sessions.size();
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showTextDialog("API probe", text);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    showMessage("Probe failed: " + error.getMessage());
                });
            }
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

    private void openAppSettings() {
        Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())
        );
        startActivity(intent);
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
                    if (PAGE_PROJECTS.equals(activeMainPage)) {
                        refreshProjects();
                    } else if (PAGE_SESSIONS.equals(activeMainPage)) {
                        refreshSessions();
                    } else if (activeSessionName == null) {
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

    private LinearLayout createAccessoryBar() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6), dp(4), dp(6), dp(4));
        panel.setBackgroundColor(COLOR_PANEL);

        LinearLayout tabs = terminalKeyRow();
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        addAccessoryTab(tabs, "Edit", 0);
        addAccessoryTab(tabs, "Ctrl", 1);
        addAccessoryTab(tabs, "Nav", 2);
        addAccessoryTab(tabs, "Sym", 3);
        panel.addView(tabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(24)
        ));

        LinearLayout firstRow = terminalKeyRow();
        LinearLayout secondRow = terminalKeyRow();
        addAccessoryPageKeys(firstRow, secondRow);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        );
        rowParams.topMargin = dp(3);
        panel.addView(firstRow, rowParams);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1
        );
        secondParams.topMargin = dp(3);
        panel.addView(secondRow, secondParams);
        return panel;
    }

    private LinearLayout terminalKeyRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private void addAccessoryPageKeys(LinearLayout firstRow, LinearLayout secondRow) {
        switch (terminalKeyPage) {
            case 1:
                addSoftKey(firstRow, "Esc", "\u001b");
                addSoftKey(firstRow, "Tab", "\t");
                addSoftKey(firstRow, "C-c", "\u0003");
                addSoftKey(firstRow, "C-d", "\u0004");
                addSoftKey(firstRow, "C-z", "\u001a");
                addSoftKey(firstRow, "C-l", "\u000c");
                addSoftKey(secondRow, "C-r", "\u0012");
                addSoftKey(secondRow, "C-a", "\u0001");
                addSoftKey(secondRow, "C-e", "\u0005");
                addSoftKey(secondRow, "C-u", "\u0015");
                addSoftKey(secondRow, "C-k", "\u000b");
                addSoftKey(secondRow, "C-b", "\u0002");
                break;
            case 2:
                addSoftKey(firstRow, "←", "\u001b[D");
                addSoftKey(firstRow, "→", "\u001b[C");
                addSoftKey(firstRow, "↑", "\u001b[A");
                addSoftKey(firstRow, "↓", "\u001b[B");
                addSoftKey(firstRow, "Home", "\u001b[H");
                addSoftKey(firstRow, "End", "\u001b[F");
                addSoftKey(secondRow, "Pg↑", "\u001b[5~");
                addSoftKey(secondRow, "Pg↓", "\u001b[6~");
                addComposerButton(secondRow, "Prev", () -> setTerminalKeyPage(terminalKeyPage - 1));
                addComposerButton(secondRow, "Next", () -> setTerminalKeyPage(terminalKeyPage + 1));
                addSoftKey(secondRow, "Clear", "\u000c");
                addSoftKey(secondRow, "Detach", "\u0002d");
                break;
            case 3:
                addTextKey(firstRow, "~", "~");
                addTextKey(firstRow, "/", "/");
                addTextKey(firstRow, "-", "-");
                addTextKey(firstRow, "_", "_");
                addTextKey(firstRow, ".", ".");
                addTextKey(firstRow, "|", "|");
                addTextKey(secondRow, "&", "&");
                addTextKey(secondRow, ";", ";");
                addTextKey(secondRow, "$", "$");
                addTextKey(secondRow, "\"", "\"");
                addTextKey(secondRow, "'", "'");
                addTextKey(secondRow, "Space", " ");
                break;
            case 0:
            default:
                addComposerButton(firstRow, "←", () -> moveComposerCursor(-1));
                addComposerButton(firstRow, "→", () -> moveComposerCursor(1));
                addSoftKey(firstRow, "↑", "\u001b[A");
                addSoftKey(firstRow, "↓", "\u001b[B");
                addSoftKey(firstRow, "Esc", "\u001b");
                addSoftKey(firstRow, "Tab", "\t");
                addSoftKey(secondRow, "Enter", TERMINAL_ENTER);
                addSoftButton(secondRow, "Paste", view -> pasteClipboard());
                addComposerButton(secondRow, "⌫", this::backspaceComposerText);
                addTextKey(secondRow, "NL", "\n");
                addComposerButton(secondRow, "Bottom", this::scrollTerminalBottom);
                addComposerButton(secondRow, "Select", () -> {
                    terminalSelectionEnabled = !terminalSelectionEnabled;
                    if (terminalText != null) {
                        terminalText.setTextIsSelectable(terminalSelectionEnabled);
                    }
                    setStatus(terminalSelectionEnabled ? "Terminal selection on" : "Terminal selection off");
                });
                break;
        }
    }

    private void connectTerminal(String sessionName) {
        terminalReconnectAttempt = 0;
        terminalConnectionGeneration++;
        cancelTerminalReconnect();
        closeTerminalSocket(false);
        connectTerminalSocket(sessionName, terminalConnectionGeneration);
    }

    private void connectTerminalSocket(String sessionName, int generation) {
        if (activityDestroyed
                || activeSessionName == null
                || !sessionName.equals(activeSessionName)
                || generation != terminalConnectionGeneration) {
            return;
        }
        terminalConnected = false;
        terminalConnecting = true;
        terminalSocketStatus = "terminal connecting";
        updateTerminalMeta();
        setStatus("Connecting " + sessionName);
        resizeTerminalToViewport(false);
        appendTerminal("[connecting]\r\n");
        terminalSocket = new TerminalSocketClient(new TerminalSocketClient.Listener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    if (!isCurrentTerminalConnection(sessionName, generation)) {
                        return;
                    }
                    terminalConnected = true;
                    terminalConnecting = false;
                    terminalReconnectAttempt = 0;
                    terminalSocketStatus = "terminal connected";
                    updateTerminalMeta();
                    setStatus("Connected " + sessionName);
                    resizeTerminalToViewport(true);
                    flushQueuedTerminalInput();
                });
            }

            @Override
            public void onOutput(String data) {
                runOnUiThread(() -> {
                    if (isCurrentTerminalConnection(sessionName, generation)) {
                        appendTerminal(data);
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    if (!isCurrentTerminalConnection(sessionName, generation)) {
                        return;
                    }
                    terminalConnecting = false;
                    appendTerminal("\r\n[error] " + message + "\r\n");
                    terminalSocketStatus = "terminal error";
                    updateTerminalMeta();
                    setStatus("Terminal error: " + message);
                });
            }

            @Override
            public void onClosed() {
                runOnUiThread(() -> {
                    if (!isCurrentTerminalConnection(sessionName, generation)) {
                        return;
                    }
                    terminalConnected = false;
                    terminalConnecting = false;
                    terminalSocketStatus = "terminal disconnected";
                    updateTerminalMeta();
                    setStatus("Disconnected " + sessionName);
                    scheduleTerminalReconnect(sessionName, generation);
                });
            }
        });
        terminalSocket.connect(api.getBaseUrl(), sessionName, terminalCols, terminalRows);
    }

    private void resizeTerminalToViewport(boolean forceSend) {
        if (terminalText == null || terminalScroll == null) {
            return;
        }
        int width = terminalScroll.getWidth();
        int height = terminalScroll.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int horizontalPadding = terminalText.getPaddingLeft() + terminalText.getPaddingRight();
        int verticalPadding = terminalText.getPaddingTop() + terminalText.getPaddingBottom();
        float charWidth = terminalText.getPaint().measureText("W");
        if (charWidth <= 0f) {
            charWidth = dp(8);
        }
        int lineHeight = terminalText.getLineHeight();
        if (lineHeight <= 0) {
            lineHeight = dp(16);
        }
        int usableWidth = Math.max(1, width - horizontalPadding);
        int cols = clamp((int) Math.floor(usableWidth / charWidth), MIN_TERMINAL_COLS, MAX_TERMINAL_COLS);
        int rows = clamp((height - verticalPadding) / lineHeight, MIN_TERMINAL_ROWS, MAX_TERMINAL_ROWS);
        if (cols == terminalCols && rows == terminalRows && !forceSend) {
            return;
        }
        terminalCols = cols;
        terminalRows = rows;
        terminalScreen.resize(cols, rows);
        scheduleTerminalRender();
        TerminalSocketClient socket = terminalSocket;
        if (socket != null && !socket.isClosed() && terminalConnected) {
            socket.resize(cols, rows);
        }
    }

    private void sendLine() {
        if (inputField == null) {
            return;
        }
        String text = inputField.getText().toString();
        if (text.isEmpty()) {
            sendTerminalInput(TERMINAL_ENTER);
            return;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.endsWith(TERMINAL_ENTER)) {
            normalized = normalized + TERMINAL_ENTER;
        }
        terminalFollowOutput = true;
        sendTerminalInput(normalized);
        inputField.setText("");
        setStatus("Sent " + text.length() + " chars");
    }

    private void sendTerminalInput(String data) {
        String sessionName = activeSessionName;
        if (sessionName == null) {
            return;
        }
        TerminalSocketClient socket = terminalSocket;
        if (socket != null && !socket.isClosed() && terminalConnected) {
            socket.sendInput(data);
            setStatus("Sent input");
            return;
        }
        if ((socket != null && !socket.isClosed()) || terminalConnecting || terminalReconnectTask != null) {
            queuedTerminalInput.append(data);
            setStatus("Queued input until terminal connects");
            return;
        }
        executor.execute(() -> {
            try {
                for (int i = 0; i < data.length(); i += 200) {
                    api.sendInput(sessionName, data.substring(i, Math.min(i + 200, data.length())));
                }
                runOnUiThread(() -> setStatus("Sent input"));
            } catch (Exception error) {
                runOnUiThread(() -> showMessage("Input failed: " + error.getMessage()));
            }
        });
    }

    private void flushQueuedTerminalInput() {
        if (!terminalConnected || terminalSocket == null || queuedTerminalInput.length() == 0) {
            return;
        }
        String data = queuedTerminalInput.toString();
        queuedTerminalInput.setLength(0);
        terminalSocket.sendInput(data);
    }

    private void pasteClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            showMessage("Clipboard is empty");
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            showMessage("Clipboard is empty");
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text != null && text.length() > 0) {
            sendTerminalInput(text.toString());
        } else {
            showMessage("Clipboard is empty");
        }
    }

    private void insertComposerText(String text) {
        if (inputField == null) {
            return;
        }
        int start = Math.max(0, inputField.getSelectionStart());
        int end = Math.max(0, inputField.getSelectionEnd());
        inputField.getText().replace(Math.min(start, end), Math.max(start, end), text);
        inputField.requestFocus();
    }

    private void moveComposerCursor(int delta) {
        if (inputField == null) {
            return;
        }
        int current = Math.max(0, inputField.getSelectionEnd());
        int next = clamp(current + delta, 0, inputField.getText().length());
        inputField.setSelection(next);
        inputField.requestFocus();
    }

    private void backspaceComposerText() {
        if (inputField == null) {
            return;
        }
        int start = Math.max(0, inputField.getSelectionStart());
        int end = Math.max(0, inputField.getSelectionEnd());
        int from = Math.min(start, end);
        int to = Math.max(start, end);
        if (from != to) {
            inputField.getText().delete(from, to);
        } else if (from > 0) {
            inputField.getText().delete(from - 1, from);
        }
        inputField.requestFocus();
    }

    private void showKeyboard() {
        if (inputField == null) {
            return;
        }
        inputField.requestFocus();
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        if (inputField == null) {
            return;
        }
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(inputField.getWindowToken(), 0);
        }
    }

    private void toggleTerminalSelection() {
        terminalSelectionEnabled = !terminalSelectionEnabled;
        if (terminalText != null) {
            terminalText.setTextIsSelectable(terminalSelectionEnabled);
        }
        setStatus(terminalSelectionEnabled ? "Terminal selection on" : "Terminal selection off");
    }

    private void scrollTerminalBottom() {
        terminalFollowOutput = true;
        if (terminalScroll != null) {
            terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
        }
        setStatus("Following terminal output");
    }

    private void setTerminalKeyPage(int page) {
        terminalKeyPage = (page + 4) % 4;
        if (activeSessionName != null) {
            renderTerminalControlsOnly();
        }
    }

    private void renderTerminalControlsOnly() {
        int accessoryIndex = terminalAccessoryBar == null ? -1 : root.indexOfChild(terminalAccessoryBar);
        if (accessoryIndex < 0) {
            return;
        }
        root.removeViewAt(accessoryIndex);
        terminalAccessoryBar = createAccessoryBar();
        root.addView(terminalAccessoryBar, accessoryIndex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(TERMINAL_KEYS_HEIGHT_DP)
        ));
    }

    private void appendTerminal(String data) {
        terminalScreen.write(data);
        scheduleTerminalRender();
    }

    private void scheduleTerminalRender() {
        if (terminalRenderPending || terminalText == null) {
            return;
        }
        terminalRenderPending = true;
        long now = System.currentTimeMillis();
        long delay = Math.max(0L, TERMINAL_RENDER_INTERVAL_MS - (now - lastTerminalRenderMs));
        terminalText.postDelayed(() -> {
            terminalRenderPending = false;
            renderTerminalNow();
        }, delay);
    }

    private void renderTerminalNow() {
        if (terminalText == null || terminalScroll == null) {
            return;
        }
        lastTerminalRenderMs = System.currentTimeMillis();
        terminalText.setText(terminalScreen.render());
        if (terminalFollowOutput) {
            terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
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

    private String appIdentityText() {
        return "Version " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.VERSION_CODE + ", "
                + (BuildConfig.DEBUG ? "debug" : "release")
                + ")";
    }

    private String updateSourceHost() {
        String url = prefs.getString("update_url", BuildConfig.DEFAULT_UPDATE_URL);
        if (url == null || url.trim().isEmpty()) {
            url = BuildConfig.DEFAULT_UPDATE_URL;
        }
        String host = Uri.parse(url).getHost();
        return host == null || host.isEmpty() ? "custom" : host;
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
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setIncludeFontPadding(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setBackground(buttonBackground());
        button.setGravity(Gravity.CENTER);
        button.setHapticFeedbackEnabled(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.setStateListAnimator(null);
            button.setElevation(0);
        }
        button.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            view.animate().cancel();
            view.setScaleX(0.96f);
            view.setScaleY(0.96f);
            view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .start();
            setStatus(label);
            listener.onClick(view);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        params.leftMargin = dp(4);
        button.setLayoutParams(params);
        return button;
    }

    private Button terminalToolButton(String label, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        button.setTextSize(label.length() <= 2 ? 16 : 10);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setMinWidth(dp(label.length() <= 2 ? 36 : 48));
        button.setMinimumWidth(dp(label.length() <= 2 ? 36 : 48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(30)
        );
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        button.setLayoutParams(params);
        return button;
    }

    private Button composerButton(String label, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        button.setTextSize(10);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        return button;
    }

    private Button composerPrimaryButton(String label, View.OnClickListener listener) {
        Button button = composerButton(label, listener);
        button.setTextColor(Color.rgb(14, 38, 24));
        button.setTextSize(11);
        button.setBackground(rounded(COLOR_ACCENT, 7, COLOR_ACCENT, 1));
        return button;
    }

    private void styleInput(EditText input) {
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_TEXT_DIM);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setPadding(dp(10), 0, dp(10), 0);
        input.setBackground(inputBackground());
    }

    private void styleComposerInput(EditText input) {
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_TEXT_DIM);
        input.setTextSize(13);
        input.setMinHeight(dp(38));
        input.setPadding(dp(8), dp(5), dp(8), dp(5));
        input.setBackground(inputBackground());
    }

    private Button compactButton(String label, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        button.setTextSize(11);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        return button;
    }

    private StateListDrawable buttonBackground() {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, rounded(Color.rgb(20, 23, 27), 8, COLOR_BORDER_SOFT, 1));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(COLOR_ACCENT_DARK, 8, COLOR_ACCENT, 1));
        states.addState(new int[]{android.R.attr.state_focused}, rounded(Color.rgb(35, 48, 53), 8, COLOR_ACCENT, 1));
        states.addState(new int[]{}, rounded(COLOR_CARD_ALT, 8, COLOR_BORDER, 1));
        return states;
    }

    private GradientDrawable cardBackground() {
        return rounded(COLOR_CARD, 8, COLOR_BORDER_SOFT, 1);
    }

    private GradientDrawable panelBackground() {
        return rounded(COLOR_PANEL, 8, COLOR_BORDER_SOFT, 1);
    }

    private GradientDrawable inputBackground() {
        return rounded(COLOR_FIELD, 8, COLOR_BORDER, 1);
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

    private void addTextKey(LinearLayout row, String label, String text) {
        addSoftButton(row, label, view -> insertComposerText(text));
    }

    private void addComposerButton(LinearLayout row, String label, Runnable action) {
        addSoftButton(row, label, view -> action.run());
    }

    private void addSoftButton(LinearLayout row, String label, View.OnClickListener listener) {
        Button button = toolbarButton(label, listener);
        button.setTextSize(isArrowLabel(label) ? 15 : 9);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        );
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        row.addView(button, params);
    }

    private void addAccessoryTab(LinearLayout row, String label, int page) {
        Button button = toolbarButton(label, view -> setTerminalKeyPage(page));
        boolean selected = terminalKeyPage == page;
        button.setTextSize(9);
        button.setPadding(dp(2), 0, dp(2), 0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setTextColor(selected ? Color.rgb(14, 38, 24) : COLOR_TEXT_MUTED);
        button.setBackground(selected
                ? rounded(COLOR_ACCENT, 7, COLOR_ACCENT, 1)
                : rounded(COLOR_CARD_ALT, 7, COLOR_BORDER_SOFT, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1
        );
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        row.addView(button, params);
    }

    private boolean isArrowLabel(String label) {
        return "←".equals(label) || "→".equals(label) || "↑".equals(label) || "↓".equals(label);
    }

    private TextView bodyText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(COLOR_TEXT_MUTED);
        view.setTextSize(13);
        return view;
    }

    private String textPart(String value) {
        return value == null || value.isEmpty() ? "" : "  " + value;
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private String compactLabel(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(1, maxChars - 1)) + "…";
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void showMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        setStatus(message);
    }

    private void setStatus(String message) {
        setStatus(message, inferStatusTone(message));
    }

    private void setStatus(String message, int tone) {
        if (statusText != null) {
            String value = message == null || message.trim().isEmpty() ? "Ready" : message.trim();
            statusText.setText(value);
            int bg = COLOR_PANEL;
            int stroke = Color.TRANSPARENT;
            int text = COLOR_TEXT_MUTED;
            if (tone == STATUS_BUSY) {
                bg = Color.rgb(20, 42, 58);
                stroke = COLOR_ACCENT;
                text = Color.rgb(220, 238, 255);
            } else if (tone == STATUS_SUCCESS) {
                bg = Color.rgb(19, 55, 36);
                stroke = COLOR_SUCCESS;
                text = Color.rgb(218, 245, 228);
            } else if (tone == STATUS_ERROR) {
                bg = Color.rgb(72, 27, 33);
                stroke = COLOR_DANGER;
                text = Color.rgb(255, 226, 226);
            }
            statusText.setTextColor(text);
            statusText.setBackground(rounded(bg, 0, stroke, tone == STATUS_NORMAL ? 0 : 1));
        }
    }

    private int inferStatusTone(String message) {
        if (message == null) {
            return STATUS_NORMAL;
        }
        String value = message.toLowerCase(java.util.Locale.ROOT);
        if (value.contains("failed")
                || value.contains("error")
                || value.contains("invalid")
                || value.contains("cannot")
                || value.contains("mismatch")
                || value.contains("disconnected")
                || value.contains("empty")
                || value.contains("no package")
                || value.contains("no app")) {
            return STATUS_ERROR;
        }
        if (value.contains("loading")
                || value.contains("checking")
                || value.contains("connecting")
                || value.contains("probing")
                || value.contains("downloading")
                || value.contains("preparing")
                || value.contains("verifying")
                || value.contains("retrying")
                || value.contains("queued")
                || value.contains("resolving")) {
            return STATUS_BUSY;
        }
        if (value.contains("done")
                || value.contains("loaded")
                || value.contains("connected")
                || value.contains("created")
                || value.contains("killed")
                || value.contains("opened")
                || value.contains("sent")
                || value.contains("selected")
                || value.contains("saved")
                || value.contains("using downloaded")
                || value.contains("update found")
                || value.contains("already up to date")) {
            return STATUS_SUCCESS;
        }
        return STATUS_NORMAL;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isCurrentTerminalConnection(String sessionName, int generation) {
        return !activityDestroyed
                && generation == terminalConnectionGeneration
                && sessionName.equals(activeSessionName);
    }

    private void scheduleTerminalReconnect(String sessionName, int generation) {
        if (!isCurrentTerminalConnection(sessionName, generation) || terminalReconnectTask != null) {
            return;
        }
        long delay = reconnectDelay(terminalReconnectAttempt++);
        terminalSocketStatus = "retry in " + Math.max(1L, delay / 1000L) + "s";
        updateTerminalMeta();
        setStatus("Terminal reconnecting in " + Math.max(1L, delay / 1000L) + "s");
        terminalReconnectTask = () -> {
            terminalReconnectTask = null;
            if (isCurrentTerminalConnection(sessionName, generation)) {
                connectTerminalSocket(sessionName, generation);
            }
        };
        mainHandler.postDelayed(terminalReconnectTask, delay);
    }

    private void cancelTerminalReconnect() {
        if (terminalReconnectTask != null) {
            mainHandler.removeCallbacks(terminalReconnectTask);
            terminalReconnectTask = null;
        }
    }

    private long reconnectDelay(int attempt) {
        return SOCKET_RECONNECT_DELAYS_MS[Math.min(attempt, SOCKET_RECONNECT_DELAYS_MS.length - 1)];
    }

    private void closeTerminalSocket() {
        closeTerminalSocket(true);
    }

    private void closeTerminalSocket(boolean invalidateConnection) {
        if (invalidateConnection) {
            terminalConnectionGeneration++;
            terminalReconnectAttempt = 0;
            cancelTerminalReconnect();
            queuedTerminalInput.setLength(0);
        }
        terminalConnected = false;
        terminalConnecting = false;
        terminalRenderPending = false;
        if (terminalSocket != null) {
            terminalSocket.close();
            terminalSocket = null;
        }
    }

    private void connectAppEvents() {
        eventReconnectAttempt = 0;
        eventConnectionGeneration++;
        cancelEventReconnect();
        if (eventSocket != null) {
            eventSocket.close();
            eventSocket = null;
        }
        connectAppEventSocket(api.getBaseUrl(), eventConnectionGeneration);
    }

    private void connectAppEventSocket(String baseUrl, int generation) {
        if (activityDestroyed || generation != eventConnectionGeneration) {
            return;
        }
        eventSocket = new AppEventSocketClient(new AppEventSocketClient.Listener() {
            @Override
            public void onMessage(String text) {
                runOnUiThread(() -> {
                    if (generation == eventConnectionGeneration && !activityDestroyed) {
                        handleAppEvent(text);
                    }
                });
            }

            @Override
            public void onClosed() {
                runOnUiThread(() -> {
                    if (generation != eventConnectionGeneration || activityDestroyed) {
                        return;
                    }
                    terminalEventStatus = "events disconnected";
                    updateTerminalMeta();
                    setStatus("Event stream disconnected");
                    scheduleEventReconnect(baseUrl, generation);
                });
            }
        });
        eventSocket.connect(baseUrl);
    }

    private void scheduleEventReconnect(String baseUrl, int generation) {
        if (activityDestroyed
                || generation != eventConnectionGeneration
                || eventReconnectTask != null) {
            return;
        }
        long delay = reconnectDelay(eventReconnectAttempt++);
        terminalEventStatus = "events retry in " + Math.max(1L, delay / 1000L) + "s";
        updateTerminalMeta();
        eventReconnectTask = () -> {
            eventReconnectTask = null;
            if (!activityDestroyed && generation == eventConnectionGeneration) {
                connectAppEventSocket(baseUrl, generation);
            }
        };
        mainHandler.postDelayed(eventReconnectTask, delay);
    }

    private void cancelEventReconnect() {
        if (eventReconnectTask != null) {
            mainHandler.removeCallbacks(eventReconnectTask);
            eventReconnectTask = null;
        }
    }

    private void handleAppEvent(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String type = event.optString("type", "");
            if ("hello".equals(type)) {
                eventReconnectAttempt = 0;
                terminalEventStatus = "events connected";
                updateTerminalMeta();
                setStatus("Event stream connected");
                return;
            }
            if ("sessions-invalidated".equals(type)) {
                terminalEventStatus = "sessions changed";
                updateTerminalMeta();
                if (activeSessionName != null) {
                    refreshTerminalSessionMeta(activeSessionName);
                }
                setStatus("Sessions changed: " + event.optString("reason", "update"));
                if (activeSessionName == null) {
                    refreshSessions();
                }
                return;
            }
            if ("hook-event".equals(type)) {
                terminalEventStatus = "hook event";
                updateTerminalMeta();
                showMessage(event.optString("title", "Hook event"));
                return;
            }
            terminalEventStatus = type.isEmpty() ? "event received" : type;
            updateTerminalMeta();
            setStatus(type.isEmpty() ? "Event received" : type);
        } catch (Exception error) {
            terminalEventStatus = "event received";
            updateTerminalMeta();
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
            openSessionPage();
            return;
        }
        if (!PAGE_SERVERS.equals(activeMainPage)) {
            renderServerScreen();
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
        if (activeSessionName != null
                && !terminalConnected
                && !terminalConnecting
                && terminalReconnectTask == null) {
            scheduleTerminalReconnect(activeSessionName, terminalConnectionGeneration);
        }
        if ((eventSocket == null || eventSocket.isClosed()) && eventReconnectTask == null) {
            connectAppEvents();
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        closeTerminalSocket();
        eventConnectionGeneration++;
        cancelEventReconnect();
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
