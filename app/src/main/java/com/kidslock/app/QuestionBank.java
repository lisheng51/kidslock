package com.kidslock.app;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 通用题库：
 *  - 离线：从 assets/questions.json 加载内置题库；
 *  - 在线（后台）：从 NAS 后台 /api/next 拉题（后台已完成弱项优先选题），/api/submit 回传进度；
 *  - 在线（题库链接）：地址以 .json 结尾时直接下载整个题库文件，在本机按年级 + 弱项优先选题，
 *    并缓存最近一次成功下载的版本，断网时先用缓存。
 *
 * 在线任何失败（超时/404/JSON 异常）均返回 null / 静默失败，由调用方回退内置题库，
 * 保证断网时仍可用。所有网络方法均在后台线程执行（Android 6.0+ 禁止主线程网络）。
 */
public class QuestionBank {

    private static final String TAG = "QuestionBank";
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 6000;

    private static final List<String> PRIMARY_SUBJECTS = Arrays.asList("语文", "数学", "英语", "荷兰语");
    private static final List<String> JUNIOR_SUBJECTS =
            Arrays.asList("语文", "数学", "英语", "荷兰语", "物理", "历史", "道德与法治");

    public static class Question {
        public String id;
        public String subject;
        public int grade;
        public String edition;
        public String unit;
        public String knowledge;
        public String type;
        public String stem;
        public List<String> options = new ArrayList<>();
        public String answer;
        public String explanation;
    }

    private final List<Question> all = new ArrayList<>();
    private final Random random = new Random();

    // ==================== 离线：assets ====================

    /** 从 assets 加载内置题库（离线优先）。 */
    public static QuestionBank loadFromAssets(Context context) {
        QuestionBank bank = new QuestionBank();
        try {
            InputStream is = context.getAssets().open("questions.json");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            bank.parse(sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "load assets failed", e);
        }
        return bank;
    }

    private void parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray arr = root.getJSONArray("questions");
        for (int i = 0; i < arr.length(); i++) {
            all.add(parseQuestion(arr.getJSONObject(i)));
        }
    }

    private static Question parseQuestion(JSONObject o) throws Exception {
        Question q = new Question();
        q.id = o.optString("id");
        q.subject = o.optString("subject");
        q.grade = o.optInt("grade");
        q.edition = o.optString("edition");
        q.unit = o.optString("unit");
        q.knowledge = o.optString("knowledge");
        q.type = o.optString("type");
        q.stem = o.optString("stem");
        q.answer = o.optString("answer");
        q.explanation = o.optString("explanation");
        JSONArray opts = o.optJSONArray("options");
        if (opts != null) {
            for (int j = 0; j < opts.length(); j++) q.options.add(opts.getString(j));
        }
        return q;
    }

    // ==================== 在线：题库 JSON 链接 ====================

    private static final String CACHE_FILE = "remote_bank.json";
    private static final String CACHE_URL_FILE = "remote_bank.url";
    private static final int JSON_READ_TIMEOUT_MS = 10000;

    /** 地址是否为题库 JSON 文件链接（路径以 .json 结尾），而不是后台服务器地址。 */
    public static boolean isJsonBankUrl(String url) {
        if (url == null) return false;
        String path = url.trim();
        int cut = path.indexOf('?');
        if (cut >= 0) path = path.substring(0, cut);
        cut = path.indexOf('#');
        if (cut >= 0) path = path.substring(0, cut);
        return path.toLowerCase().endsWith(".json");
    }

    /** GitHub 网页链接（/blob/）转成原始文件链接，避免拿到 HTML。 */
    static String toRawUrl(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        if (u.matches("^https?://github\\.com/[^/]+/[^/]+/blob/.+")) {
            u = u.replaceFirst("^https?://github\\.com/", "https://raw.githubusercontent.com/")
                    .replaceFirst("/blob/", "/");
        }
        return u;
    }

    /**
     * 下载题库 JSON（格式同 assets/questions.json），成功后缓存到本地，返回题库；失败返回 null。
     * 必须在后台线程调用。
     */
    public static QuestionBank fetchJsonBank(Context context, String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(toRawUrl(url)).openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(JSON_READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "fetchJsonBank HTTP " + code);
                return null;
            }
            String body = readAll(conn.getInputStream());
            QuestionBank bank = new QuestionBank();
            bank.parse(body);
            if (bank.validCount() == 0) {
                Log.w(TAG, "fetchJsonBank: no valid questions");
                return null;
            }
            writeFile(context, CACHE_FILE, body);
            writeFile(context, CACHE_URL_FILE, url.trim());
            return bank;
        } catch (Exception e) {
            Log.w(TAG, "fetchJsonBank failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 读取上次成功下载的题库缓存（仅当链接未变），没有则返回 null。 */
    public static QuestionBank loadCachedJsonBank(Context context, String url) {
        try {
            String cachedUrl = readFile(context, CACHE_URL_FILE);
            if (cachedUrl == null || !cachedUrl.equals(url.trim())) return null;
            String body = readFile(context, CACHE_FILE);
            if (body == null) return null;
            QuestionBank bank = new QuestionBank();
            bank.parse(body);
            return bank.validCount() > 0 ? bank : null;
        } catch (Exception e) {
            Log.w(TAG, "loadCachedJsonBank failed: " + e.getMessage());
            return null;
        }
    }

    private static void writeFile(Context context, String name, String content) throws Exception {
        try (OutputStream os = context.openFileOutput(name, Context.MODE_PRIVATE)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readFile(Context context, String name) {
        try (InputStream is = context.openFileInput(name)) {
            return readAll(is);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 在线：NAS 后台 ====================

    /**
     * 从后台拉取一轮题目（后台已按弱项优先选好）。成功返回题目列表，失败返回 null。
     * 必须在后台线程调用。
     */
    public static List<Question> fetchRoundFromServer(String baseUrl, String childId,
                                                      int grade, String edition, int count) {
        HttpURLConnection conn = null;
        try {
            String url = normalizeBase(baseUrl) + "/api/next?child_id=" + enc(childId)
                    + "&count=" + count + "&grade=" + grade + "&edition=" + enc(edition);
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "fetchRound HTTP " + code);
                return null;
            }
            String body = readAll(conn.getInputStream());
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.getJSONArray("questions");
            List<Question> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Question q = parseQuestion(arr.getJSONObject(i));
                if (isValid(q)) out.add(q); // 格式不对的题跳过，避免孩子被无解的题卡住
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "fetchRoundFromServer failed: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 回传答题结果到后台（更新掌握度）。resultsJson 形如 [{"knowledge":..,"correct":true}]。
     * 静默失败，不影响本地解锁。必须在后台线程调用。
     */
    public static void submitResults(String baseUrl, String childId, String resultsJson) {
        HttpURLConnection conn = null;
        try {
            String url = normalizeBase(baseUrl) + "/api/submit";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            String body = "child_id=" + enc(childId) + "&results=" + enc(resultsJson);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
        } catch (Exception e) {
            Log.w(TAG, "submitResults failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String normalizeBase(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 选题（离线用） ====================

    /** 年级 → 科目集（小学语数英；初中主科）。 */
    public List<String> gradeSubjects(int grade) {
        return grade <= 6 ? new ArrayList<>(PRIMARY_SUBJECTS) : new ArrayList<>(JUNIOR_SUBJECTS);
    }

    private List<Question> filter(int grade, List<String> subjects) {
        List<Question> out = new ArrayList<>();
        for (Question q : all) {
            if (q.grade != grade) continue;
            if (subjects != null && !subjects.contains(q.subject)) continue;
            if (!isValid(q)) continue;
            out.add(q);
        }
        return out;
    }

    /** 能在锁屏上正常作答：有题干，2~4 个选项，且答案就是其中一个选项。 */
    private static boolean isValid(Question q) {
        return q.stem != null && !q.stem.isEmpty()
                && q.options.size() >= 2 && q.options.size() <= 4
                && q.answer != null && q.options.contains(q.answer);
    }

    private int validCount() {
        int n = 0;
        for (Question q : all) if (isValid(q)) n++;
        return n;
    }

    /** 某年级可用（格式有效）的题目数，用于设置页测试连接时提示。 */
    public int countForGrade(int grade) {
        return filter(grade, null).size();
    }

    /**
     * 抽取一轮题目（默认 requiredCount 道），弱项优先。
     * masteryJson: { "知识点": 掌握度0~1 }
     */
    public List<Question> getRound(int grade, List<String> subjects, int count, String masteryJson) {
        List<Question> pool = filter(grade, subjects);
        if (pool.isEmpty()) return new ArrayList<>();
        JSONObject mastery = new JSONObject();
        try {
            if (masteryJson != null) mastery = new JSONObject(masteryJson);
        } catch (Exception ignore) {
        }

        List<Question> picked = new ArrayList<>();
        List<Question> working = new ArrayList<>(pool);
        for (int n = 0; n < count && !working.isEmpty(); n++) {
            double totalW = 0;
            double[] weights = new double[working.size()];
            for (int i = 0; i < working.size(); i++) {
                Question q = working.get(i);
                double m = mastery.optDouble(q.knowledge, 0.5);
                double w = Math.max(0.05, 1.0 - m); // 掌握度越低权重越高
                weights[i] = w;
                totalW += w;
            }
            double r = random.nextDouble() * totalW;
            int idx = working.size() - 1;
            for (int i = 0; i < weights.length; i++) {
                r -= weights[i];
                if (r <= 0) {
                    idx = i;
                    break;
                }
            }
            Question chosen = working.remove(idx);
            Collections.shuffle(chosen.options, random);
            picked.add(chosen);
        }
        return picked;
    }

    public int size() {
        return all.size();
    }
}
