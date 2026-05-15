package com.android.kitsune.ui.notes;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.*;
import android.text.util.Linkify;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import com.android.kitsune.R;
import com.android.kitsune.base.BaseActivity;
import com.android.kitsune.data.session.UserSession;
import com.android.kitsune.ui.auth.LoginActivity;
import com.android.kitsune.utils.DialogUtils;
import com.google.gson.Gson;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.ArrayList;

public class EditNoteActivity extends BaseActivity {

    // ─── Constants & Variables ───────────────────────────────────────────────

    private static final String TAG = "EditNoteActivity";
    private static final int MAX_UNDO_HISTORY = 100;
    private static final long UNDO_DEBOUNCE_MS = 800;
    private static final float BASE_TEXT_SIZE_SP = 14f;
    private static final float SIZE_STEP_SP = 2f;
    private static final float MIN_TEXT_SIZE_SP = 10f;
    private static final float MAX_TEXT_SIZE_SP = 36f;

    private final android.os.Handler undoHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable undoRunnable = this::pushUndoSnapshot;

    private NoteViewModel viewModel;
    private Note currentNote;

    private EditText etTitle, etContent;
    private TextView tvMetadata;
    private LinearLayout layoutDefaultActions, layoutInputActions;
    private ImageButton btnDelete, btnShare;

    private ImageButton btnUndo, btnRedo;
    private com.google.android.material.button.MaterialButton btnSaveManual;

    private final Deque<SpannableStringBuilder> undoStack = new ArrayDeque<>();
    private final Deque<SpannableStringBuilder> redoStack = new ArrayDeque<>();

    private boolean isRestoringHistory = false;

    private ImageButton btnBold, btnItalic, btnUnderline, btnHighlight, btnStrike, btnTextColor;
    private ImageButton btnAlignLeft, btnAlignCenter, btnAlignRight;
    private ImageButton btnBullet, btnSizeUp, btnSizeDown;

    private boolean bulletMode = false;
    private String originalTitle = "";
    private String originalContent = "";
    private int currentTextColor;
    private int currentHighlightColor;

    private final Gson gson = new Gson();


    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_note);

        initViews();
        initViewModel();
        loadNoteData();
        setupEditorWatcher();
        setupButtons();
        setupKeyboardToolbar();
        setupBackPressed();
        detectKeyboardVisibility();
    }


    // ─── Initialization ──────────────────────────────────────────────────────

    @SuppressLint("ObsoleteSdkInt")
    private void initViews() {
        etTitle = findViewById(R.id.et_title);
        etContent = findViewById(R.id.et_content);
        tvMetadata = findViewById(R.id.tv_metadata);

        etTitle.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
                        android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        );
        etContent.setInputType(
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES |
                        android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT |
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        );
        etTitle.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);

        etContent.setImeHintLocales(android.os.LocaleList.getDefault());
        etTitle.setImeHintLocales(android.os.LocaleList.getDefault());

        layoutDefaultActions = findViewById(R.id.layout_default_actions);
        layoutInputActions = findViewById(R.id.layout_input_actions);

        currentTextColor = getAttrColor(R.attr.text_color);
        currentHighlightColor = getAttrColor(R.attr.color_yellow);

        applyFont(etTitle, etContent, tvMetadata);

        btnDelete = findViewById(R.id.btn_delete);
        btnShare = findViewById(R.id.btn_share);
        btnUndo = findViewById(R.id.btn_undo);
        btnRedo = findViewById(R.id.btn_redo);
        btnSaveManual = findViewById(R.id.btn_save_manual);

        btnBold = findViewById(R.id.action_bold);
        btnItalic = findViewById(R.id.action_italic);
        btnUnderline = findViewById(R.id.action_underline);
        btnHighlight = findViewById(R.id.action_highlight);
        btnStrike = findViewById(R.id.action_strike);
        btnTextColor = findViewById(R.id.action_text_color);
        btnBullet = findViewById(R.id.action_bullet_list);
        btnSizeUp = findViewById(R.id.action_size_up);
        btnSizeDown = findViewById(R.id.action_size_down);
        btnAlignLeft = findViewById(R.id.action_align_left);
        btnAlignCenter = findViewById(R.id.action_align_center);
        btnAlignRight = findViewById(R.id.action_align_right);

        refreshUndoRedoButtons();

        etContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable editable) {
                Linkify.addLinks(editable, Linkify.WEB_URLS);
                etContent.setMovementMethod(LinkMovementMethod.getInstance());
            }
        });

        etContent.setLinkTextColor(getAttrColor(R.attr.color_blue));

        etContent.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                etContent.setCursorVisible(true);
            }
        });

        etContent.setOnClickListener(v -> {
            etContent.setCursorVisible(true);
            etContent.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(etContent, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void initViewModel() {
        viewModel = new ViewModelProvider(this).get(NoteViewModel.class);

        String userId = getIntent().getStringExtra("user_id");
        if (userId == null && UserSession.getInstance().isInitialized()) {
            userId = UserSession.getInstance()
                    .getUserData(UserSession.getInstance().getUsername()).userId;
        }

        if (userId == null) {
            Intent i = new Intent(this, LoginActivity.class);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
            return;
        }
        viewModel.setUserId(userId);
    }

    private void loadNoteData() {
        String noteId = getIntent().getStringExtra("note_id");
        if (noteId != null) {
            currentNote = viewModel.getNoteById(this, noteId);
        }

        if (currentNote != null) {
            etTitle.setText(currentNote.getTitle());
            etContent.setText(htmlToSpannable(currentNote.getContent()));
            originalTitle = etTitle.getText().toString();
            originalContent = spannableToHtml(etContent.getText());
        } else {
            currentNote = new Note(UUID.randomUUID().toString());
            currentNote.setTimestamp(System.currentTimeMillis());
        }

        pushUndoSnapshot();
        updateMetadata();
    }


    // ─── Editor Watcher ──────────────────────────────────────────────────────

    private void setupEditorWatcher() {
        etContent.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateToolbarState();
                if (isRestoringHistory) {
                    return;
                }

                if (bulletMode && count > 0 && s.subSequence(start, start + count).toString().contains("\n")) {
                    etContent.post(() -> {
                        Editable editable = etContent.getText();
                        int cursor = etContent.getSelectionStart();
                        if (cursor > 0 && editable.charAt(cursor - 1) == '\n') {
                            editable.insert(cursor, "• ");
                        }
                    });
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (isRestoringHistory) return;

                String currentText = s.toString();
                int cursor = etContent.getSelectionStart();
                int lineStart = currentText.lastIndexOf('\n', cursor - 1) + 1;

                if (bulletMode && !currentText.startsWith("• ", lineStart)) {
                    bulletMode = false;
                    updateToolbarState();
                }

                updateMetadata();

                undoHandler.removeCallbacks(undoRunnable);
                undoHandler.postDelayed(undoRunnable, UNDO_DEBOUNCE_MS);

                redoStack.clear();
                refreshUndoRedoButtons();
            }
        });

        etContent.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return true;
            }

            @Override
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                updateToolbarState();
                return true;
            }

            @Override
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(android.view.ActionMode mode) {
                updateToolbarState();
            }
        });
    }


    // ─── Undo & Redo ─────────────────────────────────────────────────────────

    private void pushUndoSnapshot() {
        if (isRestoringHistory || etContent == null || etContent.getText() == null) return;

        SpannableStringBuilder snapshot = new SpannableStringBuilder(etContent.getText());
        SpannableStringBuilder top = undoStack.peek();

        if (top != null && top.toString().equals(snapshot.toString())) return;

        undoStack.push(snapshot);
        if (undoStack.size() > MAX_UNDO_HISTORY) undoStack.removeLast();
        refreshUndoRedoButtons();
    }

    private void undo() {
        if (undoStack.size() <= 1) return;

        undoHandler.removeCallbacks(undoRunnable);
        SpannableStringBuilder current = new SpannableStringBuilder(etContent.getText());
        SpannableStringBuilder top = undoStack.peek();
        if (top == null || !top.toString().equals(current.toString())) {
            undoStack.push(current);
        }

        isRestoringHistory = true;
        redoStack.push(undoStack.pop());

        SpannableStringBuilder restored = new SpannableStringBuilder(Objects.requireNonNull(undoStack.peek()));
        etContent.setText(restored);
        etContent.setSelection(Math.min(restored.length(), etContent.length()));

        isRestoringHistory = false;
        refreshUndoRedoButtons();
    }

    private void redo() {
        if (redoStack.isEmpty()) return;

        undoHandler.removeCallbacks(undoRunnable);
        isRestoringHistory = true;

        SpannableStringBuilder restored = new SpannableStringBuilder(redoStack.pop());
        undoStack.push(new SpannableStringBuilder(restored));

        etContent.setText(restored);
        etContent.setSelection(Math.min(restored.length(), etContent.length()));

        isRestoringHistory = false;
        refreshUndoRedoButtons();
    }

    private void refreshUndoRedoButtons() {
        boolean canUndo = undoStack.size() > 1;
        boolean canRedo = !redoStack.isEmpty();

        btnUndo.setEnabled(canUndo);
        btnRedo.setEnabled(canRedo);

        btnUndo.setAlpha(canUndo ? 1f : 0.35f);
        btnRedo.setAlpha(canRedo ? 1f : 0.35f);
    }


    // ─── Buttons & Toolbar Setup ─────────────────────────────────────────────

    private void setupButtons() {
        findViewById(R.id.btn_back).setOnClickListener(v -> saveAndExit());
        btnShare.setVisibility(View.GONE);
        btnDelete.setVisibility(View.GONE);
        findViewById(R.id.btn_menu).setOnClickListener(this::showMenuPopup);

        btnUndo.setOnClickListener(v -> undo());
        btnRedo.setOnClickListener(v -> redo());
        btnSaveManual.setOnClickListener(v -> manualSave());
    }

    private void setupKeyboardToolbar() {
        btnBold.setOnClickListener(v -> {
            pushUndoSnapshot();
            toggleStyle(Typeface.BOLD);
        });

        btnItalic.setOnClickListener(v -> {
            pushUndoSnapshot();
            toggleStyle(Typeface.ITALIC);
        });

        btnUnderline.setOnClickListener(v -> {
            pushUndoSnapshot();
            toggleSpan(UnderlineSpan.class);
        });

        btnStrike.setOnClickListener(v -> {
            pushUndoSnapshot();
            toggleSpan(StrikethroughSpan.class);
        });

        btnHighlight.setOnClickListener(v -> {
            pushUndoSnapshot();
            openHighlightColorPicker();
        });

        btnTextColor.setOnClickListener(v -> {
            pushUndoSnapshot();
            openTextColorPicker();
        });

        btnAlignLeft.setOnClickListener(v -> {
            pushUndoSnapshot();
            setAlignment(Layout.Alignment.ALIGN_NORMAL);
        });

        btnAlignCenter.setOnClickListener(v -> {
            pushUndoSnapshot();
            setAlignment(Layout.Alignment.ALIGN_CENTER);
        });

        btnAlignRight.setOnClickListener(v -> {
            pushUndoSnapshot();
            setAlignment(Layout.Alignment.ALIGN_OPPOSITE);
        });

        btnBullet.setOnClickListener(v -> {
            pushUndoSnapshot();
            toggleBullet();
        });

        btnSizeUp.setOnClickListener(v -> {
            pushUndoSnapshot();
            changeTextSize(true);
        });

        btnSizeDown.setOnClickListener(v -> {
            pushUndoSnapshot();
            changeTextSize(false);
        });
    }


    // ─── Keyboard Visibility ─────────────────────────────────────────────────

    private void detectKeyboardVisibility() {
        final View contentView = findViewById(android.R.id.content);
        contentView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            boolean isKeyboardVisible = (contentView.getRootView().getHeight() - contentView.getHeight()) > dpToPx();

            if (isKeyboardVisible) {
                etContent.post(() -> {
                    etContent.requestFocus();
                    etContent.setCursorVisible(true);
                });
                layoutDefaultActions.setVisibility(View.GONE);
                layoutInputActions.setVisibility(View.VISIBLE);
            } else {
                layoutDefaultActions.setVisibility(View.VISIBLE);
                layoutInputActions.setVisibility(View.GONE);
            }
            refreshUndoRedoButtons();
        });
    }

    private int dpToPx() {
        return Math.round(200 * getResources().getDisplayMetrics().density);
    }

    private void setupBackPressed() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                saveAndExit();
            }
        });
    }


    // ─── Metadata & Save ─────────────────────────────────────────────────────

    private void updateMetadata() {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy, HH:mm", Locale.getDefault());
        long ts = currentNote.getTimestamp() == 0 ? System.currentTimeMillis() : currentNote.getTimestamp();
        tvMetadata.setText(getString(R.string.metadata_format, sdf.format(new Date(ts)), etContent.length()));
    }

    private void manualSave() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(etContent.getWindowToken(), 0);
        }

        layoutInputActions.setVisibility(View.GONE);
        layoutDefaultActions.setVisibility(View.VISIBLE);

        String title = etTitle.getText().toString().trim();
        String contentHtml = spannableToHtml(etContent.getText());
        String plainText = Html.fromHtml(contentHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim();

        if (currentNote != null && title.isEmpty() && plainText.isEmpty()) {
            getSharedPreferences("notes_" + viewModel.getUserId(), MODE_PRIVATE)
                    .edit().remove(currentNote.getId()).apply();
            viewModel.deleteNote(this, currentNote.getId());
            Toast.makeText(this, getString(R.string.toast_note_deleted), Toast.LENGTH_SHORT).show();
            return;
        }

        if (!title.equals(originalTitle) || !contentHtml.equals(originalContent)) {
            assert currentNote != null;
            currentNote.setTitle(title);
            currentNote.setContent(contentHtml);
            currentNote.setTimestamp(System.currentTimeMillis());

            getSharedPreferences("notes_" + viewModel.getUserId(), MODE_PRIVATE)
                    .edit().putString(currentNote.getId(), gson.toJson(currentNote)).apply();

            viewModel.saveNote(this, currentNote);
            originalTitle = title;
            originalContent = contentHtml;
            Toast.makeText(this, getString(R.string.toast_note_saved), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.toast_no_changes), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveAndExit() {
        undoHandler.removeCallbacks(undoRunnable);

        String title = etTitle.getText().toString().trim();
        String contentHtml = spannableToHtml(etContent.getText());
        String plainText = Html.fromHtml(contentHtml, Html.FROM_HTML_MODE_LEGACY).toString().trim();

        if (originalContent != null && currentNote != null && title.isEmpty() && plainText.isEmpty()) {
            getSharedPreferences("notes_" + viewModel.getUserId(), MODE_PRIVATE)
                    .edit().remove(currentNote.getId()).apply();
            viewModel.deleteNote(this, currentNote.getId());
            setResult(RESULT_OK);
            finish();
            return;
        }

        if (title.isEmpty() && plainText.isEmpty()) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        if (!title.equals(originalTitle) || !contentHtml.equals(originalContent)) {
            assert currentNote != null;
            currentNote.setTitle(title);
            currentNote.setContent(contentHtml);
            currentNote.setTimestamp(System.currentTimeMillis());

            getSharedPreferences("notes_" + viewModel.getUserId(), MODE_PRIVATE)
                    .edit().putString(currentNote.getId(), gson.toJson(currentNote)).apply();

            viewModel.saveNote(this, currentNote);
            setResult(RESULT_OK);
        } else {
            setResult(RESULT_CANCELED);
        }
        finish();
    }


    // ─── Custom HTML Serialization ───────────────────────────────────────────

    private String buildAlignStyle(Layout.Alignment a) {
        if (a == Layout.Alignment.ALIGN_CENTER) {
            return " style=\"text-align:center;\"";
        }
        if (a == Layout.Alignment.ALIGN_OPPOSITE) {
            return " style=\"text-align:right;\"";
        }
        return " style=\"text-align:left;\"";
    }

    private String spannableToHtml(Spannable text) {
        StringBuilder sb = new StringBuilder();
        String rawText = text.toString();

        while (rawText.endsWith("\n")) {
            rawText = rawText.substring(0, rawText.length() - 1);
        }

        String[] lines = rawText.split("\n", -1);
        int offset = 0;

        for (String line : lines) {
            int lineEnd = offset + line.length();

            AlignmentSpan.Standard[] alignSpans = text.getSpans(offset, lineEnd, AlignmentSpan.Standard.class);
            String alignAttr = alignSpans.length > 0 ? buildAlignStyle(alignSpans[0].getAlignment()) : "";

            AbsoluteSizeSpan[] sizeSpans = text.getSpans(offset, lineEnd, AbsoluteSizeSpan.class);
            String sizeAttr = "";

            if (sizeSpans.length > 0) {
                StringBuilder sizes = new StringBuilder();
                for (AbsoluteSizeSpan ss : sizeSpans) {
                    int sS = Math.max(0, text.getSpanStart(ss) - offset);
                    int sE = Math.min(line.length(), text.getSpanEnd(ss) - offset);

                    if (sS >= sE) {
                        continue;
                    }

                    float sp = ss.getDip() ? ss.getSize() : ss.getSize() / getResources().getDisplayMetrics().scaledDensity;
                    if (sizes.length() > 0) {
                        sizes.append(",");
                    }
                    sizes.append(sS).append(":").append(sE).append(":").append((int) sp);
                }

                if (sizes.length() > 0) {
                    sizeAttr = " data-sizes=\"" + sizes + "\"";
                }
            }

            SpannableStringBuilder lineSpan = new SpannableStringBuilder(line);

            for (Object span : text.getSpans(offset, lineEnd, Object.class)) {
                if (span instanceof AlignmentSpan || span instanceof AbsoluteSizeSpan) {
                    continue;
                }

                int sS = Math.max(0, text.getSpanStart(span) - offset);
                int sE = Math.min(line.length(), text.getSpanEnd(span) - offset);

                if (sS >= sE) {
                    continue;
                }

                try {
                    lineSpan.setSpan(span, sS, sE, text.getSpanFlags(span));
                } catch (Exception ignored) {
                }
            }

            String lineHtml = Html.toHtml(lineSpan, Html.FROM_HTML_MODE_COMPACT)
                    .replaceAll("(?i)</?p[^>]*>", "")
                    .replaceAll("(?i)<br\\s*/?>", "")
                    .trim();

            sb.append("<p").append(alignAttr).append(sizeAttr).append(">")
                    .append(lineHtml)
                    .append("</p>");

            offset += line.length() + 1;
        }
        return sb.toString();
    }

    private CharSequence htmlToSpannable(String html) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        String[] paragraphs = html.split("(?i)</p>", -1);

        for (String paragraph : paragraphs) {
            String para = paragraph.trim();
            if (para.isEmpty()) {
                continue;
            }

            Layout.Alignment alignment = null;
            java.util.regex.Matcher mAlign = java.util.regex.Pattern
                    .compile("(?i)text-align\\s*:\\s*(\\w+)").matcher(para);

            if (mAlign.find()) {
                String a = Objects.requireNonNull(mAlign.group(1)).toLowerCase(Locale.ROOT);
                if ("center".equals(a)) {
                    alignment = Layout.Alignment.ALIGN_CENTER;
                } else if ("right".equals(a)) {
                    alignment = Layout.Alignment.ALIGN_OPPOSITE;
                } else {
                    alignment = Layout.Alignment.ALIGN_NORMAL;
                }
            }

            List<int[]> sizeRanges = new ArrayList<>();
            java.util.regex.Matcher mSize = java.util.regex.Pattern
                    .compile("data-sizes=\"([^\"]*)\"").matcher(para);

            if (mSize.find()) {
                for (String token : Objects.requireNonNull(mSize.group(1)).split(",")) {
                    String[] parts = token.split(":");
                    if (parts.length == 3) {
                        try {
                            sizeRanges.add(new int[]{
                                    Integer.parseInt(parts[0]),
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2])
                            });
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            String innerHtml = para.replaceAll("(?i)<p[^>]*>", "").trim();
            if (innerHtml.isEmpty()) {
                continue;
            }

            SpannableStringBuilder parsed = new SpannableStringBuilder(
                    Html.fromHtml(innerHtml, Html.FROM_HTML_MODE_COMPACT));

            while (parsed.length() > 0 && parsed.charAt(parsed.length() - 1) == '\n') {
                parsed.delete(parsed.length() - 1, parsed.length());
            }

            if (parsed.length() == 0) {
                continue;
            }

            if (result.length() > 0) {
                result.append("\n");
            }

            int lineStart = result.length();
            result.append(parsed);
            int lineEnd = result.length();

            if (alignment != null && lineStart < lineEnd) {
                result.setSpan(new AlignmentSpan.Standard(alignment),
                        lineStart, lineEnd, Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            }

            for (int[] range : sizeRanges) {
                int sS = Math.max(lineStart, Math.min(lineEnd, lineStart + range[0]));
                int sE = Math.max(lineStart, Math.min(lineEnd, lineStart + range[1]));
                if (sS < sE) {
                    result.setSpan(new AbsoluteSizeSpan(range[2], true),
                            sS, sE, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return result;
    }


    // ─── Share as Image ──────────────────────────────────────────────────────

    private Uri saveBitmapToCache(Bitmap bitmap) {
        try {
            File cachePath = new File(getCacheDir(), "shared_notes");
            if (!cachePath.exists() && !cachePath.mkdirs()) {
                Log.e("ShareNote", "Failed to create cache directory");
                return null;
            }

            File file = new File(cachePath, "note_share_" + System.currentTimeMillis() + ".png");
            try (FileOutputStream stream = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            return FileProvider.getUriForFile(this, getPackageName() + ".provider", file);

        } catch (Exception e) {
            Log.e("ShareNote", "Error saving bitmap: " + e.getMessage(), e);
            return null;
        }
    }

    private void shareNoteAsImage() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(getAttrColor(R.attr.color_1));
        container.setPadding(64, 64, 64, 80);

        // App watermark (atas)
        TextView tvApp = new TextView(this);
        tvApp.setText(getString(R.string.app_name));
        tvApp.setTextColor(getAttrColor(R.attr.text_color));
        tvApp.setAlpha(0.35f);
        tvApp.setTextSize(11f);
        tvApp.setTypeface(tvApp.getTypeface(), Typeface.BOLD);
        tvApp.setPadding(0, 0, 0, 20);
        container.addView(tvApp);

        // Title
        TextView tvTitle = new TextView(this);
        tvTitle.setText(etTitle.getText().toString());
        tvTitle.setTextColor(getAttrColor(R.attr.text_color));
        tvTitle.setTextSize(22f);
        tvTitle.setTypeface(tvTitle.getTypeface(), Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, 10);
        container.addView(tvTitle);

        // Date
        TextView tvDate = new TextView(this);
        tvDate.setText(new SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault())
                .format(new Date(currentNote.getTimestamp())));
        tvDate.setTextColor(getAttrColor(R.attr.text_color));
        tvDate.setAlpha(0.5f);
        tvDate.setTextSize(12f);
        tvDate.setPadding(0, 0, 0, 4);
        container.addView(tvDate);

        // Divider
        View divider = new View(this);
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                (int) (48 * getResources().getDisplayMetrics().density),
                (int) (3 * getResources().getDisplayMetrics().density));
        divParams.setMargins(0, 16, 0, 24);
        divider.setLayoutParams(divParams);
        divider.setBackgroundColor(getAttrColor(R.attr.text_color));
        divider.setAlpha(0.2f);
        container.addView(divider);

        TextView tvContent = new TextView(this);
        tvContent.setText(etContent.getText());
        tvContent.setTextColor(getAttrColor(R.attr.text_color));
        tvContent.setTextSize(15f);
        tvContent.setLineSpacing(8f, 1f);
        tvContent.setMovementMethod(null);
        container.addView(tvContent);

        // Measure & layout manual
        int screenWidth = getWindow().getDecorView().getWidth();
        int widthSpec = View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        container.measure(widthSpec, heightSpec);
        container.layout(0, 0, container.getMeasuredWidth(), container.getMeasuredHeight());

        Bitmap bitmap = Bitmap.createBitmap(
                container.getMeasuredWidth(),
                container.getMeasuredHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        container.draw(canvas);

        Uri uri = saveBitmapToCache(bitmap);
        if (uri == null) {
            Toast.makeText(this, getString(R.string.toast_image_failed), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.share_chooser_title)));
    }


    // ─── Popup Menu, Reminder, Delete ────────────────────────────────────────

    private void showMenuPopup(View anchor) {
        ViewGroup root = (ViewGroup) anchor.getRootView();
        View popupView = getLayoutInflater().inflate(R.layout.popup_menu_note, root, false);

        PopupWindow popup = new PopupWindow(popupView,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(10f);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        popupView.findViewById(R.id.action_reminder).setOnClickListener(v -> {
            setReminder();
            popup.dismiss();
        });

        popupView.findViewById(R.id.action_share).setOnClickListener(v -> {
            shareNoteAsImage();
            popup.dismiss();
        });

        popupView.findViewById(R.id.action_delete).setOnClickListener(v -> {
            confirmDelete();
            popup.dismiss();
        });

        popup.showAsDropDown(anchor, 0, 0);
    }

    private void setReminder() {
        Calendar calendar = Calendar.getInstance();
        new DatePickerDialog(this, (v, y, m, d) -> {
            calendar.set(y, m, d);
            new TimePickerDialog(this, (v1, h, min) -> {
                calendar.set(Calendar.HOUR_OF_DAY, h);
                calendar.set(Calendar.MINUTE, min);
                calendar.set(Calendar.SECOND, 0);

                currentNote.setTimestamp(calendar.getTimeInMillis());
                viewModel.saveNote(this, currentNote);
                scheduleNotification(calendar.getTimeInMillis());

                Toast.makeText(this, getString(R.string.toast_reminder_set), Toast.LENGTH_SHORT).show();
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show();
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void scheduleNotification(long triggerTime) {
        Intent intent = new Intent(this, ReminderReceiver.class);
        intent.putExtra("title", etTitle.getText().toString());
        intent.putExtra("note_id", currentNote.getId());
        intent.putExtra("user_id", viewModel.getUserId());

        PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                currentNote.getId().hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Toast.makeText(this, getString(R.string.toast_alarm_permission_prompt), Toast.LENGTH_LONG).show();
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM));
                return;
            }

            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        } catch (SecurityException e) {
            Log.e(TAG, "Exact alarm permission denied", e);
            Toast.makeText(this, getString(R.string.toast_reminder_failed), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete() {
        DialogUtils.showConfirmDialog(
                this,
                getString(R.string.dialog_delete_note_title),
                getString(R.string.dialog_delete_note_msg),
                getString(R.string.action_delete),
                getString(R.string.action_cancel),
                () -> {
                    getSharedPreferences("notes_" + viewModel.getUserId(), MODE_PRIVATE)
                            .edit().remove(currentNote.getId()).apply();
                    viewModel.deleteNote(this, currentNote.getId());
                    setResult(RESULT_OK);
                    finish();
                },
                null);
    }


    // ─── Text Span & Styling ─────────────────────────────────────────────────

    private void toggleStyle(int style) {
        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();

        if (start == end) {
            return;
        }

        Spannable str = etContent.getText();
        boolean exists = false;

        for (StyleSpan span : str.getSpans(start, end, StyleSpan.class)) {
            if (span.getStyle() == style) {
                str.removeSpan(span);
                exists = true;
            }
        }

        if (!exists) {
            str.setSpan(new StyleSpan(style), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        updateToolbarState();
    }

    private void toggleSpan(Class<? extends CharacterStyle> spanClass) {
        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();

        if (start == end) {
            return;
        }

        Spannable str = etContent.getText();
        Object[] spans = str.getSpans(start, end, spanClass);

        if (spans.length > 0) {
            for (Object span : spans) {
                str.removeSpan(span);
            }
        } else if (spanClass.equals(StrikethroughSpan.class)) {
            str.setSpan(new StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (spanClass.equals(UnderlineSpan.class)) {
            str.setSpan(new UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        updateToolbarState();
    }

    private void openTextColorPicker() {
        openColorPicker(currentTextColor, this::setTextColor, color -> currentTextColor = color);
    }

    private void openHighlightColorPicker() {
        openColorPicker(currentHighlightColor, this::toggleBackgroundColor, color -> currentHighlightColor = color);
    }

    private void openColorPicker(int selectedColor, ColorCallback onOk, ColorCallback onSave) {

        final int[] colors = {
                // Row 1 - Reds & Pinks
                Color.parseColor("#FF0000"),
                Color.parseColor("#FF4444"),
                Color.parseColor("#FF6B6B"),
                Color.parseColor("#FF8787"),
                Color.parseColor("#FFA8A8"),
                Color.parseColor("#FF69B4"),
                Color.parseColor("#F06595"),
                Color.parseColor("#E64980"),

                // Row 2 - Purples
                Color.parseColor("#CC5DE8"),
                Color.parseColor("#AE3EC9"),
                Color.parseColor("#845EF7"),
                Color.parseColor("#7048E8"),
                Color.parseColor("#5C5FE0"),
                Color.parseColor("#4263EB"),
                Color.parseColor("#3B5BDB"),
                Color.parseColor("#364FC7"),

                // Row 3 - Blues & Cyans
                Color.parseColor("#339AF0"),
                Color.parseColor("#1C7ED6"),
                Color.parseColor("#1098AD"),
                Color.parseColor("#0C8599"),
                Color.parseColor("#22B8CF"),
                Color.parseColor("#15AABF"),
                Color.parseColor("#3BC9DB"),
                Color.parseColor("#66D9E8"),

                // Row 4 - Greens
                Color.parseColor("#20C997"),
                Color.parseColor("#0CA678"),
                Color.parseColor("#51CF66"),
                Color.parseColor("#2F9E44"),
                Color.parseColor("#94D82D"),
                Color.parseColor("#74B816"),
                Color.parseColor("#A9E34B"),
                Color.parseColor("#C0EB75"),

                // Row 5 - Yellows & Oranges
                Color.parseColor("#FCC419"),
                Color.parseColor("#F59F00"),
                Color.parseColor("#FFD43B"),
                Color.parseColor("#FFE066"),
                Color.parseColor("#FF922B"),
                Color.parseColor("#E8590C"),
                Color.parseColor("#FD7E14"),
                Color.parseColor("#F76707"),

                // Row 6 - Neutrals
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#F1F3F5"),
                Color.parseColor("#DEE2E6"),
                Color.parseColor("#ADB5BD"),
                Color.parseColor("#6C757D"),
                Color.parseColor("#495057"),
                Color.parseColor("#212529"),
                Color.parseColor("#000000"),
        };

        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(
                        this, R.style.BottomSheetDialogTheme);

        ViewGroup root = findViewById(android.R.id.content);
        View view = getLayoutInflater().inflate(R.layout.bottomsheet_color_picker, root, false);
        androidx.gridlayout.widget.GridLayout container = view.findViewById(R.id.color_container);

        container.setColumnCount(8);

        int swatchSizePx = (int) (40 * getResources().getDisplayMetrics().density);
        int marginPx = (int) (5 * getResources().getDisplayMetrics().density);
        int strokeWidthPx = (int) (2.5f * getResources().getDisplayMetrics().density);
        int selectedStrokeWidthPx = (int) (3.5f * getResources().getDisplayMetrics().density);

        for (int color : colors) {
            View colorView = new View(this);

            androidx.gridlayout.widget.GridLayout.LayoutParams params =
                    new androidx.gridlayout.widget.GridLayout.LayoutParams();
            params.width = swatchSizePx;
            params.height = swatchSizePx;
            params.setMargins(marginPx, marginPx, marginPx, marginPx);
            colorView.setLayoutParams(params);

            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            bg.setColor(color);

            boolean isSelected = color == selectedColor;
            boolean isLight = isLightColor(color);

            if (isSelected) {
                // Stroke tebal + warna kontras untuk selected
                int strokeColor = isLight
                        ? Color.parseColor("#333333")
                        : Color.parseColor("#FFFFFF");
                bg.setStroke(selectedStrokeWidthPx, strokeColor);
                colorView.setScaleX(1.2f);
                colorView.setScaleY(1.2f);
            } else {
                // Stroke tipis agar semua warna terlihat outline-nya
                int strokeColor = isLight
                        ? Color.parseColor("#CCCCCC")
                        : Color.parseColor("#00000033");
                bg.setStroke(strokeWidthPx, strokeColor);
            }

            colorView.setBackground(bg);

            colorView.setOnClickListener(v -> {
                onOk.onColorSelected(color);
                onSave.onColorSelected(color);
                dialog.dismiss();
            });

            container.addView(colorView);
        }

        dialog.setContentView(view);
        dialog.show();
    }

    private boolean isLightColor(int color) {
        double luminance = (0.299 * Color.red(color)
                + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255.0;
        return luminance > 0.6;
    }

    private interface ColorCallback {
        void onColorSelected(int color);
    }

    private void setTextColor(int color) {
        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();

        if (start == end) {
            return;
        }

        Spannable str = etContent.getText();

        for (ForegroundColorSpan s : str.getSpans(start, end, ForegroundColorSpan.class)) {
            str.removeSpan(s);
        }

        str.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        updateToolbarState();
    }

    private void toggleBackgroundColor(int color) {
        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();

        if (start == end) {
            return;
        }

        Spannable str = etContent.getText();
        BackgroundColorSpan[] spans = str.getSpans(start, end, BackgroundColorSpan.class);

        if (spans.length > 0) {
            for (BackgroundColorSpan s : spans) {
                str.removeSpan(s);
            }
        } else {
            str.setSpan(new BackgroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        updateToolbarState();
    }

    private void setAlignment(Layout.Alignment alignment) {
        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();
        Editable editable = etContent.getText();
        String text = editable.toString();

        int pos = text.lastIndexOf('\n', start - 1) + 1;
        while (pos <= end && pos <= editable.length()) {
            int lineEnd = text.indexOf('\n', pos);
            if (lineEnd == -1) {
                lineEnd = editable.length();
            }

            for (AlignmentSpan.Standard s : editable.getSpans(pos, lineEnd, AlignmentSpan.Standard.class)) {
                editable.removeSpan(s);
            }

            editable.setSpan(new AlignmentSpan.Standard(alignment),
                    pos, lineEnd, Spannable.SPAN_INCLUSIVE_INCLUSIVE);

            if (lineEnd >= editable.length()) {
                break;
            }
            pos = lineEnd + 1;
        }
        updateToolbarState();
    }

    private void toggleBullet() {
        int start = etContent.getSelectionStart();
        Editable editable = etContent.getText();
        int lineStart = editable.toString().lastIndexOf('\n', start - 1) + 1;

        if (bulletMode && editable.toString().startsWith("• ", lineStart)) {
            editable.delete(lineStart, lineStart + 2);
            bulletMode = false;
        } else {
            editable.insert(lineStart, "• ");
            bulletMode = true;
        }

        updateToolbarState();
    }

    private void changeTextSize(boolean sizeUp) {
        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();

        if (start == end) {
            Toast.makeText(this, "Select text first", Toast.LENGTH_SHORT).show();
            return;
        }

        Spannable str = etContent.getText();
        AbsoluteSizeSpan[] existing = str.getSpans(start, end, AbsoluteSizeSpan.class);

        float currentSp = BASE_TEXT_SIZE_SP;
        if (existing.length > 0) {
            AbsoluteSizeSpan first = existing[0];
            currentSp = first.getDip() ? first.getSize()
                    : first.getSize() / getResources().getDisplayMetrics().scaledDensity;
        }

        float newSp = Math.max(MIN_TEXT_SIZE_SP,
                Math.min(MAX_TEXT_SIZE_SP, sizeUp ? currentSp + SIZE_STEP_SP : currentSp - SIZE_STEP_SP));

        for (AbsoluteSizeSpan s : existing) {
            int sStart = str.getSpanStart(s);
            int sEnd = str.getSpanEnd(s);
            str.removeSpan(s);

            if (sStart < start) {
                str.setSpan(new AbsoluteSizeSpan((int) currentSp, true),
                        sStart, start, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (sEnd > end) {
                str.setSpan(new AbsoluteSizeSpan((int) currentSp, true),
                        end, sEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        str.setSpan(new AbsoluteSizeSpan((int) newSp, true),
                start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        updateToolbarState();
    }

    private void updateToolbarState() {
        if (etContent == null || etContent.getText() == null) {
            return;
        }

        int start = etContent.getSelectionStart();
        int end = etContent.getSelectionEnd();

        if (start < 0) {
            return;
        }

        Spannable str = etContent.getText();
        int queryStart = start == end ? Math.max(0, start - 1) : start;
        int queryEnd = Math.min(start == end ? Math.max(1, start) : end, str.length());
        queryStart = Math.min(queryStart, queryEnd);

        boolean isBold = false;
        for (StyleSpan s : str.getSpans(queryStart, queryEnd, StyleSpan.class)) {
            if (s.getStyle() == Typeface.BOLD) {
                isBold = true;
                break;
            }
        }
        btnBold.setAlpha(isBold ? 1f : 0.45f);
        btnBold.setSelected(isBold);

        boolean isItalic = false;
        for (StyleSpan s : str.getSpans(queryStart, queryEnd, StyleSpan.class)) {
            if (s.getStyle() == Typeface.ITALIC) {
                isItalic = true;
                break;
            }
        }
        btnItalic.setAlpha(isItalic ? 1f : 0.45f);
        btnItalic.setSelected(isItalic);

        boolean isUnderline = str.getSpans(queryStart, queryEnd, UnderlineSpan.class).length > 0;
        btnUnderline.setAlpha(isUnderline ? 1f : 0.45f);
        btnUnderline.setSelected(isUnderline);

        boolean isStrike = str.getSpans(queryStart, queryEnd, StrikethroughSpan.class).length > 0;
        btnStrike.setAlpha(isStrike ? 1f : 0.45f);
        btnStrike.setSelected(isStrike);

        boolean isHighlight = str.getSpans(queryStart, queryEnd, BackgroundColorSpan.class).length > 0;
        btnHighlight.setAlpha(isHighlight ? 1f : 0.45f);
        btnHighlight.setSelected(isHighlight);

        ForegroundColorSpan[] colorSpans = str.getSpans(queryStart, queryEnd, ForegroundColorSpan.class);
        if (colorSpans.length > 0) {
            btnTextColor.setColorFilter(colorSpans[0].getForegroundColor());
            btnTextColor.setAlpha(1f);
        } else {
            btnTextColor.clearColorFilter();
            btnTextColor.setAlpha(0.45f);
        }

        btnBullet.setAlpha(bulletMode ? 1f : 0.45f);
        btnBullet.setSelected(bulletMode);

        AlignmentSpan.Standard[] alignSpans = str.getSpans(queryStart, queryEnd, AlignmentSpan.Standard.class);
        Layout.Alignment currentAlign = alignSpans.length > 0 ? alignSpans[0].getAlignment() : Layout.Alignment.ALIGN_NORMAL;

        btnAlignLeft.setAlpha(currentAlign == Layout.Alignment.ALIGN_NORMAL ? 1f : 0.45f);
        btnAlignCenter.setAlpha(currentAlign == Layout.Alignment.ALIGN_CENTER ? 1f : 0.45f);
        btnAlignRight.setAlpha(currentAlign == Layout.Alignment.ALIGN_OPPOSITE ? 1f : 0.45f);

        float sizeAlpha = start != end ? 1f : 0.45f;
        btnSizeUp.setAlpha(sizeAlpha);
        btnSizeDown.setAlpha(sizeAlpha);
    }
}