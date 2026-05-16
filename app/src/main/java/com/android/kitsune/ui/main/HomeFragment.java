package com.android.kitsune.ui.main;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.android.kitsune.R;
import com.android.kitsune.base.BaseActivity;
import com.android.kitsune.data.local.UserStorageManager;
import com.android.kitsune.data.session.UserSession;
import com.android.kitsune.ui.common.Refreshable;
import com.android.kitsune.ui.notes.EditNoteActivity;
import com.android.kitsune.ui.notes.Note;
import com.android.kitsune.ui.notes.NoteActivity;
import com.android.kitsune.ui.notes.NoteAdapter;
import com.android.kitsune.ui.notes.NoteViewModel;
import com.android.kitsune.ui.notifications.ActivityItem;
import com.android.kitsune.ui.notifications.NotificationActivity;
import com.android.kitsune.ui.notifications.NotificationAdapter;
import com.android.kitsune.utils.DialogUtils;
import com.facebook.shimmer.ShimmerFrameLayout;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;

public class HomeFragment extends Fragment implements
        MainActivity.ToolbarTitleProvider,
        UserSession.UserSessionListener,
        UserSession.ActivityListener,
        UserSession.ActivityClearedListener,
        Refreshable {

    // ─── Constants & Variables ───────────────────────────────────────────────

    private final String TAG = "HomeFragment";

    private ShimmerFrameLayout shimmerLayout;
    private LottieAnimationView lottieWelcome;
    private TextView tvGreeting, tvUsername, tvDateTime, tvActiveDays, tvViewAll;
    private RecyclerView recyclerViewActivities;
    private NotificationAdapter activityAdapter;
    private LinearLayout emptyActivity;
    private SwipeRefreshLayout swipeRefreshLayout;
    private TextView tvViewAllNotes;
    private RecyclerView recyclerViewNotes;
    private LinearLayout emptyNotes;

    private NoteViewModel noteViewModel;
    private NoteAdapter adapter;
    private final List<ActivityItem> activityList = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEEE, d MMMM yyyy • HH:mm", Locale.getDefault());

    private boolean isFirstLoad = true;

    private ActivityResultLauncher<Intent> noteLauncher;

    private final Runnable timeUpdater = new Runnable() {
        @Override
        public void run() {
            updateDateTime();
            handler.postDelayed(this, 60000);
        }
    };


    // ─── Lifecycle ───────────────────────────────────────────────────────────

    public HomeFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initializeViews(view);
        initUIComponents();
        registerListeners();

        setupActivityRecycler();
        setupRecyclerView();
        setupNoteLauncher();

        if (isFirstLoad) {
            startShimmerInitialLoad(view.findViewById(R.id.scrollViewHome));
            isFirstLoad = false;
        } else {
            loadAllData();
        }

        handler.post(timeUpdater);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof MainActivity) {
            loadNotes();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacksAndMessages(null);
        UserSession.getInstance().setActivityClearedListener(null);
        unregisterListeners();
    }

    @Override
    public int getToolbarTitleRes() {
        return R.string.menu_title_home;
    }


    // ─── Initialization ──────────────────────────────────────────────────────

    private void initializeViews(View view) {
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout);
        shimmerLayout = view.findViewById(R.id.shimmerLayout);
        lottieWelcome = view.findViewById(R.id.lottieWelcome);
        tvGreeting = view.findViewById(R.id.tvGreeting);
        tvUsername = view.findViewById(R.id.tvUsername);
        tvDateTime = view.findViewById(R.id.tvDateTime);
        tvActiveDays = view.findViewById(R.id.tvActiveDays);
        tvViewAll = view.findViewById(R.id.tvViewAll);
        recyclerViewActivities = view.findViewById(R.id.recyclerViewActivities);
        emptyActivity = view.findViewById(R.id.emptyActivity);
        tvViewAllNotes = view.findViewById(R.id.tvViewAllNotes);
        recyclerViewNotes = view.findViewById(R.id.recyclerViewNotes);
        emptyNotes = view.findViewById(R.id.emptyNotes);

        ((BaseActivity) requireActivity()).applyFont(
                tvGreeting,
                tvUsername,
                tvDateTime,
                tvActiveDays,
                tvViewAll,
                tvViewAllNotes
        );
    }

    private void initUIComponents() {
        setupLottie();
        setupRefresh();
        setupBackPress();
        setupClickListeners();
    }

    private void setupLottie() {
        updateLottieBasedOnTheme();
        lottieWelcome.setRepeatCount(LottieDrawable.INFINITE);
        lottieWelcome.playAnimation();
    }

    private void updateLottieBasedOnTheme() {
        boolean isDark = isDarkModeActive();
        if (isDark) {
            lottieWelcome.setAnimation(R.raw.underwater_ocean);
        } else {
            lottieWelcome.setAnimation(R.raw.welcome_animation);
        }
    }

    private boolean isDarkModeActive() {
        int currentNightMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void setupRefresh() {
        swipeRefreshLayout.setOnRefreshListener(this::onRefreshRequested);
    }

    private void setupBackPress() {
        requireActivity().getOnBackPressedDispatcher().addCallback(
                getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        DialogUtils.showConfirmDialog(
                                requireContext(),
                                getString(R.string.dialog_exit_title),
                                getString(R.string.dialog_exit_message),
                                getString(R.string.action_exit),
                                getString(R.string.action_cancel),
                                () -> requireActivity().finishAffinity(),
                                null
                        );
                    }
                }
        );
    }

    private void setupClickListeners() {
        tvViewAll.setOnClickListener(v -> openAllActivities());
        tvViewAllNotes.setOnClickListener(v -> openAllNotes());
    }

    private void registerListeners() {
        UserSession.getInstance().addListener(this);
        UserSession.getInstance().addActivityListener(this);
        UserSession.getInstance().setActivityClearedListener(this);
    }

    private void unregisterListeners() {}

    @Override
    public void onActivitiesCleared() {
        activityList.clear();
        refreshActivityList();
    }


    // ─── Notes & RecyclerView ────────────────────────────────────────────────

    private void setupRecyclerView() {
        adapter = new NoteAdapter(
                new ArrayList<>(),
                note -> openNoteDetail(note.getId()),
                new NoteAdapter.OnSelectionModeListener() {
                    @Override
                    public void onSelectionModeChange(boolean active) {}

                    @Override
                    public void onSelectionCountChange(int count) {}
                },
                true,
                false
        );

        recyclerViewNotes.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        recyclerViewNotes.setHasFixedSize(false);
        recyclerViewNotes.setAdapter(adapter);

        noteViewModel = new ViewModelProvider(requireActivity()).get(NoteViewModel.class);
        String userId = UserSession.getInstance()
                .getUserData(UserSession.getInstance().getUsername()).userId;
        noteViewModel.setUserId(userId);

        loadNotes();

        noteViewModel.getActiveNotes().observe(getViewLifecycleOwner(), notes -> {
            if (notes == null) return;

            List<Note> sorted = new ArrayList<>(notes);
            sorted.sort((n1, n2) -> Long.compare(n2.getTimestamp(), n1.getTimestamp()));
            adapter.updateNotes(sorted);

            boolean isEmpty = sorted.isEmpty();
            emptyNotes.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
            recyclerViewNotes.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        });
    }

    public void refreshNotes() {
        if (noteViewModel != null) {
            noteViewModel.loadNotes(requireContext());
        }
    }

    private void loadNotes() {
        noteViewModel.loadNotes(requireContext());
        noteViewModel.refreshNotes(requireContext());
    }

    private void setupNoteLauncher() {
        noteLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> loadNotes()
        );
    }

    private void openNoteDetail(String noteId) {
        Note selectedNote = noteViewModel.getNoteById(requireContext(), noteId);
        Intent intent = new Intent(requireContext(), EditNoteActivity.class);
        intent.putExtra("note_id", selectedNote.getId());
        noteLauncher.launch(intent);
    }

    private void openAllNotes() {
        startActivity(new Intent(requireContext(), NoteActivity.class));
    }


    // ─── Data Loading & Shimmer ──────────────────────────────────────────────

    private void startShimmerInitialLoad(View scrollView) {
        shimmerLayout.setVisibility(View.VISIBLE);
        scrollView.setVisibility(View.GONE);

        handler.postDelayed(() -> {
            loadAllData();
            shimmerLayout.setVisibility(View.GONE);
            scrollView.setVisibility(View.VISIBLE);
        }, 1200);
    }

    private void loadAllData() {
        try {
            loadUserData();
            loadActivityHistory();
            updateDateTime();
        } catch (Exception e) {
            Log.e(TAG, "Error loading data", e);
        }
    }


    // ─── User Data & Greeting ────────────────────────────────────────────────

    public void loadUserData() {
        String username = Optional.ofNullable(UserSession.getInstance().getUsername())
                .orElse(getString(R.string.title_guest));

        try {
            UserSession.UserData userData = UserSession.getInstance().getUserData(username);
            if (userData != null && getContext() != null) {
                JSONObject profileJson = UserStorageManager.getInstance(getContext())
                        .loadUserProfile(userData.userId);
                if (profileJson != null) {
                    username = profileJson.optString("username", username);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading profile", e);
        }

        tvUsername.setText(username);
        updateGreeting();

        UserSession.getInstance().setFirstLoginIfNotExists();
        tvActiveDays.setText(getString(R.string.active_days_format, UserSession.getInstance().getActiveDays()));
    }

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;

        if (hour < 12) {
            res = R.string.greeting_morning;
        } else if (hour < 15) {
            res = R.string.greeting_afternoon;
        } else if (hour < 19) {
            res = R.string.greeting_evening;
        } else {
            res = R.string.greeting_night;
        }

        tvGreeting.setText(getString(res));
    }

    @SuppressLint("SetTextI18n")
    private void updateDateTime() {
        String date = dateFormat.format(new Date());
        String country = UserSession.getInstance().getDetectedCountryName();
        if (country != null && !country.isEmpty()) {
            tvDateTime.setText(date + " • " + country);
        } else {
            tvDateTime.setText(date);
        }
    }


    // ─── Activity History ────────────────────────────────────────────────────

    private void loadActivityHistory() {
        activityList.clear();
        activityList.addAll(UserSession.getInstance().getActivities());

        if (!UserSession.getInstance().hasAddedLoginActivity() && UserSession.getInstance().isLoggedIn()) {
            UserSession.getInstance().addLoginActivity();
            UserSession.getInstance().setAddedLoginActivity(true);
        }

        refreshActivityList();
    }

    private void setupActivityRecycler() {
        activityAdapter = new NotificationAdapter();
        recyclerViewActivities.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerViewActivities.setNestedScrollingEnabled(false);
        recyclerViewActivities.setAdapter(activityAdapter);
    }

    private void refreshActivityList() {
        if (activityList.isEmpty()) {
            emptyActivity.setVisibility(View.VISIBLE);
            recyclerViewActivities.setVisibility(View.GONE);
            return;
        }

        emptyActivity.setVisibility(View.GONE);
        recyclerViewActivities.setVisibility(View.VISIBLE);

        List<ActivityItem> limited = activityList.subList(0, Math.min(activityList.size(), 5));
        activityAdapter.submitList(new ArrayList<>(limited));
    }

    private void openAllActivities() {
        startActivity(new Intent(requireContext(), NotificationActivity.class));
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).hideNotificationBadge();
        }
    }


    // ─── Session Listeners ───────────────────────────────────────────────────

    @Override
    public void onProfileUpdated() {
        loadUserData();
    }

    @Override
    public void onNewActivity(ActivityItem item) {
        if (item == null) return;

        activityList.add(0, item);
        if (activityList.size() > 10) {
            activityList.subList(10, activityList.size()).clear();
        }

        refreshActivityList();

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showNotificationBadge();
        }
    }


    // ─── Refreshable ─────────────────────────────────────────────────────────

    @Override
    public void onRefreshRequested() {
        handler.postDelayed(() -> {
            try {
                noteViewModel.refreshNotes(requireContext());
                loadAllData();
                Toast.makeText(requireContext(), R.string.toast_home_updated, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Refresh error", e);
            } finally {
                swipeRefreshLayout.setRefreshing(false);
            }
        }, 800);
    }
}