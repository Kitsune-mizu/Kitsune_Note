package com.android.kitsune.ui.geminichat;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.kitsune.BuildConfig;
import com.android.kitsune.R;
import com.android.kitsune.base.BaseActivity;
import com.android.kitsune.data.session.UserSession;
import com.android.kitsune.ui.notes.Note;
import com.android.kitsune.ui.notes.NoteViewModel;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatActivity extends BaseActivity
        implements UserSession.UserSessionListener, ChatAdapter.OnSaveNoteListener {

    // ─── Constants ───────────────────────────────────────────────────────────

    private static final String TAG = "ChatActivity";
    private static final String GEMINI_API_KEY = BuildConfig.GEMINI_API_KEY;
    private static final String GEMINI_API_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/"
                    + "gemini-2.5-flash:generateContent?key="
                    + GEMINI_API_KEY;
    private static final int DAILY_LIMIT   = 1500;
    private static final int MAX_RETRIES   = 3;
    // Backoff delays (ms): 2s → 5s → 10s
    private static final long[] RETRY_DELAYS = {2_000L, 5_000L, 10_000L};

    // ─── Views ───────────────────────────────────────────────────────────────

    private DrawerLayout       drawerLayout;
    private RecyclerView       chatRecyclerView;
    private RecyclerView       historyRecyclerView;
    private LinearLayout       emptyStateLayout;
    private View               networkBanner;
    private TextInputEditText  messageInput;
    private ImageButton        sendButton, menuButton, newChatButton;
    private ImageView          aiIconView;
    private TextView           usageLimitText;

    // ─── Adapters & Data ─────────────────────────────────────────────────────

    private ChatAdapter          chatAdapter;
    private HistoryAdapter       historyAdapter;
    private final List<ChatMessage> currentMessages = new ArrayList<>();
    private final List<ChatSession> chatSessions    = new ArrayList<>();
    private String currentSessionId = null;

    // ─── Services ────────────────────────────────────────────────────────────

    private ChatSessionManager sessionManager;
    private UserSession        userSession;
    private NoteViewModel      noteViewModel;
    private final Handler      mainHandler = new Handler(Looper.getMainLooper());

    /** OkHttpClient — proper timeouts so slow networks don't hang silently */
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    // ─── Network Monitoring ───────────────────────────────────────────────────

    private ConnectivityManager          connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean isNetworkAvailable = true;

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        userSession    = UserSession.getInstance();
        sessionManager = ChatSessionManager.getInstance(this);
        noteViewModel  = new androidx.lifecycle.ViewModelProvider(this).get(NoteViewModel.class);

        if (userSession.isLoggedIn()) {
            String userId = userSession.getUserData(userSession.getUsername()).userId;
            noteViewModel.setUserId(userId);
        }

        userSession.addListener(this);

        initViews();
        setupAdapters();
        setupListeners();
        setupBackPressedHandler();
        setupNetworkMonitoring();
        loadSessionsForCurrentUser();
        showEmptyState();
        animateAiIcon();

        showLoading();
        new Handler(Looper.getMainLooper()).postDelayed(this::hideLoading, 1200);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        userSession.removeListener();
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        }
    }

    // ─── Initialization ──────────────────────────────────────────────────────

    private void initViews() {
        drawerLayout        = findViewById(R.id.drawerLayout);
        chatRecyclerView    = findViewById(R.id.chatRecyclerView);
        historyRecyclerView = findViewById(R.id.historyRecyclerView);
        emptyStateLayout    = findViewById(R.id.emptyStateLayout);
        networkBanner       = findViewById(R.id.networkBanner);
        messageInput        = findViewById(R.id.messageInput);
        sendButton          = findViewById(R.id.sendButton);
        menuButton          = findViewById(R.id.menuButton);
        newChatButton       = findViewById(R.id.newChatButton);
        aiIconView          = findViewById(R.id.aiIconView);
        usageLimitText      = findViewById(R.id.usageLimitText);

        updateUsageDisplay();
        applyFont(messageInput, usageLimitText);
    }

    private void setupAdapters() {
        chatAdapter = new ChatAdapter(currentMessages);
        chatAdapter.setSaveNoteListener(this);
        chatAdapter.setRetryListener(this::retryLastMessage);

        chatRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        chatRecyclerView.setAdapter(chatAdapter);
        // Improve scroll performance
        chatRecyclerView.setHasFixedSize(false);
        chatRecyclerView.setItemViewCacheSize(20);

        historyAdapter = new HistoryAdapter(chatSessions, new HistoryAdapter.HistoryListener() {
            @Override public void onSessionClick(ChatSession s) {
                loadSession(s);
                drawerLayout.closeDrawer(GravityCompat.START);
            }
            @Override public void onRenameClick(ChatSession s, int pos) { showRenameDialog(s, pos); }
            @Override public void onDeleteClick(ChatSession s, int pos) { showDeleteConfirmDialog(s, pos); }
        });
        historyRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        historyRecyclerView.setAdapter(historyAdapter);
    }

    private void setupListeners() {
        menuButton.setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START))
                drawerLayout.closeDrawer(GravityCompat.START);
            else
                drawerLayout.openDrawer(GravityCompat.START);
        });
        newChatButton.setOnClickListener(v -> startNewChat());
        sendButton.setOnClickListener(v -> sendMessage());
        messageInput.setOnEditorActionListener((v, actionId, event) -> { sendMessage(); return true; });

        View sidebarNewChat = findViewById(R.id.sidebarNewChatBtn);
        if (sidebarNewChat != null)
            sidebarNewChat.setOnClickListener(v -> {
                startNewChat();
                drawerLayout.closeDrawer(GravityCompat.START);
            });
    }

    private void setupBackPressedHandler() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START))
                    drawerLayout.closeDrawer(GravityCompat.START);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
    }

    // ─── Network Monitoring (NEW) ─────────────────────────────────────────────

    private void setupNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(@NonNull Network network) {
                mainHandler.post(() -> setNetworkBanner(true));
            }
            @Override public void onLost(@NonNull Network network) {
                mainHandler.post(() -> setNetworkBanner(false));
            }
            @Override public void onCapabilitiesChanged(@NonNull Network net,
                                                        @NonNull NetworkCapabilities caps) {
                // Detect weak/metered signal
                boolean ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                mainHandler.post(() -> setNetworkBanner(ok));
            }
        };

        NetworkRequest req = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        connectivityManager.registerNetworkCallback(req, networkCallback);

        // Set initial state
        isNetworkAvailable = isConnected();
    }

    /** Show/hide the network status banner with a smooth slide animation */
    private void setNetworkBanner(boolean connected) {
        isNetworkAvailable = connected;
        if (networkBanner == null) return;

        if (!connected) {
            networkBanner.setVisibility(View.VISIBLE);
            networkBanner.animate().translationY(0).alpha(1f).setDuration(300).start();
        } else {
            networkBanner.animate().translationY(-networkBanner.getHeight()).alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> networkBanner.setVisibility(View.GONE))
                    .start();
        }
    }

    private boolean isConnected() {
        if (connectivityManager == null) return true;
        Network net = connectivityManager.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ─── Animate AI Icon ──────────────────────────────────────────────────────

    private void animateAiIcon() {
        if (aiIconView == null) return;
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(aiIconView, "scaleX", 0.8f, 1.1f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(aiIconView, "scaleY", 0.8f, 1.1f, 1.0f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(aiIconView, "alpha", 0f, 1f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(800);
        set.setInterpolator(new OvershootInterpolator());
        set.start();

        ObjectAnimator floatAnim = ObjectAnimator.ofFloat(aiIconView, "translationY", 0f, -12f, 0f);
        floatAnim.setDuration(2500);
        floatAnim.setRepeatCount(ObjectAnimator.INFINITE);
        floatAnim.setRepeatMode(ObjectAnimator.REVERSE);
        mainHandler.postDelayed(floatAnim::start, 900);
    }

    // ─── Listeners & Callbacks ───────────────────────────────────────────────

    @Override
    public void onProfileUpdated() {
        if (!userSession.isLoggedIn()) {
            runOnUiThread(() -> {
                dispatchSessionsDiff(chatSessions, new ArrayList<>());
                chatSessions.clear();
                showEmptyState();
                updateUsageDisplay();
            });
            return;
        }
        String userId = userSession.getUserData(userSession.getUsername()).userId;
        noteViewModel.setUserId(userId);
        runOnUiThread(() -> { loadSessionsForCurrentUser(); updateUsageDisplay(); });
    }

    @Override
    public void onSaveNote(String content) { showSaveAsNoteDialog(content); }

    // ─── Chat State ───────────────────────────────────────────────────────────

    private void showEmptyState() {
        emptyStateLayout.setVisibility(View.VISIBLE);
        chatRecyclerView.setVisibility(View.GONE);
        int size = currentMessages.size();
        currentMessages.clear();
        if (size > 0) chatAdapter.notifyItemRangeRemoved(0, size);
        currentSessionId = null;
    }

    private void startNewChat() {
        showEmptyState();
        animateAiIcon();
        messageInput.setText("");
        hideKeyboard();
    }

    private void sendMessage() {
        if (!userSession.isLoggedIn()) { showNotLoggedInDialog(); return; }

        String text = messageInput.getText() != null
                ? messageInput.getText().toString().trim() : "";
        if (TextUtils.isEmpty(text)) return;
        if (!checkDailyLimit()) { showLimitExceededDialog(); return; }

        if (currentSessionId == null) {
            currentSessionId = UUID.randomUUID().toString();
            emptyStateLayout.setVisibility(View.GONE);
            chatRecyclerView.setVisibility(View.VISIBLE);
        }

        messageInput.setText("");
        hideKeyboard();

        currentMessages.add(new ChatMessage(text, ChatMessage.TYPE_USER));
        chatAdapter.notifyItemInserted(currentMessages.size() - 1);
        scrollToBottom();

        currentMessages.add(new ChatMessage("", ChatMessage.TYPE_LOADING));
        int loadingIndex = currentMessages.size() - 1;
        chatAdapter.notifyItemInserted(loadingIndex);
        scrollToBottom();

        callGeminiApi(loadingIndex, 0); // attempt 0
    }

    /** Called by retry button in ChatAdapter */
    private void retryLastMessage() {
        for (int i = currentMessages.size() - 1; i >= 0; i--) {
            if (currentMessages.get(i).getType() == ChatMessage.TYPE_ERROR) {
                currentMessages.remove(i);
                chatAdapter.notifyItemRemoved(i);
                break;
            }
        }
        currentMessages.add(new ChatMessage("", ChatMessage.TYPE_LOADING));
        int loadingIndex = currentMessages.size() - 1;
        chatAdapter.notifyItemInserted(loadingIndex);
        scrollToBottom();
        callGeminiApi(loadingIndex, 0);
    }

    // ─── Gemini API + Auto Retry ──────────────────────────────────────────────

    private void callGeminiApi(int loadingIndex, int attempt) {
        JSONArray contents = buildContents();
        if (contents == null) return;

        try {
            JSONObject body = new JSONObject();
            body.put("contents", contents);
            body.put("generationConfig", new JSONObject()
                    .put("temperature", 0.9)
                    .put("maxOutputTokens", 2048));

            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json"));
            Request request = new Request.Builder().url(GEMINI_API_URL).post(requestBody).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    // ── Auto-retry with exponential backoff ──
                    if (attempt < MAX_RETRIES - 1) {
                        long delay = RETRY_DELAYS[attempt];
                        Log.w(TAG, "Attempt " + (attempt + 1) + " failed, retry in " + delay + "ms");
                        mainHandler.postDelayed(() -> callGeminiApi(loadingIndex, attempt + 1), delay);
                    } else {
                        // All retries exhausted
                        String errMsg = !isNetworkAvailable
                                ? getString(R.string.error_no_network)
                                : getString(R.string.error_connection, e.getMessage());
                        mainHandler.post(() ->
                                removeLoadingAndAdd(loadingIndex, errMsg, ChatMessage.TYPE_ERROR));
                    }
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response)
                        throws IOException {
                    String respBody = response.body().string();
                    mainHandler.post(() -> {
                        try {
                            if (!response.isSuccessful()) {
                                handleErrorResponse(loadingIndex, response.code(), respBody, attempt);
                                return;
                            }

                            String aiText = new JSONObject(respBody)
                                    .getJSONArray("candidates").getJSONObject(0)
                                    .getJSONObject("content")
                                    .getJSONArray("parts").getJSONObject(0)
                                    .getString("text");

                            sessionManager.incrementDailyCount();
                            updateUsageDisplay();
                            removeLoadingAndAdd(loadingIndex, aiText, ChatMessage.TYPE_AI);
                            persistCurrentSession();

                        } catch (JSONException e) {
                            removeLoadingAndAdd(loadingIndex,
                                    getString(R.string.error_parse), ChatMessage.TYPE_ERROR);
                        }
                    });
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "JSON build error: " + e.getMessage());
        }
    }

    /** Handle HTTP error — retry on 429/5xx, fail immediately on 4xx */
    private void handleErrorResponse(int loadingIndex, int code, String body, int attempt) {
        if (code == 429) {
            // Rate limited — retry with backoff
            if (attempt < MAX_RETRIES - 1) {
                long delay = RETRY_DELAYS[attempt];
                mainHandler.postDelayed(() -> callGeminiApi(loadingIndex, attempt + 1), delay);
            } else {
                showRateLimitDialog();
                removeLoadingAndAdd(loadingIndex, getString(R.string.error_rate_limit), ChatMessage.TYPE_ERROR);
            }
        } else if (code >= 500 && attempt < MAX_RETRIES - 1) {
            // Server error — retry
            mainHandler.postDelayed(() -> callGeminiApi(loadingIndex, attempt + 1), RETRY_DELAYS[attempt]);
        } else {
            String msg;
            try {
                JSONObject err = new JSONObject(body);
                msg = err.optJSONObject("error") != null
                        ? err.getJSONObject("error").optString("message", getString(R.string.error_unknown))
                        : getString(R.string.error_http, code);
            } catch (JSONException ex) {
                msg = getString(R.string.error_http, code);
            }
            removeLoadingAndAdd(loadingIndex, getString(R.string.error_prefix, msg), ChatMessage.TYPE_ERROR);
        }
    }

    /** Build the conversation contents array (excludes loading/error messages) */
    private JSONArray buildContents() {
        try {
            JSONArray contents = new JSONArray();
            for (int i = 0; i < currentMessages.size() - 1; i++) {
                ChatMessage msg = currentMessages.get(i);
                if (msg.getType() == ChatMessage.TYPE_USER || msg.getType() == ChatMessage.TYPE_AI) {
                    JSONObject content = new JSONObject();
                    content.put("role", msg.getType() == ChatMessage.TYPE_USER ? "user" : "model");
                    content.put("parts", new JSONArray().put(new JSONObject().put("text", msg.getText())));
                    contents.put(content);
                }
            }
            return contents;
        } catch (JSONException e) {
            Log.e(TAG, "buildContents error: " + e.getMessage());
            return null;
        }
    }

    private void removeLoadingAndAdd(int loadingIndex, String text, int type) {
        if (loadingIndex < currentMessages.size()) {
            currentMessages.remove(loadingIndex);
            chatAdapter.notifyItemRemoved(loadingIndex);
        }
        currentMessages.add(new ChatMessage(text, type));
        chatAdapter.notifyItemInserted(currentMessages.size() - 1);
        scrollToBottom();
    }

    private void scrollToBottom() {
        chatRecyclerView.postDelayed(() ->
                chatRecyclerView.smoothScrollToPosition(
                        Math.max(0, currentMessages.size() - 1)), 80); // slightly faster
    }

    // ─── Session Persistence & Limits ────────────────────────────────────────

    private void loadSessionsForCurrentUser() {
        List<ChatSession> newSessions = new ArrayList<>();
        if (userSession.isLoggedIn()) newSessions.addAll(sessionManager.loadSessions());
        dispatchSessionsDiff(chatSessions, newSessions);
        chatSessions.clear();
        chatSessions.addAll(newSessions);
    }

    private void dispatchSessionsDiff(List<ChatSession> oldList, List<ChatSession> newList) {
        if (historyAdapter == null) return;
        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldList.size(); }
            @Override public int getNewListSize() { return newList.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return oldList.get(o).getId().equals(newList.get(n).getId());
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                return oldList.get(o).getTitle().equals(newList.get(n).getTitle());
            }
        }).dispatchUpdatesTo(historyAdapter);
    }

    private void persistCurrentSession() {
        if (currentSessionId == null || currentMessages.isEmpty() || !userSession.isLoggedIn()) return;

        String title = "";
        for (ChatMessage m : currentMessages) {
            if (m.getType() == ChatMessage.TYPE_USER) { title = m.getText(); break; }
        }
        if (title.length() > 45) title = title.substring(0, 45) + "…";

        sessionManager.upsertSession(new ChatSession(
                currentSessionId, title, new ArrayList<>(currentMessages), userSession.getUsername()));
        loadSessionsForCurrentUser();
    }

    private void loadSession(ChatSession session) {
        currentSessionId = session.getId();
        List<ChatMessage> newMessages = session.getMessages();

        DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return currentMessages.size(); }
            @Override public int getNewListSize() { return newMessages.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return currentMessages.get(o).getTimestamp() == newMessages.get(n).getTimestamp();
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                return currentMessages.get(o).getText().equals(newMessages.get(n).getText());
            }
        }).dispatchUpdatesTo(chatAdapter);

        currentMessages.clear();
        currentMessages.addAll(newMessages);
        emptyStateLayout.setVisibility(View.GONE);
        chatRecyclerView.setVisibility(View.VISIBLE);
        scrollToBottom();
    }

    private boolean checkDailyLimit() {
        return sessionManager.getDailyCount() < DAILY_LIMIT;
    }

    private void updateUsageDisplay() {
        if (usageLimitText == null) return;
        int remaining = DAILY_LIMIT - sessionManager.getDailyCount();
        usageLimitText.setText(getString(R.string.usage_remaining, remaining, DAILY_LIMIT));
        usageLimitText.setTextColor(remaining < 100
                ? getAttrColor(R.attr.color_error)
                : getAttrColor(R.attr.text_color));
    }

    // ─── Dialogs & UI Utilities ──────────────────────────────────────────────

    private void showSaveAsNoteDialog(String content) {
        if (!userSession.isLoggedIn()) {
            Toast.makeText(this, getString(R.string.dialog_not_logged_in_title), Toast.LENGTH_SHORT).show();
            return;
        }

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_save_as_note,
                findViewById(android.R.id.content), false);

        TextInputEditText inputTitle = view.findViewById(R.id.inputNoteTitle);
        TextView previewText         = view.findViewById(R.id.previewNoteContent);

        String autoTitle = content.length() > 60
                ? content.substring(0, 60).trim() + "…" : content.trim();
        int firstBreak = Math.min(
                autoTitle.indexOf('\n') < 0 ? autoTitle.length() : autoTitle.indexOf('\n'),
                autoTitle.indexOf('.') < 0 ? autoTitle.length() : autoTitle.indexOf('.') + 1);
        inputTitle.setText(autoTitle.substring(0, Math.min(firstBreak, autoTitle.length())).trim());
        inputTitle.setSelection(inputTitle.getText() != null ? inputTitle.getText().length() : 0);
        previewText.setText(MarkdownFormatter.toPlainNote(content));

        AlertDialog dialog = buildDialog(view);
        view.findViewById(R.id.btnSaveNoteCancel).setOnClickListener(v -> dialog.dismiss());
        view.findViewById(R.id.btnSaveNoteConfirm).setOnClickListener(v -> {
            String title = inputTitle.getText() != null ? inputTitle.getText().toString().trim() : "";
            Note note = new Note();
            note.setTitle(title.isEmpty() ? getString(R.string.note_from_gemini_default_title) : title);
            note.setContent(MarkdownFormatter.toEditNoteHtml(content));
            noteViewModel.saveNote(this, note);
            Toast.makeText(this, getString(R.string.toast_note_saved_from_ai), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void showRenameDialog(ChatSession session, int position) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_rename,
                findViewById(android.R.id.content), false);
        android.widget.EditText input = view.findViewById(R.id.renameInput);
        input.setText(session.getTitle());
        input.setSelection(input.getText().length());

        AlertDialog dialog = buildDialog(view);
        view.findViewById(R.id.btnRenameConfirm).setOnClickListener(v -> {
            String newName = input.getText().toString().trim();
            if (!TextUtils.isEmpty(newName)) {
                sessionManager.renameSession(session.getId(), newName);
                session.setTitle(newName);
                historyAdapter.notifyItemChanged(position);
                dialog.dismiss();
            }
        });
        view.findViewById(R.id.btnRenameCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showDeleteConfirmDialog(ChatSession session, int position) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_delete_confirm,
                findViewById(android.R.id.content), false);
        AlertDialog dialog = buildDialog(view);
        view.findViewById(R.id.btnDeleteConfirm).setOnClickListener(v -> {
            if (session.getId().equals(currentSessionId)) showEmptyState();
            sessionManager.deleteSession(session.getId());
            chatSessions.remove(position);
            historyAdapter.notifyItemRemoved(position);
            dialog.dismiss();
        });
        view.findViewById(R.id.btnDeleteCancel).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showLimitExceededDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_limit_exceeded,
                findViewById(android.R.id.content), false);
        AlertDialog dialog = buildDialog(view);
        view.findViewById(R.id.btnClose).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void showRateLimitDialog() {
        new AlertDialog.Builder(this, R.style.RoundedDialog)
                .setTitle(R.string.dialog_rate_limit_title)
                .setMessage(R.string.dialog_rate_limit_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showNotLoggedInDialog() {
        new AlertDialog.Builder(this, R.style.RoundedDialog)
                .setTitle(R.string.dialog_not_logged_in_title)
                .setMessage(R.string.dialog_not_logged_in_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private AlertDialog buildDialog(View view) {
        AlertDialog d = new AlertDialog.Builder(this, R.style.RoundedDialog)
                .setView(view).setCancelable(true).create();
        if (d.getWindow() != null)
            d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        return d;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null)
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
    }
}