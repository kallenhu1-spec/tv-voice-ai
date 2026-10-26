package com.home.tvvoice;

import android.Manifest;
import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 语音：按话筒键 → 录音（说完自动停）→ 硅基流动 SenseVoiceSmall 转文字（免费）→
 *   “打开 XX” / “播放 XX” 在本地用规则处理（不花钱）；其他问题交给聊天模型（默认免费模型，失败再用 DeepSeek）。
 * 只连 api.siliconflow.cn；密钥存在应用私有目录；每天提问次数有上限。
 */
public class AssistActivity extends Activity {
    static final String TAG = "TVVOICE";
    static final String API = "https://api.siliconflow.cn/v1";
    static final int RATE = 16000;

    TextView status, heard, answer, hint;
    ScrollView scroll;
    android.widget.ProgressBar meter;
    final Handler main = new Handler(Looper.getMainLooper());
    volatile boolean listening = false, stopRequested = false;
    volatile int session = 0;
    Properties cfg = new Properties();
    volatile String timing = "";   // 每一步耗时，显示在面板底部，方便找出慢在哪
    void addTiming(String step, long ms) { if (ms >= 0) timing += (timing.isEmpty() ? "" : " · ") + step + " " + String.format(Locale.ROOT, "%.1f", ms / 1000.0) + "秒"; Log.i(TAG, "timing " + step + " " + ms); }
    String fg = "";   // 按话筒键时正在用的应用

    // 说法 → 应用包名
    static final Map<String, String> APPS = new LinkedHashMap<>();
    static {
        APPS.put("哔哩哔哩", "com.xiaodianshi.tv.yst"); APPS.put("b站", "com.xiaodianshi.tv.yst"); APPS.put("小电视", "com.xiaodianshi.tv.yst"); APPS.put("bilibili", "com.xiaodianshi.tv.yst");
        APPS.put("云视听极光", "com.ktcp.video"); APPS.put("极光", "com.ktcp.video"); APPS.put("腾讯视频", "com.ktcp.video"); APPS.put("腾讯", "com.ktcp.video");
        APPS.put("芒果", "com.hunantv.market");
        APPS.put("奇异果", "com.gitvdemo.video"); APPS.put("爱奇艺", "com.gitvdemo.video");
        APPS.put("酷喵", "com.cibn.tv"); APPS.put("优酷", "com.cibn.tv");
        APPS.put("投屏", "com.hpplay.happyplay.aw"); APPS.put("乐播", "com.hpplay.happyplay.aw");
        APPS.put("qq音乐", "com.tencent.qqmusictv"); APPS.put("斗鱼", "com.douyu.xl.douyutv"); APPS.put("虎牙", "com.duowan.kiwitv");
        APPS.put("应用中心", "com.sony.dangbeimarket"); APPS.put("应用商店", "com.sony.dangbeimarket");
        APPS.put("电视", "com.sony.dtv.tvx"); APPS.put("直播", "com.sony.dtv.tvx");
    }
    // 搜片时依次尝试的应用
    static final String[] VIDEO_APPS = {"com.ktcp.video", "com.gitvdemo.video", "com.hunantv.market", "com.cibn.tv", "com.xiaodianshi.tv.yst"};

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setGravity(Gravity.BOTTOM);
        w.setLayout(1400, ViewGroup.LayoutParams.WRAP_CONTENT);
        WindowManager.LayoutParams lp = w.getAttributes(); lp.y = 80; lp.dimAmount = 0.35f; w.setAttributes(lp);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(56, 40, 56, 36);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xEE10131C); bg.setCornerRadius(36); box.setBackground(bg);
        status = tv(20, 0xFF8AB4FF); heard = tv(28, Color.WHITE); answer = tv(22, 0xFFE6E6E6); hint = tv(15, 0xFF8C8C96);
        status.setSingleLine(true); heard.setSingleLine(true); heard.setEllipsize(android.text.TextUtils.TruncateAt.END); hint.setSingleLine(true);
        meter = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(20); meter.setProgress(0);
        LinearLayout top = new LinearLayout(this); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(meter, new LinearLayout.LayoutParams(dp(220), dp(8)));
        scroll = new ScrollView(this); scroll.addView(answer);
        box.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        box.addView(heard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)));   // 固定高度，面板不再跳
        box.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));
        setContentView(box);
        loadConfig();
        fg = getIntent().getStringExtra("fg"); if (fg == null) fg = "";
        String u = usageForeground(); if (!u.isEmpty()) fg = u;
        Log.i(TAG, "start fg=" + fg + " (usage=" + u + ")");
        start();
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    TextView tv(float size, int color) {
        TextView t = new TextView(this); t.setTextSize(size); t.setTextColor(color); t.setPadding(0, 2, 0, 2); t.setLineSpacing(4, 1.05f); return t;
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        String f = i.getStringExtra("fg"); if (f != null && !f.isEmpty() && !f.equals(getPackageName())) fg = f;
        String u = usageForeground(); if (!u.isEmpty()) fg = u;
        if (listening) stopRequested = true;   // 正在听时再按话筒键 = 说完了
        else start();
    }

    void start() {
        final int my = ++session;
        stopSpeaking();
        answer.setText(""); heard.setText(""); hint.setText("说完停一下会自动结束 · 想快一点就再按一下话筒键");
        if (cfg.getProperty("key", "").isEmpty()) { show("还没设置 API 密钥", "", "请在电脑上运行 scripts/install.sh 设置密钥"); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { show("没有录音权限", "", ""); return; }
        status.setText("🎤 正在听…"); meter.setVisibility(android.view.View.VISIBLE); meter.setProgress(0);
        new Thread(() -> run(my), "voice").start();
        new Thread(AssistActivity::warmUp, "warm").start();   // 趁你说话时先把网络连接建好
    }

    void show(String s, String h, String a) { main.post(() -> { meter.setVisibility(android.view.View.INVISIBLE); status.setText(s); if (h != null) heard.setText(h); if (a != null) answer.setText(a); }); }
    void hintLater(String s) { main.post(() -> hint.setText(s)); }

    // 硅基流动账户余额不足时（连免费模型都会被拦），改用电视自带的语音识别（索尼内置，免费、不需要账户）。
    boolean noBalance() { return today().equals(getSharedPreferences("usage", MODE_PRIVATE).getString("nobal", "")); }
    void markNoBalance() { getSharedPreferences("usage", MODE_PRIVATE).edit().putString("nobal", today()).apply(); }
    static boolean isBalanceError(Exception e) { String m = String.valueOf(e.getMessage()); return m.contains("402") || m.contains("30001"); }

    android.speech.SpeechRecognizer sr;
    void systemListen(int my) {
        main.post(() -> {
            if (my != session) return;
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { show("出错了", "", "电视自带识别不可用，请给硅基流动账户充值后再试"); return; }
            if (sr != null) sr.destroy();
            sr = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            status.setText("🎤 正在听…（电视自带识别）"); meter.setVisibility(android.view.View.VISIBLE); meter.setProgress(0);
            sr.setRecognitionListener(new android.speech.RecognitionListener() {
                public void onReadyForSpeech(Bundle b) { }
                public void onBeginningOfSpeech() { }
                public void onRmsChanged(float v) { meter.setProgress((int) Math.max(0, Math.min(20, v * 2))); }
                public void onBufferReceived(byte[] b) { }
                public void onEndOfSpeech() { status.setText("识别中…（电视自带识别）"); }
                public void onError(int err) { Log.i(TAG, "system asr error " + err); show("没听清", "", "再按一次话筒键试试"); hintLater("再按话筒键继续说 · 按返回键关闭"); }
                public void onResults(Bundle r) {
                    java.util.ArrayList<String> l = r.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                    final String text = l == null || l.isEmpty() ? "" : l.get(0).trim();
                    Log.i(TAG, "system asr heard: " + text);
                    if (my != session) return;
                    if (text.isEmpty()) { show("没听清", "", "再按一次话筒键试试"); return; }
                    show("", "“" + text + "”", "");
                    new Thread(() -> {
                        try { Thread.sleep(800); if (my != session) return; handle(text, my); } catch (Exception e) { Log.i(TAG, "error " + e); show("出错了", null, friendly(e)); }
                        hintLater("再按话筒键继续说 · 按返回键关闭");
                    }, "voice2").start();
                }
                public void onPartialResults(Bundle b) { }
                public void onEvent(int t, Bundle b) { }
            });
            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            sr.startListening(i);
        });
    }

    void run(int my) {
        if (noBalance()) { systemListen(my); return; }
        timing = "";
        try {
            long tr = SystemClock.elapsedRealtime();
            byte[] wav = record(my);
            addTiming("录音", SystemClock.elapsedRealtime() - tr);
            if (my != session) return;
            if (wav == null) { show("没听到声音", "", "再按一次话筒键试试，拿近一点说"); return; }
            show("识别中…", null, null);
            final long ta = SystemClock.elapsedRealtime();
            Runnable tick = new Runnable() { public void run() {
                if (my != session) return;
                status.setText("识别中… " + (SystemClock.elapsedRealtime() - ta) / 1000 + " 秒"); main.postDelayed(this, 1000); } };
            main.postDelayed(tick, 1000);
            String text;
            try { text = transcribe(wav); }
            catch (Exception e) {
                if (!isBalanceError(e)) throw e;
                markNoBalance();
                main.removeCallbacks(tick);
                show("硅基流动余额不足", "", "今天先改用电视自带的识别，请再说一次");
                main.postDelayed(() -> { if (my == session) systemListen(my); }, 1800);
                return;
            }
            finally { main.removeCallbacks(tick); }
            Log.i(TAG, "asr ms=" + (SystemClock.elapsedRealtime() - ta));
            addTiming("识别", SystemClock.elapsedRealtime() - ta);
            Log.i(TAG, "heard: " + text);
            if (my != session) return;
            if (text.isEmpty()) { show("没听清", "", "再按一次话筒键试试"); return; }
            String fixed = applyAliases(text);
            if (!fixed.equals(text)) { Log.i(TAG, "alias: " + text + " -> " + fixed); text = fixed; }
            show("", "“" + text + "”", "");
            String tn = text.replaceAll("[，。！？、,.!?\\s]", "").toLowerCase(Locale.ROOT);
            boolean willSearch = PLAY.matcher(tn).matches();
            if (!willSearch) Thread.sleep(800);   // 先让你看清识别出的文字再执行；搜索时纠错那 1~2 秒本来就能看到文字，不再额外等
            if (my != session) return;
            handle(text, my);
        } catch (Exception e) {
            Log.i(TAG, "error " + e);
            show("出错了", null, friendly(e));
        }
        hintLater(timing.isEmpty() ? "再按话筒键继续说 · 按返回键关闭" : "耗时：" + timing);
    }

    String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("401")) return "API 密钥不对或已失效，请重新运行 install.sh 设置";
        if (m.startsWith("聊天") && (m.contains("402") || m.contains("403"))) return "免费聊天模型这次没回应，备用的 DeepSeek 需要账户余额。可以过一会儿再问，或者给硅基流动充几块钱";
        if (m.contains("402") || m.contains("403")) return "账户余额不足或没有权限使用这个模型";
        if (m.startsWith("聊天") && m.contains("429")) return "免费聊天模型现在太忙，过一会儿再问";
        if (m.contains("429")) return "请求太频繁，稍后再试";
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException) return "网络连不上，检查一下电视的网络";
        return "错误：" + m;
    }

    // ---------- 录音：说完自动停 ----------
    // 遥控器话筒音量偏小，所以门限放低、要求连续 2 帧（100ms）超过才算开口；录完把音量放大再送去识别。
    byte[] record(int my) throws Exception {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE * 2));
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("录音初始化失败");
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        StringBuilder dbg = new StringBuilder();
        short[] buf = new short[800];                       // 50ms
        listening = true; stopRequested = false;
        // 录音时请正在播放的视频暂停/静音，免得电视声音被话筒收进去、一直判断不出“说完了”
        android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        android.media.AudioManager.OnAudioFocusChangeListener fl = ch -> { };
        am.requestAudioFocus(fl, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
        ar.startRecording();
        long t0 = SystemClock.elapsedRealtime(), lastVoice = 0, firstVoice = 0;
        double[] early = new double[6]; int frames = 0, above = 0; double maxRms = 0; double th = 250;
        try {
            while (my == session) {
                int n = ar.read(buf, 0, buf.length);
                if (n <= 0) break;
                long sum = 0;
                for (int i = 0; i < n; i++) { int v = buf[i]; sum += (long) v * v; pcm.write(v & 0xff); pcm.write((v >> 8) & 0xff); }
                double rms = Math.sqrt(sum / (double) n);
                long now = SystemClock.elapsedRealtime() - t0;
                if (frames < 6) early[frames] = rms;
                if (frames == 5) { double[] e = early.clone(); java.util.Arrays.sort(e); th = Math.max(250, e[3] * 2.2); }
                frames++;
                maxRms = Math.max(maxRms, rms);
                dbg.append(now).append(' ').append((int) rms).append('\n');
                if (rms > th) { above++; if (above >= 2) { lastVoice = now; if (firstVoice == 0) firstVoice = now - 50; } } else above = 0;
                final int lvl = (int) Math.min(20, rms / 60);
                main.post(() -> meter.setProgress(lvl));
                if (stopRequested) break;
                long tail = (lastVoice - firstVoice) < 2500 ? 2000 : 1600;   // 刚开口时多等一会儿（常常是想下一个词），说了一段之后停 1.6 秒算说完
                if (firstVoice > 0 && now - lastVoice > tail) break;
                if (firstVoice == 0 && now > 6000) break;                   // 6 秒没开口
                if (now > 12000) break;                                     // 最长 12 秒
            }
        } finally { ar.stop(); ar.release(); listening = false; am.abandonAudioFocus(fl); }
        byte[] raw = pcm.toByteArray();
        Log.i(TAG, "rec ms=" + (SystemClock.elapsedRealtime() - t0) + " th=" + (int) th + " maxRms=" + (int) maxRms + " firstVoice=" + firstVoice);
        try {   // 调试用：最后一次录音和音量曲线（只在电视本机）
            File d = getExternalFilesDir(null);
            try (FileOutputStream o = new FileOutputStream(new File(d, "last.wav"))) { o.write(wav(raw)); }
            try (FileOutputStream o = new FileOutputStream(new File(d, "last-levels.txt"))) { o.write(("th=" + (int) th + " first=" + firstVoice + "\n" + dbg).getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception ignored) { }
        if (firstVoice == 0 && maxRms < 200) return null;              // 确实没声音
        if (firstVoice > 0) {   // 只保留说话那一段（前后各留一点），上传和识别都更快
            int from = (int) Math.max(0, (firstVoice - 300) * RATE / 1000 * 2);
            int to = (int) Math.min(raw.length, (lastVoice + 450) * RATE / 1000 * 2);
            from -= from % 2; to -= to % 2;
            if (to - from > RATE / 2) raw = java.util.Arrays.copyOfRange(raw, from, to);
        }
        return wav(normalize(raw));
    }

    /** 把音量放大到接近满幅（最多放大 8 倍），遥控器话筒声音小，放大后识别更准。 */
    static byte[] normalize(byte[] pcm) {
        int peak = 1;
        for (int i = 0; i + 1 < pcm.length; i += 2) { int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)); peak = Math.max(peak, Math.abs(v)); }
        double g = Math.min(8.0, 26000.0 / peak);
        if (g <= 1.05) return pcm;
        byte[] out = new byte[pcm.length];
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int w = (int) Math.max(-32768, Math.min(32767, Math.round(v * g)));
            out[i] = (byte) (w & 0xff); out[i + 1] = (byte) ((w >> 8) & 0xff);
        }
        return out;
    }

    static String bars(int n) { StringBuilder s = new StringBuilder(); for (int i = 0; i < n; i++) s.append('▮'); return s.toString(); }

    static byte[] wav(byte[] data) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('R'); o.write('I'); o.write('F'); o.write('F'); le32(o, 36 + data.length);
        for (char c : "WAVEfmt ".toCharArray()) o.write(c);
        le32(o, 16); le16(o, 1); le16(o, 1); le32(o, RATE); le32(o, RATE * 2); le16(o, 2); le16(o, 16);
        o.write('d'); o.write('a'); o.write('t'); o.write('a'); le32(o, data.length);
        o.write(data, 0, data.length);
        return o.toByteArray();
    }
    static void le32(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); o.write((v >> 16) & 0xff); o.write((v >> 24) & 0xff); }
    static void le16(ByteArrayOutputStream o, int v) { o.write(v & 0xff); o.write((v >> 8) & 0xff); }

    // ---------- 语音转文字：SenseVoiceSmall（免费） ----------
    /** 只用 SenseVoice（免费）。它偶尔会卡住，所以 6 秒没结果就重发一次，不再干等 12 秒。 */
    String transcribe(byte[] wav) throws Exception {
        String m = cfg.getProperty("asr_model", "FunAudioLLM/SenseVoiceSmall");
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long t0 = SystemClock.elapsedRealtime();
            try {
                String t = transcribeWith(m, wav);
                Log.i(TAG, "asr via " + m + " try=" + attempt + " ms=" + (SystemClock.elapsedRealtime() - t0));
                return t;
            } catch (java.net.SocketTimeoutException e) { last = e; Log.i(TAG, "asr timeout try=" + attempt); }
        }
        throw last;
    }

    String transcribeWith(String model, byte[] wav) throws Exception {
        String boundary = "----tvvoice" + System.currentTimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n";
        body.write(head.getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        String res = post("/audio/transcriptions", "multipart/form-data; boundary=" + boundary, body.toByteArray(), 6000);
        String t = new JSONObject(res).optString("text", "");
        t = t.replaceAll("<\\|[^|]*\\|>", "")                                   // SenseVoice 的语言/情绪标记
             .replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]", "")   // 表情符号
             .trim();
        return t;
    }

    // ---------- 理解意图 ----------
    static final Pattern OPEN = Pattern.compile("^(请)?(帮我)?(打开|启动|进入|切换到|切到|去)(一下)?(.+)$");
    static final Pattern PLAY = Pattern.compile("^(请)?(帮我)?(我想看|我要看|想看|我想找|我要找|帮我找|播放|放一下|放|搜索|搜一下|搜|找一下|找|来一集|来点|看一下|看)(一下)?(.+)$");

    void handle(String raw, int my) throws Exception {
        String t = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
                .replaceAll("[，。！？、,.!?\\s]", "").toLowerCase(Locale.ROOT)
                .replaceAll("[必逼比毕哔币碧壁笔]站", "b站").replaceAll("b[账帐仗障张章]", "b站").replace("bilibili", "哔哩哔哩").replace("哔哩", "哔哩哔哩").replace("哔哩哔哩哔哩哔哩", "哔哩哔哩");
        if (inputCommand(t)) return;
        if (biliCommand(t)) return;
        Matcher m = OPEN.matcher(t);
        if (m.matches()) { String pkg = findApp(m.group(5)); if (pkg != null && launch(pkg)) { show("已打开", null, ""); finishSoon(); return; } }
        for (Map.Entry<String, String> e : APPS.entrySet()) if (t.equals(e.getKey()) || t.equals(e.getKey() + "tv")) { if (launch(e.getValue())) { show("已打开", null, ""); finishSoon(); return; } }
        m = PLAY.matcher(t);
        if (m.matches()) { searchVideo(m.group(5)); return; }
        // 其他：交给聊天模型
        if (!underLimit()) { show("", null, "今天的提问次数用完了（每天 " + limit() + " 次），明天再来"); return; }
        show("思考中…", null, null);
        final long tc = SystemClock.elapsedRealtime();
        Runnable tick = new Runnable() { public void run() {
            if (my != session) return;
            status.setText("思考中… " + (SystemClock.elapsedRealtime() - tc) / 1000 + " 秒"); main.postDelayed(this, 1000); } };
        main.postDelayed(tick, 1000);
        String reply;
        try { reply = chat(raw); } finally { main.removeCallbacks(tick); }
        Log.i(TAG, "chat ms=" + (SystemClock.elapsedRealtime() - tc));
        addTiming("AI", SystemClock.elapsedRealtime() - tc);
        if (my != session) return;
        if (dispatch(reply, raw)) return;
        show("", null, reply);
        speak(reply, my);
    }

    /** 规则没认出来的话，交给 DeepSeek 理解成指令（JSON）；它判断是聊天才返回回答文字。 */
    boolean dispatch(String reply, String raw) {
        String r = reply.trim();
        if (r.startsWith("SEARCH:")) { searchVideo(r.substring(7).trim()); return true; }
        int a = r.indexOf('{'), b = r.lastIndexOf('}');
        if (a < 0 || b <= a) return false;
        try {
            JSONObject j = new JSONObject(r.substring(a, b + 1));
            String intent = j.optString("intent"), app = j.optString("app"), q = j.optString("query"), in = j.optString("input"), page = j.optString("page");
            Log.i(TAG, "intent " + j);
            switch (intent) {
                case "open": { String pkg = findApp(app.toLowerCase(Locale.ROOT)); if (pkg != null && launch(pkg)) { show("已打开", null, ""); finishSoon(); return true; } break; }
                case "search": searchVideo((app.isEmpty() ? "" : "在" + app.toLowerCase(Locale.ROOT).replace("tv", "").replace("视频", "")) + q); return true;
                case "input": if (inputCommand("切换到" + in.toLowerCase(Locale.ROOT).replaceAll("\\s", ""))) return true; show("没找到这个信号源", null, in); return true;
                case "bili": if (biliCommand("b站" + page)) return true; break;
                case "chat": { String ans = j.optString("answer"); if (!ans.isEmpty()) { show("", null, ans); speak(ans, session); return true; } break; }
            }
            show("没明白要做什么", null, "换个说法试试，比如“打开芒果TV”“播放庆余年”"); return true;
        } catch (Exception e) { Log.i(TAG, "dispatch " + e); return false; }
    }

    /** “切换到 HDMI2”“打开 PS5”“看电视”：用系统的输入源框架切换（新桌面切信号源也是这么做的）。 */
    boolean inputCommand(String t) {
        try {
            android.media.tv.TvInputManager tm = (android.media.tv.TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tm == null) return false;
            List<android.media.tv.TvInputInfo> list = tm.getTvInputList();
            Matcher hm = Pattern.compile("h[dt]m[il1]?(?:接口|口)?([1-4一二三四])").matcher(t);   // 容忍 HTMI、HDM、HDML 这类识别误差
            String want = null;
            if (hm.find()) { String g = hm.group(1); int idx = "一二三四".indexOf(g); want = "hdmi" + (idx >= 0 ? String.valueOf(idx + 1) : g); }
            for (android.media.tv.TvInputInfo in : list) {
                String label = String.valueOf(in.loadLabel(this)).toLowerCase(Locale.ROOT).replaceAll("\\s", "");
                CharSequence custom = in.loadCustomLabel(this);
                String cl = custom == null ? "" : custom.toString().toLowerCase(Locale.ROOT).replaceAll("\\s", "");
                boolean hit = (want != null && label.contains(want))
                        || (!cl.isEmpty() && t.contains(cl))
                        || (label.length() >= 3 && !label.startsWith("hdmi") && t.matches(".*(打开|切换到|切到|看|玩).*") && t.contains(label));
                if (!hit) continue;
                Intent i = new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(in.getId()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Log.i(TAG, "input " + label + " / " + cl + " id=" + in.getId());
                show("切换到 " + (cl.isEmpty() ? in.loadLabel(this) : custom), null, ""); finishSoon(); return true;
            }
            if (t.matches(".*(看电视|电视直播|切换到电视).*")) return launch("com.sony.dtv.tvx") && finishAndTrue("打开电视");
            if (want != null) { Log.i(TAG, "input not found " + want + " in " + list.size()); }
        } catch (Exception e) { Log.i(TAG, "input " + e); }
        return false;
    }
    boolean finishAndTrue(String s) { show(s, null, ""); finishSoon(); return true; }

    /** 用系统的使用记录找“最近一次切到前台的应用”（比无障碍事件更可靠，重装后也不会丢）。 */
    String usageForeground() {
        try {
            android.app.usage.UsageStatsManager um = (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            android.app.usage.UsageEvents ev = um.queryEvents(now - 6 * 3600 * 1000L, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            String last = "";
            PackageManager pm = getPackageManager();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() != android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) continue;
                String p = e.getPackageName();
                if (p == null || p.equals(getPackageName()) || p.equals("com.android.systemui") || p.startsWith("com.sony.dtv.searchapp")) continue;
                if (pm.getLeanbackLaunchIntentForPackage(p) == null && pm.getLaunchIntentForPackage(p) == null) continue;
                last = p;
            }
            return last;
        } catch (Exception ex) { Log.i(TAG, "usage " + ex); return ""; }
    }

    String appName(String pkg) {
        for (Map.Entry<String, String> e : APPS.entrySet()) if (e.getValue().equals(pkg)) return e.getKey();
        return pkg == null || pkg.isEmpty() ? "桌面" : pkg;
    }

    static final String YST = "com.xiaodianshi.tv.yst";
    /** B站（云视听小电视）里的常用入口：在 B站 里说，或者话里带“B站”时生效。地址来自它的安装包。 */
    boolean biliCommand(String t) {
        boolean otherVideoApp = java.util.Arrays.asList(VIDEO_APPS).contains(fg) && !YST.equals(fg);
        // 在 B站里、话里提到 B站，或者不在别的视频应用里（收藏/历史这些指令目前只有 B站 支持）
        boolean inBili = YST.equals(fg) || t.contains("b站") || t.contains("哔哩哔哩") || t.contains("小电视") || !otherVideoApp;
        Log.i(TAG, "bili check fg=" + fg + " inBili=" + inBili);
        if (!inBili) return false;
        String uri = null, name = null;
        // 编号是逐个实测出来的：type=12 收藏、10 历史、19 大会员、21 番剧时间表、22 直播、29 排行榜
        String Y = "yst://com.xiaodianshi.tv.yst?type=";
        if (t.matches(".*(收藏|收藏夹|收藏的).*")) { uri = Y + "12"; name = "我的收藏"; }
        else if (t.matches(".*(历史|看过|观看记录|播放记录|上次看).*")) { uri = Y + "10"; name = "历史记录"; }
        else if (t.matches(".*(大会员|会员中心|vip).*")) { uri = Y + "19"; name = "大会员"; }
        else if (t.matches(".*(番剧时间表|新番时间表|时间表|更新表).*")) { uri = Y + "21"; name = "番剧时间表"; }
        else if (t.matches(".*(直播).*") && !t.matches(".*电视直播.*") && (YST.equals(fg) || t.contains("b站"))) { uri = Y + "22"; name = "直播"; }
        else if (t.matches(".*(排行|排行榜|热门榜).*")) { uri = Y + "29"; name = "排行榜"; }
        else if (t.matches(".*(首页|主页|推荐).*") && !t.matches(".*(搜|播放|看).*")) { uri = "yst://com.xiaodianshi.tv.yst?type=1"; name = "首页"; }
        if (uri == null) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).setPackage(YST)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            Log.i(TAG, "bili " + name + " " + uri);
            show("B站：" + name, null, ""); finishSoon(); return true;
        } catch (Exception e) { Log.i(TAG, "bili failed " + e); return false; }
    }

    String findApp(String name) {
        for (Map.Entry<String, String> e : APPS.entrySet()) if (name.contains(e.getKey())) return e.getValue();
        return null;
    }

    boolean launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    void searchVideo(String q) {
        String app = null;
        for (Map.Entry<String, String> e : APPS.entrySet()) {
            if (q.startsWith("在" + e.getKey()) || q.startsWith("用" + e.getKey())) { app = e.getValue(); q = q.replaceFirst("^[在用]" + Pattern.quote(e.getKey()) + "(tv|视频)?(上|里)?(看|播放|搜)?", ""); break; }
            if (q.endsWith("在" + e.getKey()) || q.endsWith("用" + e.getKey())) { app = e.getValue(); q = q.substring(0, q.length() - e.getKey().length() - 1); break; }
        }
        q = q.replaceAll("(吧|呗|啊|呀|这部剧|这部电影|电视剧|电影)$", "").replaceAll("^(我的|一下|下)", "");
        // 搜索前让 DeepSeek 纠一下错（UP 主名、英文名常被听错），告诉它是在哪个应用里搜
        String tgtName = appName(app != null ? app : (java.util.Arrays.asList(VIDEO_APPS).contains(fg) ? fg : "com.ktcp.video"));
        String fixedQ = correctQuery(q, tgtName);
        if (!fixedQ.equals(q)) { show("", "“" + q + "” → “" + fixedQ + "”", ""); Log.i(TAG, "fix query " + q + " -> " + fixedQ); q = fixedQ; try { Thread.sleep(700); } catch (Exception ignored) { } }
        final String query = q;
        // 五个视频应用都能直接搜片（B站能打开搜索页，关键词是否自动填上待确认）。
        boolean inVideoApp = java.util.Arrays.asList(VIDEO_APPS).contains(fg);
        String target = app != null ? app : (inVideoApp ? fg : "com.ktcp.video");   // 正在用哪个视频应用，就在哪个里搜
        Log.i(TAG, "fg=" + fg + " target=" + target);
        Intent s = searchIntent(target, query);
        if (s != null) {
            try {
                startActivity(s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                Log.i(TAG, "search via " + target + ": " + query);
                show("正在搜索", "“" + query + "”", ""); finishSoon(); return;
            } catch (Exception e) { Log.i(TAG, "search failed " + target + " " + e); }
        }
        if (target.equals(fg)) { show("这个应用还不支持语音直接搜索", "请在应用里搜：" + query, "（说“在极光看" + query + "”可以去云视听极光搜）"); finishSoon10(); return; }
        if (launch(target)) { show("已打开应用", "请在应用里搜索：" + query, "（这个应用还不支持直接搜索）"); finishSoon10(); }
        else show("没找到能搜片的应用", "“" + query + "”", "");
    }

    static Intent searchIntent(String pkg, String q) {
        switch (pkg) {
            case "com.ktcp.video":       // 云视听极光：腾讯开放跳转，关键词不能转义
                return new Intent("com.tencent.qqlivetv.open", android.net.Uri.parse("tenvideo2://?action=9&search_key=" + q)).setPackage(pkg);
            case "com.gitvdemo.video":   // 银河奇异果：搜索结果页
                return new Intent("com.gitvdemo.video.action.ACTION_SEARCHRESULT", android.net.Uri.parse("gala://page?keyword=" + android.net.Uri.encode(q)))
                        .setPackage(pkg).putExtra("keyword", q);
            case "com.cibn.tv":          // 酷喵：搜索页
                return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("cibntv_yingshi://search?keyword=" + android.net.Uri.encode(q))).setPackage(pkg);
            case "com.hunantv.market":   // 芒果TV：搜索页带关键词（从安装包里找到、实测可用）
                return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("mgtvapp://search/ott/searchInput?searchKey=" + android.net.Uri.encode(q))).setPackage(pkg);
            case "com.xiaodianshi.tv.yst": // B站（云视听小电视）：type=11 是搜索页
                return new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("yst://com.xiaodianshi.tv.yst?type=11&keyword=" + android.net.Uri.encode(q)
                        + "&bundle_keyword=" + android.net.Uri.encode(q))).setPackage(pkg);
            default: return null;
        }
    }

    void finishSoon10() { main.postDelayed(this::finish, 6000); }

    void finishSoon() { final String tm = timing; if (!tm.isEmpty()) main.post(() -> hint.setText("耗时：" + tm)); main.postDelayed(this::finish, 2000); }

    // ---------- 聊天：默认免费模型，失败再用 DeepSeek ----------
    String chat(String q) throws Exception {
        String date = new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(new Date());
        String sys = "你是装在家里电视上的语音助手。用简体中文口语回答，简洁，最多4句话，不要用Markdown、列表或表情。今天是" + date
                + "。你无法上网查实时信息（天气、新闻、比分等），遇到这类问题请直说并给出建议。"
                + "你不能直接操作电视，不要说“已为你打开”之类假装做了事情的话。"
                + "用户正在用的应用：" + appName(fg) + "。"
                + "先判断用户想做什么，只输出一行 JSON，不要别的文字："
                + "打开应用 {\"intent\":\"open\",\"app\":\"应用名\"}（应用：哔哩哔哩、云视听极光、芒果TV、奇异果、酷喵、乐播投屏、QQ音乐、斗鱼、虎牙、应用中心、电视）；"
                + "搜片/找视频/找UP主 {\"intent\":\"search\",\"app\":\"指定的应用名或空\",\"query\":\"要搜的词\"}；"
                + "切换信号源 {\"intent\":\"input\",\"input\":\"hdmi1~4或设备名如ps5\"}；"
                + "B站页面 {\"intent\":\"bili\",\"page\":\"收藏|历史|大会员|番剧时间表|直播|排行榜|首页\"}；"
                + "其他闲聊和提问 {\"intent\":\"chat\",\"answer\":\"你的回答\"}。";
        String[] models = {cfg.getProperty("chat_model", "deepseek-ai/DeepSeek-V4.1-Flash"), cfg.getProperty("fallback_model", "deepseek-ai/DeepSeek-V3.2")};
        Exception last = null;
        for (String model : models) {
            if (model.isEmpty()) continue;
            try {
                JSONObject req = new JSONObject();
                req.put("model", model); req.put("max_tokens", 800); req.put("temperature", 0.5); req.put("stream", false);
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "system").put("content", sys));
                msgs.put(new JSONObject().put("role", "user").put("content", q));
                req.put("messages", msgs);
                String res = post("/chat/completions", "application/json", req.toString().getBytes(StandardCharsets.UTF_8), model == models[0] ? 15000 : 15000);
                JSONObject jr = new JSONObject(res);
                String a = jr.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim();
                JSONObject us = jr.optJSONObject("usage");
                if (us != null) logUsage("chat", model, us.optInt("prompt_tokens"), us.optInt("completion_tokens"));
                a = a.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[*#`]", "").trim();
                if (a.isEmpty()) throw new Exception("empty answer");   // 有的模型只“思考”不回答，换下一个
                count();
                Log.i(TAG, "chat via " + model);
                return a;
            } catch (Exception e) { last = e; Log.i(TAG, "model " + model + " failed: " + e.getMessage()); }
        }
        if (isBalanceError(last)) markNoBalance();
        throw new Exception("聊天" + last.getMessage());
    }

    // ---------- 朗读回答：CosyVoice2（约 0.5 分钱/次） ----------
    // 流式朗读：服务器边合成边发回 PCM 声音，收到一点就播一点，不用等整段合成完。
    volatile android.media.AudioTrack track;
    static final int TTS_RATE = 24000;

    void speak(String text, int my) {
        if (!"on".equals(cfg.getProperty("tts", "on")) || noBalance()) return;
        String t = text.length() > 220 ? text.substring(0, 220) : text;
        String model = cfg.getProperty("tts_model", "FunAudioLLM/CosyVoice2-0.5B");
        HttpURLConnection c = null;
        android.media.AudioTrack at = null;
        try {
            JSONObject req = new JSONObject();
            req.put("model", model); req.put("input", t);
            req.put("voice", cfg.getProperty("tts_voice", model + ":anna"));
            req.put("response_format", "pcm"); req.put("sample_rate", TTS_RATE); req.put("stream", true);
            long t0 = SystemClock.elapsedRealtime();
            c = (HttpURLConnection) new URL(API + "/audio/speech").openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(20000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + cfg.getProperty("key", ""));
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(req.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = c.getResponseCode();
            if (code >= 400) {
                InputStream es = c.getErrorStream(); String m = "";
                if (es != null) { ByteArrayOutputStream r = new ByteArrayOutputStream(); byte[] bb = new byte[2048]; int k; while ((k = es.read(bb)) > 0) r.write(bb, 0, k); m = r.toString("UTF-8"); }
                throw new Exception("HTTP " + code + " " + m);
            }
            int min = android.media.AudioTrack.getMinBufferSize(TTS_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            at = new android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, TTS_RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, TTS_RATE), android.media.AudioTrack.MODE_STREAM);
            track = at;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[4096]; int n; boolean started = false; byte carry = 0; boolean hasCarry = false;
            while ((n = in.read(buf)) > 0) {
                if (my != session) break;
                if (!started) { at.play(); started = true; Log.i(TAG, "tts first audio ms=" + (SystemClock.elapsedRealtime() - t0)); }
                // 保证按 2 字节对齐写入
                int off = 0;
                if (hasCarry) { at.write(new byte[]{carry, buf[0]}, 0, 2); off = 1; hasCarry = false; }
                int len = n - off;
                if (len % 2 == 1) { carry = buf[n - 1]; hasCarry = true; len--; }
                if (len > 0 && at.write(buf, off, len) < 0) break;   // 已被停止
            }
            Log.i(TAG, "tts done ms=" + (SystemClock.elapsedRealtime() - t0));
        } catch (Exception e) {
            Log.i(TAG, "tts failed: " + e.getMessage());
            String m = String.valueOf(e.getMessage());
            if (m.contains("402") || m.contains("403")) { markNoBalance(); hintLater("朗读需要硅基流动账户里有一点余额（约 0.5 分钱/次）"); }
        } finally {
            if (c != null) c.disconnect();
            if (at != null) {
                final android.media.AudioTrack fat = at;
                main.postDelayed(() -> { if (track == fat) track = null; try { fat.stop(); } catch (Exception ignored) { } try { fat.release(); } catch (Exception ignored) { } }, 1500);
            }
        }
    }
    void stopSpeaking() {
        android.media.AudioTrack at = track; track = null;
        if (at != null) { try { at.stop(); } catch (Exception ignored) { } try { at.release(); } catch (Exception ignored) { } }
    }

    /** 记账：每次调用付费模型记一行，方便算花了多少钱（只存在电视本机）。 */
    void logUsage(String kind, String model, int in, int out) {
        try (FileOutputStream o = new FileOutputStream(new File(getExternalFilesDir(null), "usage.csv"), true)) {
            o.write((new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(new Date()) + "," + kind + "," + model + "," + in + "," + out + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /** 搜索词纠错：只改明显听错的专有名词，拿不准就原样返回；5 秒没结果也原样返回。 */
    String correctQuery(String q, String app) {
        if (!"on".equals(cfg.getProperty("search_fix", "on")) || q.length() < 2 || !underLimit() || noBalance()) return q;
        // 只在最容易听错的场景纠错：在 B站里搜，或者搜索词里有英文字母。其他应用搜影视剧名一般不会错，直接搜，省 1~2 秒
        if (!app.contains("哔哩") && !app.contains("b站") && !q.matches(".*[a-zA-Z].*")) return q;
        try {
            String model = cfg.getProperty("chat_model", "deepseek-ai/DeepSeek-V4.1-Flash");
            String sys = "你是电视上的搜索纠错助手。用户正在「" + app + "」里用语音搜索，语音识别可能把 B站UP主名字、英文名、影视作品名听错"
                    + "（同音字、英文拼错、被拆成几个英文单词，比如把“觅食meetfood”听成“meat food”）。"
                    + "如果你有把握知道正确写法，就输出正确的搜索词；拿不准就原样输出。不要加解释、引号或标点，只输出搜索词。";
            JSONObject req = new JSONObject();
            req.put("model", model); req.put("max_tokens", 40); req.put("temperature", 0); req.put("stream", false);
            JSONArray msgs = new JSONArray();
            msgs.put(new JSONObject().put("role", "system").put("content", sys));
            msgs.put(new JSONObject().put("role", "user").put("content", q));
            req.put("messages", msgs);
            long t0 = SystemClock.elapsedRealtime();
            JSONObject jr;
            try { jr = new JSONObject(post("/chat/completions", "application/json", req.toString().getBytes(StandardCharsets.UTF_8), 5000)); }
            finally { addTiming("纠错", SystemClock.elapsedRealtime() - t0); }
            String a = jr.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "").trim()
                    .replaceAll("(?s)<think>.*?</think>", "").replaceAll("[“”\"'。，,.！!？?\\n]", "").trim();
            JSONObject us = jr.optJSONObject("usage");
            if (us != null) logUsage("fix", model, us.optInt("prompt_tokens"), us.optInt("completion_tokens"));
            Log.i(TAG, "fix ms=" + (SystemClock.elapsedRealtime() - t0) + " " + q + " -> " + a);
            if (a.isEmpty() || a.length() > q.length() * 3 + 10) return q;   // 异常回答就不用
            return a;
        } catch (Exception e) { Log.i(TAG, "fix failed " + e.getMessage()); return q; }
    }

    // ---------- 每日上限 ----------
    int limit() { try { return Integer.parseInt(cfg.getProperty("daily_limit", "100")); } catch (Exception e) { return 100; } }
    String today() { return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date()); }
    boolean underLimit() { SharedPreferences p = getSharedPreferences("usage", MODE_PRIVATE); return !today().equals(p.getString("day", "")) || p.getInt("n", 0) < limit(); }
    void count() {
        SharedPreferences p = getSharedPreferences("usage", MODE_PRIVATE);
        int n = today().equals(p.getString("day", "")) ? p.getInt("n", 0) : 0;
        p.edit().putString("day", today()).putInt("n", n + 1).apply();
    }

    // ---------- HTTP ----------
    static void warmUp() {
        try { HttpURLConnection c = (HttpURLConnection) new URL("https://api.siliconflow.cn/").openConnection();
            c.setConnectTimeout(5000); c.setReadTimeout(5000); c.setRequestMethod("HEAD"); c.getResponseCode(); c.disconnect(); }
        catch (Exception ignored) { }
    }

    String post(String path, String type, byte[] body) throws Exception { return post(path, type, body, path.startsWith("/audio") ? 12000 : 25000); }

    String post(String path, String type, byte[] body, int readTimeout) throws Exception {
        return new String(postBytes(path, type, body, readTimeout), StandardCharsets.UTF_8);
    }

    byte[] postBytes(String path, String type, byte[] body, int readTimeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(readTimeout);
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + cfg.getProperty("key", ""));
        c.setRequestProperty("Content-Type", type);
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        if (in != null) { byte[] b = new byte[8192]; int k; while ((k = in.read(b)) > 0) r.write(b, 0, k); in.close(); }
        if (code >= 400) { String s = r.toString("UTF-8"); throw new Exception("HTTP " + code + " " + (s.length() > 200 ? s.substring(0, 200) : s)); }
        return r.toByteArray();
    }

    // ---------- 自定义纠错表：files/aliases.txt（UP 主名字、英文名这类识别容易错的） ----------
    List<String[]> aliases;
    String applyAliases(String text) {
        if (aliases == null) {
            aliases = new ArrayList<>();
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new FileInputStream(new File(getFilesDir(), "aliases.txt")), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim(); if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
                    String right = line.substring(line.indexOf('=') + 1).trim();
                    for (String l : line.substring(0, line.indexOf('=')).split("\\|")) {
                        String k = l.replaceAll("\\s", "").toLowerCase(Locale.ROOT);
                        if (!k.isEmpty()) aliases.add(new String[]{k, right});
                    }
                }
            } catch (Exception ignored) { }
            java.util.Collections.sort(aliases, (x, y) -> y[0].length() - x[0].length());   // 长的先替换
        }
        String norm = text.replaceAll("\\s", "");
        String low = norm.toLowerCase(Locale.ROOT);
        for (String[] a : aliases) {
            int i = low.indexOf(a[0]);
            if (i < 0) continue;
            if (low.contains(a[1].replaceAll("\\s", "").toLowerCase(Locale.ROOT))) continue;   // 已经是正确写法
            norm = norm.substring(0, i) + a[1] + norm.substring(i + a[0].length());
            low = norm.toLowerCase(Locale.ROOT);
        }
        return norm;
    }

    // ---------- 配置：私有目录 files/config.properties ----------
    void loadConfig() {
        File priv = new File(getFilesDir(), "config.properties");
        File ext = new File(getExternalFilesDir(null), "config.properties");   // 安装脚本的备用通道：读到后立即搬进私有目录并删除
        try {
            if (ext.exists()) {
                try (InputStream i = new FileInputStream(ext); OutputStream o = new FileOutputStream(priv)) { byte[] b = new byte[4096]; int k; while ((k = i.read(b)) > 0) o.write(b, 0, k); }
                ext.delete();
            }
            if (priv.exists()) try (InputStream i = new FileInputStream(priv)) { cfg.load(new java.io.InputStreamReader(i, StandardCharsets.UTF_8)); }
        } catch (Exception e) { Log.i(TAG, "config " + e); }
    }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_SEARCH) { onNewIntent(getIntent()); return true; }
        return super.onKeyDown(code, e);
    }
    @Override protected void onPause() { super.onPause(); if (isFinishing()) session++; }
    @Override protected void onDestroy() { session++; stopSpeaking(); if (sr != null) sr.destroy(); super.onDestroy(); }
}
