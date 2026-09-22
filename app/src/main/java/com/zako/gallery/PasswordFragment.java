package com.zako.gallery;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 密码 page: set the lock password, engage the lock with the "锁定主页"
 * switch, and earn a short window in which the switch may be flipped freely.
 *
 * <p>The switch position and the lock state are the same value
 * ({@link AppLock#isLocked}). Flipping it on is always allowed; flipping it
 * off requires the password, which only opens a {@link AppLock#GRACE_MS}
 * grace window — it never releases the lock by itself. While the lock is
 * engaged and no window is open, tapping the switch is refused with a
 * toast.</p>
 */
public class PasswordFragment extends Fragment implements MainActivity.Page {

    private static final long TICK_MS = 100L;

    private View cardSet;
    private View cardLock;
    private View unlockSection;
    private TextView verifiedHint;
    private SwitchMaterial switchLock;
    private EditText etNew;
    private EditText etConfirm;
    private EditText etUnlock;
    private TextView tvSetError;
    private TextView tvUnlockError;

    /** True while the switch is being positioned programmatically. */
    private boolean restoring = false;

    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    /** Ticks the grace-window countdown while it is open. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            if (AppLock.isAuthorized(requireContext())) {
                updateCountdown();
                tickHandler.postDelayed(this, TICK_MS);
            } else {
                // Window just expired: the switch freezes again.
                refresh();
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_password, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        cardSet = view.findViewById(R.id.card_set_password);
        cardLock = view.findViewById(R.id.card_lock);
        unlockSection = view.findViewById(R.id.unlock_section);
        verifiedHint = view.findViewById(R.id.tv_verified_hint);
        switchLock = view.findViewById(R.id.switch_lock);
        tvSetError = view.findViewById(R.id.tv_set_error);
        tvUnlockError = view.findViewById(R.id.tv_unlock_error);

        // TextInputEditText children carry no id; take them off the layouts.
        etNew = innerEditText(view.findViewById(R.id.til_new));
        etConfirm = innerEditText(view.findViewById(R.id.til_confirm));
        etUnlock = innerEditText(view.findViewById(R.id.til_unlock));

        view.findViewById(R.id.btn_set_password).setOnClickListener(v -> setPassword());
        view.findViewById(R.id.btn_unlock).setOnClickListener(v -> unlock());

        switchLock.setOnCheckedChangeListener((button, checked) -> {
            if (restoring) return;
            if (checked) {
                // Engaging the lock is always allowed, no password needed.
                AppLock.setSwitchOn(requireContext(), true);
                refresh();
                return;
            }
            // Releasing it: only while the grace window is open.
            if (!AppLock.isAuthorized(requireContext())) {
                bounceSwitch();
                Toast.makeText(getContext(), R.string.toast_lock_locked,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            AppLock.setSwitchOn(requireContext(), false);
            refresh();
        });

        // Any typing clears the matching error line.
        addErrorClearer(etNew, tvSetError);
        addErrorClearer(etConfirm, tvSetError);
        addErrorClearer(etUnlock, tvUnlockError);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        tickHandler.removeCallbacks(ticker);
    }

    /** Called by the host whenever this page becomes the visible one. */
    @Override
    public void onPageShown() {
        refresh();
    }

    /** Called by the host once the gallery store has been seeded/loaded. */
    @Override
    public void onStoreReady() {
        // No gallery data on this page.
    }

    private void setPassword() {
        String a = etNew.getText().toString();
        String b = etConfirm.getText().toString();
        if (a.isEmpty()) {
            showError(tvSetError, R.string.pw_error_empty);
            return;
        }
        if (!AppLock.hasValidCharset(a)) {
            showError(tvSetError, R.string.pw_error_charset);
            return;
        }
        if (!a.equals(b)) {
            showError(tvSetError, R.string.pw_error_mismatch);
            return;
        }
        AppLock.setPassword(requireContext(), a);
        etNew.setText("");
        etConfirm.setText("");
        tvSetError.setVisibility(View.GONE);
        Toast.makeText(getContext(), R.string.pw_set_done, Toast.LENGTH_SHORT).show();
        refresh();
    }

    /**
     * Verifies the password. Success only opens the grace window — the lock
     * stays engaged until the switch is actually flipped off (which also
     * closes the window for good).
     */
    private void unlock() {
        String p = etUnlock.getText().toString();
        if (p.isEmpty()) {
            showError(tvUnlockError, R.string.pw_error_empty);
            return;
        }
        if (!AppLock.verify(requireContext(), p)) {
            showError(tvUnlockError, R.string.pw_error_wrong);
            return;
        }
        AppLock.authorize(requireContext());
        etUnlock.setText("");
        Toast.makeText(getContext(), R.string.pw_unlocked, Toast.LENGTH_SHORT).show();
        refresh();
    }

    /** Mirrors the persisted state into the two cards. */
    private void refresh() {
        boolean hasPw = AppLock.hasPassword(requireContext());
        cardSet.setVisibility(hasPw ? View.GONE : View.VISIBLE);
        cardLock.setVisibility(hasPw ? View.VISIBLE : View.GONE);
        if (!hasPw) return;

        boolean locked = AppLock.isLocked(requireContext());
        boolean authorized = AppLock.isAuthorized(requireContext());
        restoring = true;
        switchLock.setChecked(locked);
        restoring = false;
        unlockSection.setVisibility(locked && !authorized ? View.VISIBLE : View.GONE);
        verifiedHint.setVisibility(locked && authorized ? View.VISIBLE : View.GONE);
        if (!locked) {
            tvUnlockError.setVisibility(View.GONE);
            etUnlock.setText("");
        }

        tickHandler.removeCallbacks(ticker);
        if (authorized) {
            updateCountdown();
            tickHandler.postDelayed(ticker, TICK_MS);
        }
    }

    /** Shows the remaining seconds of the grace window. */
    private void updateCountdown() {
        long leftMs = AppLock.authorityRemainingMs(requireContext());
        int seconds = (int) ((leftMs + 999) / 1000);
        verifiedHint.setText(getString(R.string.pw_verified_countdown, seconds));
    }

    /** Keeps the switch visually on after a refused attempt to turn it off. */
    private void bounceSwitch() {
        restoring = true;
        switchLock.setChecked(true);
        restoring = false;
    }

    private void showError(TextView target, int stringRes) {
        target.setText(stringRes);
        target.setVisibility(View.VISIBLE);
    }

    private static void addErrorClearer(EditText edit, TextView error) {
        edit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                error.setVisibility(View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    /** The TextInputEditText inside a TextInputLayout (it has no id). */
    private static EditText innerEditText(TextInputLayout til) {
        if (til.getEditText() != null) return til.getEditText();
        return new EditText(til.getContext());
    }
}
