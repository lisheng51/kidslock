package com.kidslock.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * 设置主界面（改造版）。
 * 家长可配置：运行模式、孩子年级/科目、出题间隔、解锁题数、PIN。
 * 并可在"孩子模式/家长模式"之间切换。
 */
public class MainActivity extends AppCompatActivity {

    private PrefManager pref;
    private QuestionBank bank;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvStatus, tvRemaining, tvMode, tvGrade, tvSubjects, tvInterval, tvUnlockCount;
    private TextView tvOnline, tvGuardStatus;
    private Button btnModeToggle, btnGradeDown, btnGradeUp;
    private Button btnEnableChild, btnEnableParent;
    private Button btnConfigOnline, btnToggleOnline;

    private final Runnable updateRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pref = new PrefManager(this);
        bank = QuestionBank.loadFromAssets(this);

        // 锁屏状态下禁止进入设置（防绕过）
        if (pref.isLocked()) {
            Intent lockIntent = new Intent(this, LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(lockIntent);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        initViews();
        setupListeners();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(updateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(updateRunnable);
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvRemaining = findViewById(R.id.tvRemaining);
        tvMode = findViewById(R.id.tvMode);
        tvGrade = findViewById(R.id.tvGrade);
        tvSubjects = findViewById(R.id.tvSubjects);
        tvInterval = findViewById(R.id.tvInterval);
        tvUnlockCount = findViewById(R.id.tvUnlockCount);
        btnModeToggle = findViewById(R.id.btnModeToggle);
        btnGradeDown = findViewById(R.id.btnGradeDown);
        btnGradeUp = findViewById(R.id.btnGradeUp);
        btnEnableChild = findViewById(R.id.btnEnableChild);
        btnEnableParent = findViewById(R.id.btnEnableParent);
        tvOnline = findViewById(R.id.tvOnline);
        tvGuardStatus = findViewById(R.id.tvGuardStatus);
        btnConfigOnline = findViewById(R.id.btnConfigOnline);
        btnToggleOnline = findViewById(R.id.btnToggleOnline);
    }

    private void setupListeners() {
        // 模式切换
        btnModeToggle.setOnClickListener(v -> {
            String next = pref.isChildMode() ? PrefManager.MODE_PARENT : PrefManager.MODE_CHILD;
            pref.setMode(next);
            updateStatus();
            toast(next.equals(PrefManager.MODE_CHILD) ? "已切换到孩子模式" : "已切换到家长模式");
        });

        // 年级调整（1~9）
        btnGradeDown.setOnClickListener(v -> { pref.setGrade(Math.max(1, pref.getGrade() - 1)); updateStatus(); });
        btnGradeUp.setOnClickListener(v -> { pref.setGrade(Math.min(9, pref.getGrade() + 1)); updateStatus(); });

        // 出题间隔
        int[] intButtons = {R.id.btnInt10, R.id.btnInt15, R.id.btnInt30, R.id.btnInt45,
                R.id.btnInt60, R.id.btnInt90, R.id.btnInt120};
        int[] intValues = {10, 15,30,45,60,90,120};
        for (int i = 0; i < intButtons.length; i++) {
            final int mins = intValues[i];
            findViewById(intButtons[i]).setOnClickListener(v -> {
                pref.setIntervalMinutes(mins);
                updateStatus();
                toast("出题间隔设为 " + mins + " 分钟");
            });
        }

        // 解锁题数
        int[] countButtons = {R.id.btnUnlock3, R.id.btnUnlock5, R.id.btnUnlock10};
        int[] countValues = {3, 5, 10};
        for (int i = 0; i < countButtons.length; i++) {
            final int count = countValues[i];
            findViewById(countButtons[i]).setOnClickListener(v -> {
                pref.setUnlockCount(count);
                updateStatus();
                toast("解锁题数设为 " + count + " 题");
            });
        }

        // 在线题库（NAS 后台）
        btnConfigOnline.setOnClickListener(v -> showServerDialog());
        btnToggleOnline.setOnClickListener(v -> {
            if (pref.isOnline()) {
                pref.setOnline(false);
                toast("已关闭联网拉题，改用内置题库");
            } else {
                String u = pref.getServerUrl();
                if (u.isEmpty() || (!QuestionBank.isJsonBankUrl(u) && pref.getChildId().isEmpty())) {
                    toast("请先配置题库地址（后台地址还需填孩子ID）");
                    showServerDialog();
                    return;
                }
                pref.setOnline(true);
                toast("已开启联网拉题");
            }
            updateStatus();
        });

        // PIN
        findViewById(R.id.btnSetPin).setOnClickListener(v -> showPinDialog());

        // 防绕过权限
        findViewById(R.id.btnOpenA11y).setOnClickListener(v -> openAccessibilitySettings());
        findViewById(R.id.btnOpenOverlay).setOnClickListener(v -> openOverlaySettings());

        // 开启孩子模式（立即锁屏）
        btnEnableChild.setOnClickListener(v -> {
            if (!GuardAccessibilityService.isEnabled(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("锁屏守护未开启")
                        .setMessage("未开启时，在 Google TV 上孩子按 HOME 键即可离开答题界面。\n\n建议先开启「锁屏守护」再进入孩子模式。")
                        .setPositiveButton("去开启", (d, w) -> openAccessibilitySettings())
                        .setNegativeButton("仍然锁屏", (d, w) -> enterChildLock())
                        .show();
                return;
            }
            enterChildLock();
        });

        // 切换到家长模式（解锁）
        btnEnableParent.setOnClickListener(v -> {
            pref.setMode(PrefManager.MODE_PARENT);
            pref.setLocked(false);
            pref.setHomeAliasEnabled(this, false);
            pref.stopTimer();
            stopService(new Intent(this, LockService.class));
            toast("已切换到家长模式，不再弹题");
            updateStatus();
        });
    }

    private void enterChildLock() {
        pref.setMode(PrefManager.MODE_CHILD);
        pref.setLocked(true);
        pref.setHomeAliasEnabled(this, true);
        pref.stopTimer();
        startLockService();
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(lockIntent);
    }

    // ==================== 防绕过权限引导 ====================

    private boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    /** 打开系统无障碍设置；Google TV 找不到入口时给出 adb 命令。 */
    private void openAccessibilitySettings() {
        if (tryStart(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
            toast("请在列表中找到「" + getString(R.string.guard_a11y_label) + "」并开启");
            return;
        }
        showAdbHelp("开启锁屏守护", GuardAccessibilityService.adbEnableCommand(this));
    }

    /** 打开悬浮窗授权页；Google TV 通常没有这个页面，回退到 adb 命令。 */
    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            if (tryStart(i)) return;
        }
        showAdbHelp("开启悬浮窗权限",
                "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
    }

    private boolean tryStart(Intent intent) {
        try {
            if (intent.resolveActivity(getPackageManager()) == null) return false;
            startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showAdbHelp(String title, String cmd) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("这台电视的设置里没有对应入口，请在电脑上用 adb 执行：\n\n"
                        + "adb connect <电视IP>:5555\n" + cmd)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showPinDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置家长PIN码（4位数字）");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setRawInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String pin = input.getText().toString().trim();
            if (pin.length() == 4) {
                pref.setParentPin(pin);
                toast("PIN码已设置");
            } else {
                toast("PIN码必须是4位数字");
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showServerDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);

        final EditText etUrl = new EditText(this);
        etUrl.setHint("后台地址 http://192.168.1.10:8000 或题库链接 https://…/questions.json");
        etUrl.setText(pref.getServerUrl());
        etUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        final EditText etChild = new EditText(this);
        etChild.setHint("孩子ID（如 ming；用题库链接时可不填）");
        etChild.setText(pref.getChildId());
        etChild.setInputType(InputType.TYPE_CLASS_TEXT);

        box.addView(etUrl);
        box.addView(etChild);

        new AlertDialog.Builder(this)
                .setTitle("配置在线题库（后台地址或 JSON 链接）")
                .setView(box)
                .setPositiveButton("保存并测试", (d, w) -> {
                    String url = etUrl.getText().toString().trim();
                    String child = etChild.getText().toString().trim();
                    if (url.isEmpty()) {
                        toast("地址不能为空");
                        return;
                    }
                    if (!QuestionBank.isJsonBankUrl(url) && child.isEmpty()) {
                        toast("使用后台地址时必须填写孩子ID");
                        return;
                    }
                    pref.setServerUrl(url);
                    pref.setChildId(child);
                    pref.setOnline(true);
                    updateStatus();
                    testServer(url, child);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void testServer(final String url, final String child) {
        final int grade = pref.getGrade();
        final String edition = pref.getEdition();
        toast("测试连接中…");
        if (QuestionBank.isJsonBankUrl(url)) {
            new Thread(() -> {
                QuestionBank b = QuestionBank.fetchJsonBank(getApplicationContext(), url);
                runOnUiThread(() -> {
                    if (b == null) {
                        toast("下载题库失败：请检查链接是否为原始 JSON 文件、格式是否正确");
                    } else {
                        int n = b.countForGrade(grade);
                        toast("题库下载成功，共 " + b.size() + " 题，" + gradeLabel(grade) + "可用 " + n + " 题"
                                + (n == 0 ? "（本年级没有题，将使用内置题库）" : ""));
                    }
                });
            }).start();
            return;
        }
        new Thread(() -> {
            List<QuestionBank.Question> r = QuestionBank.fetchRoundFromServer(url, child, grade, edition, 1);
            runOnUiThread(() -> toast(r != null && !r.isEmpty() ? "连接成功，已能拉取题目" : "连接失败，请检查地址/后台是否启动"));
        }).start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void startLockService() {
        Intent serviceIntent = new Intent(this, LockService.class);
        serviceIntent.putExtra("action", "start_timer");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void updateStatus() {
        // 模式
        boolean child = pref.isChildMode();
        tvMode.setText("当前模式：" + (child ? "孩子模式（答题解锁）" : "家长模式（不弹题）"));
        btnModeToggle.setText(child ? "切换到家长模式" : "切换到孩子模式");

        // 年级 / 科目
        int grade = pref.getGrade();
        tvGrade.setText("当前年级：" + gradeLabel(grade) + "（教材：" + pref.getEdition() + "）");
        List<String> subs = bank.gradeSubjects(grade);
        tvSubjects.setText("科目范围：" + join(subs, "、"));

        // 间隔 / 题数
        tvInterval.setText("出题间隔（自由观看）：" + pref.getIntervalMinutes() + " 分钟");
        tvUnlockCount.setText("解锁题数：" + pref.getUnlockCount() + " 道连续答对");

        // 状态
        if (pref.isLocked()) {
            tvStatus.setText("当前状态：已锁屏");
            tvStatus.setTextColor(getResources().getColor(R.color.wrong_red));
            tvRemaining.setText("请答题解锁");
        } else if (pref.isTimerActive()) {
            long remaining = pref.getRemainingMillis();
            if (remaining <= 0) {
                tvStatus.setText("当前状态：时间到");
                tvStatus.setTextColor(getResources().getColor(R.color.wrong_red));
            } else {
                long totalSec = remaining / 1000;
                tvStatus.setText("当前状态：自由观看中");
                tvStatus.setTextColor(getResources().getColor(R.color.correct_green));
                tvRemaining.setText("距下次答题：" + String.format("%02d:%02d", totalSec / 60, totalSec % 60));
            }
        } else {
            tvStatus.setText("当前状态：待命");
            tvStatus.setTextColor(getResources().getColor(R.color.text_secondary));
            tvRemaining.setText("");
        }

        // 防绕过权限状态
        if (tvGuardStatus != null) {
            boolean a11y = GuardAccessibilityService.isEnabled(this);
            boolean overlay = canDrawOverlays();
            tvGuardStatus.setText("锁屏守护（拦截 HOME 键）：" + (a11y ? "已开启 ✓" : "未开启 ✗")
                    + "\n悬浮窗权限：" + (overlay ? "已开启 ✓" : "未开启（可选）"));
            tvGuardStatus.setTextColor(getResources().getColor(
                    a11y ? R.color.correct_green : R.color.wrong_red));
        }

        // 在线题库状态
        if (tvOnline != null) {
            if (pref.isOnline() && QuestionBank.isJsonBankUrl(pref.getServerUrl())) {
                tvOnline.setText("在线题库：开启 · 题库链接 " + pref.getServerUrl());
            } else if (pref.isOnline() && !pref.getServerUrl().isEmpty()) {
                tvOnline.setText("在线题库：开启 · 后台 " + pref.getServerUrl() + " · 孩子ID=" + pref.getChildId());
            } else {
                tvOnline.setText("在线题库：关闭（使用内置题库）");
            }
        }
    }

    private static String gradeLabel(int grade) {
        if (grade <= 6) return "小学" + grade + "年级";
        switch (grade) {
            case 7: return "初一";
            case 8: return "初二";
            case 9: return "初三";
            default: return grade + "年级";
        }
    }

    /** 手动拼接（String.join 需 API 26，Android 6.0/API 23 不可用）。 */
    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
