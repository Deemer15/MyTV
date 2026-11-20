package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppsCatalogActivity extends Activity implements AppAdapter.TileListener {
    private static final int COLUMNS = 5;
    static final String EXTRA_RETURN_TO_DASHBOARD = "return_to_dashboard";
    static final String EXTRA_DASHBOARD_APP_POSITION = "dashboard_app_position";
    private FrameLayout root;
    private RecyclerView grid;
    private AppAdapter adapter;
    private FrameLayout sideNav;
    private LinearLayout sideNavPanel;
    private final List<TextView> navItems = new ArrayList<>();
    private int navIndex;
    private boolean edgeLeftArmed;
    private View sideNavReturnFocus;
    private boolean returnToDashboardOnExit;
    private int dashboardAppPosition;
    private boolean transitions;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Config cfg = Config.load(this);
        returnToDashboardOnExit = getIntent().getBooleanExtra(EXTRA_RETURN_TO_DASHBOARD, false);
        dashboardAppPosition = Math.max(0, getIntent().getIntExtra(EXTRA_DASHBOARD_APP_POSITION, 0));
        transitions = cfg.animations && !cfg.reducedMotion;
        float density = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF090B10);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(72), dp(42), dp(72), dp(24));
        TextView title = new TextView(this);
        title.setText("Apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(UiFont.get(this, R.string.font_light));
        page.addView(title);
        TextView hint = new TextView(this);
        hint.setText(returnToDashboardOnExit ? "Up or Back to Dashboard" : "Back or Left to navigate");
        hint.setTextColor(0x88FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setTypeface(UiFont.get(this, R.string.font_regular));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = dp(4);
        hintLp.bottomMargin = dp(18);
        page.addView(hint, hintLp);

        grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(8), dp(8), dp(8), dp(24));
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new AppAdapter(this, this, COLUMNS, cfg, true);
        List<ResolveInfo> leanback = getPackageManager().queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        adapter.reload(Collections.emptyList(), leanback);
        grid.setAdapter(adapter);
        page.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildSideNav();
        setContentView(root);
        grid.post(() -> focusTile(0));
    }

    @Override
    public void onTileFocused(int position, View card) {
        edgeLeftArmed = false;
        card.setForeground(null);
    }

    @Override
    public boolean onTileVerticalKey(int position, int keyCode) {
        if (returnToDashboardOnExit && keyCode == KeyEvent.KEYCODE_DPAD_UP && position < COLUMNS) {
            returnToDashboard();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position % COLUMNS == 0) {
            if (edgeLeftArmed) returnToMainSideNav();
            else edgeLeftArmed = true;
            return true;
        }
        edgeLeftArmed = false;
        return false;
    }

    @Override public void onLibraryRequested() { }
    @Override public void onLibraryFocused() { }
    @Override public void onTileLongPress(int position, View card) { }
    @Override public void onLibraryUp() { }
    @Override public void onOpenInput(String inputId) { }

    private void focusTile(int position) {
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            View card = holder.itemView.findViewById(R.id.card);
            if (card != null) card.requestFocus();
        }
    }

    private void buildSideNav() {
        sideNav = new FrameLayout(this);
        sideNav.setVisibility(View.GONE);
        sideNav.setAlpha(0f);
        sideNav.setFocusable(false);
        sideNav.setClickable(true);
        sideNav.setBackgroundColor(0x88000000);
        sideNavPanel = new LinearLayout(this);
        sideNavPanel.setOrientation(LinearLayout.VERTICAL);
        sideNavPanel.setPadding(dp(22), dp(36), dp(22), dp(28));
        sideNavPanel.setMinimumWidth(dp(250));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[]{0, 0, dp(24), dp(24), dp(24), dp(24), 0, 0});
        bg.setColor(0xF214171D);
        sideNavPanel.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("MyTV");
        title.setTextColor(0xBFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(UiFont.get(this, R.string.font_medium));
        title.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = dp(14);
        sideNavPanel.addView(title, titleLp);

        addNavItem(R.drawable.ic_house, "Dashboard", 0);
        addNavItem(R.drawable.ic_livetv, "TV", 1);
        addNavItem(R.drawable.ic_nav_movies, "Movies", 2);
        addNavItem(R.drawable.ic_nav_shows, "Shows", 3);
        addNavItem(R.drawable.ic_apps_pill, "Apps", 4);
        addNavItem(R.drawable.ic_wifi, "Home Assist", 5);
        addNavItem(R.drawable.ic_settings, "Settings", 6);
        sideNav.addView(sideNavPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        root.addView(sideNav, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        paintNav();
    }

    private void addNavItem(int icon, String text, int index) {
        TextView item = new TextView(this);
        item.setText(text);
        item.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        item.setCompoundDrawablePadding(dp(14));
        item.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        item.setTextColor(Color.WHITE);
        item.setTypeface(UiFont.get(this, R.string.font_regular));
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setPadding(dp(14), dp(7), dp(14), dp(7));
        item.setSingleLine(true);
        item.setFocusable(true);
        item.setFocusableInTouchMode(false);
        item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        item.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                navIndex = index;
                paintNav();
            }
        });
        item.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_UP) {
                navItems.get(Math.max(0, navIndex - 1)).requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                navItems.get(Math.min(navItems.size() - 1, navIndex + 1)).requestFocus();
                return true;
            }
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
                if (navIndex == 4) closeSideNav();
                else returnToMain(navIndex);
                return true;
            }
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                closeSideNav();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(3);
        sideNavPanel.addView(item, lp);
        navItems.add(item);
    }

    private void openSideNav() {
        sideNavReturnFocus = getCurrentFocus();
        sideNavPanel.setTranslationX(-dp(260));
        sideNav.setVisibility(View.VISIBLE);
        sideNav.setAlpha(0f);
        sideNav.animate().alpha(1f).setDuration(160).start();
        sideNavPanel.animate().translationX(0f).setDuration(220).start();
        navIndex = 4;
        paintNav();
        navItems.get(navIndex).post(() -> navItems.get(navIndex).requestFocus());
    }

    private void closeSideNav() {
        sideNavPanel.animate().translationX(-dp(260)).setDuration(160).start();
        sideNav.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            sideNav.setVisibility(View.GONE);
            if (sideNavReturnFocus != null) sideNavReturnFocus.requestFocus();
            else focusTile(0);
        }).start();
    }

    private void paintNav() {
        for (int i = 0; i < navItems.size(); i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(i == navIndex ? 0xFF272C35 : Color.TRANSPARENT);
            if (i == navIndex) bg.setStroke(dp(1), 0x33FFFFFF);
            navItems.get(i).setBackground(bg);
            navItems.get(i).setTextColor(i == navIndex ? Color.WHITE : 0x99FFFFFF);
            navItems.get(i).setScaleX(i == navIndex ? 1.04f : 1f);
            navItems.get(i).setScaleY(i == navIndex ? 1.04f : 1f);
        }
    }

    private void returnToMain(int index) {
        sideNav.setVisibility(View.GONE);
        Intent intent = new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(index == 1 ? "open_tv" : index == 2 ? "focus_movies"
                : index == 3 ? "focus_shows" : index == 5 ? "open_ha"
                : index == 6 ? "open_settings" : "open_dashboard", true);
        startActivity(intent);
        finish();
    }

    private void returnToMainSideNav() {
        sideNav.setVisibility(View.GONE);
        Intent intent = new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_side_nav", true)
                .putExtra("nav_index", 4);
        startActivity(intent);
        finish();
    }

    @SuppressWarnings("deprecation")
    private void returnToDashboard() {
        sideNav.setVisibility(View.GONE);
        Intent intent = new Intent(this, MainActivity.class)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_dashboard", true)
                .putExtra(EXTRA_DASHBOARD_APP_POSITION, dashboardAppPosition);
        startActivity(intent);
        finish();
        if (transitions) overridePendingTransition(R.anim.apps_fade_in, R.anim.apps_fade_out);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (sideNav != null && sideNav.getVisibility() == View.VISIBLE
                && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            returnToMain(0);
            return true;
        }
        if (sideNav != null && sideNav.getVisibility() == View.VISIBLE) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
            if (returnToDashboardOnExit) returnToDashboard();
            else returnToMainSideNav();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
