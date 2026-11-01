package tw.nekomimi.nekogram.xray;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Встроенное Xray-ядро: поднимает локальный SOCKS5 на 127.0.0.1:PORT,
 * а Telegram ходит в него как в обычный SOCKS5-прокси.
 * Источник — ссылка на подписку (http/https) или одна/несколько ссылок vless://.
 */
public final class XrayCore {

    private static final String TAG = "XrayCore";
    private static final String PREFS = "xray_core";
    private static final String K_ENABLED = "enabled";
    private static final String K_SOURCE = "source";
    private static final String K_CONFIG = "last_config";
    private static final String K_NODES = "last_nodes";

    public static final String LOCAL_HOST = "127.0.0.1";
    public static final int LOCAL_PORT = 17923;

    private static final Object lock = new Object();
    private static CoreController controller;
    private static volatile boolean running;

    private XrayCore() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(K_ENABLED, false);
    }

    public static boolean isRunning() {
        return running;
    }

    // ---------- запуск при старте приложения ----------

    public static void autoStart() {
        if (!isEnabled()) return;
        new Thread(() -> {
            // сразу стартуем на кэшированном конфиге, чтобы телега не ждала сеть
            String cached = prefs().getString(K_CONFIG, null);
            if (!TextUtils.isEmpty(cached)) {
                startCore(cached);
                applyProxy();
            }
            // потом тихо обновляем подписку
            try {
                String fresh = buildConfigFromSource(prefs().getString(K_SOURCE, ""));
                if (fresh != null && !fresh.equals(cached)) {
                    prefs().edit().putString(K_CONFIG, fresh).apply();
                    restartCore(fresh);
                    applyProxy();
                }
            } catch (Throwable e) {
                Log.w(TAG, "subscription refresh failed", e);
            }
        }, "xray-autostart").start();
    }

    // ---------- включение / выключение из UI ----------

    public interface Result {
        void done(boolean ok, String message);
    }

    public static void enable(String source, Result cb) {
        new Thread(() -> {
            boolean ok;
            String msg;
            try {
                String cfg = buildConfigFromSource(source);
                if (cfg == null) throw new IllegalStateException("не найдено ни одного vless-сервера");
                prefs().edit()
                        .putString(K_SOURCE, source)
                        .putString(K_CONFIG, cfg)
                        .putBoolean(K_ENABLED, true)
                        .apply();
                restartCore(cfg);
                ok = running;
                msg = ok ? "Xray запущен, серверов: " + prefs().getInt(K_NODES, 0) : "ядро не запустилось";
                if (ok) applyProxy();
            } catch (Throwable e) {
                Log.e(TAG, "enable failed", e);
                ok = false;
                msg = "Ошибка: " + e.getMessage();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            AndroidUtilities.runOnUIThread(() -> cb.done(fOk, fMsg));
        }, "xray-enable").start();
    }

    public static void disable() {
        prefs().edit().putBoolean(K_ENABLED, false).apply();
        new Thread(XrayCore::stopCore, "xray-stop").start();
        AndroidUtilities.runOnUIThread(() -> {
            SharedConfig.ProxyInfo ours = findOurProxy();
            if (ours != null) {
                SharedConfig.deleteProxy(ours); // сам выключит прокси, если он был текущим
            }
        });
    }

    // ---------- ядро ----------

    private static void ensureController() {
        if (controller != null) return;
        Libv2ray.initCoreEnv(ApplicationLoader.applicationContext.getFilesDir().getAbsolutePath(), "");
        controller = Libv2ray.newCoreController(new CoreCallbackHandler() {
            @Override
            public long startup() {
                return 0;
            }

            @Override
            public long shutdown() {
                return 0;
            }

            @Override
            public long onEmitStatus(long code, String s) {
                Log.d(TAG, "status " + code + ": " + s);
                return 0;
            }
        });
    }

    private static void startCore(String config) {
        synchronized (lock) {
            if (running) return;
            try {
                ensureController();
                controller.startLoop(config, 0);
                running = true;
            } catch (Throwable e) {
                Log.e(TAG, "start failed", e);
                running = false;
            }
        }
    }

    private static void stopCore() {
        synchronized (lock) {
            if (controller != null && running) {
                try {
                    controller.stopLoop();
                } catch (Throwable e) {
                    Log.w(TAG, "stop failed", e);
                }
            }
            running = false;
        }
    }

    private static void restartCore(String config) {
        stopCore();
        startCore(config);
    }

    // ---------- прокси в телеграме ----------

    private static ProxySettings ourSettings() {
        return ProxySettings.builder()
                .setAddress(LOCAL_HOST)
                .setPort(LOCAL_PORT)
                .setUser("")
                .setPassword("")
                .setSecret("")
                .setType(ProxySettings.Type.SOCKS5)
                .build();
    }

    private static SharedConfig.ProxyInfo findOurProxy() {
        ProxySettings ours = ourSettings();
        for (SharedConfig.ProxyInfo info : SharedConfig.getProxyList()) {
            if (Objects.equals(info.settings, ours)) return info;
        }
        return null;
    }

    private static void applyProxy() {
        AndroidUtilities.runOnUIThread(() -> {
            SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(ourSettings()));
            if (SharedConfig.currentProxy != info || !SharedConfig.isProxyEnabled()) {
                SharedConfig.setCurrentProxy(info);
            }
        });
    }

    // ---------- подписка ----------

    private static String buildConfigFromSource(String source) throws Exception {
        source = source == null ? "" : source.trim();
        if (source.isEmpty()) throw new IllegalArgumentException("пустая ссылка");
        String body;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            body = download(source);
        } else {
            body = source;
        }
        List<JSONObject> outbounds = parseNodes(body);
        if (outbounds.isEmpty()) return null;
        prefs().edit().putInt(K_NODES, outbounds.size()).apply();
        return buildConfig(outbounds).toString();
    }

    private static String download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        // с таким UA панели отдают список ссылок, а не json под конкретный клиент
        c.setRequestProperty("User-Agent", "v2rayNG/1.10.0");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static List<JSONObject> parseNodes(String body) {
        List<JSONObject> result = new ArrayList<>();
        String text = body.trim();

        // 1) json (полный конфиг xray или массив конфигов)
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                JSONArray configs = text.startsWith("[") ? new JSONArray(text) : new JSONArray().put(new JSONObject(text));
                for (int i = 0; i < configs.length(); i++) {
                    JSONArray obs = configs.getJSONObject(i).optJSONArray("outbounds");
                    if (obs == null) continue;
                    for (int j = 0; j < obs.length(); j++) {
                        JSONObject ob = obs.getJSONObject(j);
                        String p = ob.optString("protocol");
                        if (p.equals("vless") || p.equals("vmess") || p.equals("trojan") || p.equals("shadowsocks")) {
                            JSONObject copy = new JSONObject(ob.toString());
                            copy.remove("proxySettings"); // цепочки не поддерживаем
                            result.add(copy);
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "json subscription parse failed", e);
            }
            return result;
        }

        // 2) base64 со ссылками или просто ссылки построчно
        if (!text.contains("://")) {
            try {
                text = new String(Base64.decode(text, Base64.DEFAULT | Base64.URL_SAFE), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                try {
                    text = new String(Base64.decode(text, Base64.DEFAULT), StandardCharsets.UTF_8);
                } catch (Exception ignored2) {
                }
            }
        }
        for (String line : text.split("[\\r\\n\\s]+")) {
            line = line.trim();
            if (line.startsWith("vless://")) {
                try {
                    result.add(parseVless(line));
                } catch (Exception e) {
                    Log.w(TAG, "bad vless link", e);
                }
            }
        }
        return result;
    }

    static JSONObject parseVless(String link) throws Exception {
        Uri u = Uri.parse(link);
        String uuid = u.getUserInfo();
        String host = u.getHost();
        int port = u.getPort();
        if (TextUtils.isEmpty(uuid) || TextUtils.isEmpty(host) || port <= 0) {
            throw new IllegalArgumentException("invalid vless: " + link);
        }
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);

        String type = q(u, "type", "tcp");
        String security = q(u, "security", "none");
        String flow = q(u, "flow", "");

        JSONObject user = new JSONObject()
                .put("id", uuid)
                .put("encryption", q(u, "encryption", "none"));
        if (!flow.isEmpty()) user.put("flow", flow);

        JSONObject vnext = new JSONObject()
                .put("address", host)
                .put("port", port)
                .put("users", new JSONArray().put(user));

        JSONObject stream = new JSONObject().put("network", type).put("security", security);

        String sni = q(u, "sni", "");
        String fp = q(u, "fp", "");
        if (security.equals("reality")) {
            JSONObject r = new JSONObject()
                    .put("serverName", sni)
                    .put("publicKey", q(u, "pbk", ""))
                    .put("shortId", q(u, "sid", ""))
                    .put("fingerprint", fp.isEmpty() ? "chrome" : fp);
            String spx = q(u, "spx", "");
            if (!spx.isEmpty()) r.put("spiderX", spx);
            String pqv = q(u, "pqv", "");
            if (!pqv.isEmpty()) r.put("mldsa65Verify", pqv);
            stream.put("realitySettings", r);
        } else if (security.equals("tls")) {
            JSONObject t = new JSONObject();
            if (!sni.isEmpty()) t.put("serverName", sni);
            if (!fp.isEmpty()) t.put("fingerprint", fp);
            String alpn = q(u, "alpn", "");
            if (!alpn.isEmpty()) {
                JSONArray a = new JSONArray();
                for (String s : alpn.split(",")) a.put(s.trim());
                t.put("alpn", a);
            }
            if ("1".equals(q(u, "allowInsecure", "")) || "true".equals(q(u, "allowInsecure", ""))) {
                t.put("allowInsecure", true);
            }
            stream.put("tlsSettings", t);
        }

        String path = q(u, "path", "");
        String hostHeader = q(u, "host", "");
        switch (type) {
            case "ws": {
                JSONObject ws = new JSONObject().put("path", path.isEmpty() ? "/" : path);
                if (!hostHeader.isEmpty()) ws.put("host", hostHeader);
                stream.put("wsSettings", ws);
                break;
            }
            case "grpc": {
                JSONObject g = new JSONObject().put("serviceName", q(u, "serviceName", ""));
                if ("multi".equals(q(u, "mode", ""))) g.put("multiMode", true);
                String authority = q(u, "authority", "");
                if (!authority.isEmpty()) g.put("authority", authority);
                stream.put("grpcSettings", g);
                break;
            }
            case "xhttp":
            case "splithttp": {
                stream.put("network", "xhttp");
                JSONObject x = new JSONObject().put("path", path.isEmpty() ? "/" : path);
                if (!hostHeader.isEmpty()) x.put("host", hostHeader);
                String mode = q(u, "mode", "");
                if (!mode.isEmpty()) x.put("mode", mode);
                String extra = q(u, "extra", "");
                if (!extra.isEmpty()) {
                    try {
                        x.put("extra", new JSONObject(extra));
                    } catch (Exception ignored) {
                    }
                }
                stream.put("xhttpSettings", x);
                break;
            }
            case "httpupgrade": {
                JSONObject h = new JSONObject().put("path", path.isEmpty() ? "/" : path);
                if (!hostHeader.isEmpty()) h.put("host", hostHeader);
                stream.put("httpupgradeSettings", h);
                break;
            }
            case "tcp":
            default: {
                if ("http".equals(q(u, "headerType", ""))) {
                    JSONObject req = new JSONObject()
                            .put("path", new JSONArray().put(path.isEmpty() ? "/" : path));
                    if (!hostHeader.isEmpty()) {
                        req.put("headers", new JSONObject().put("Host", new JSONArray().put(hostHeader)));
                    }
                    stream.put("tcpSettings", new JSONObject().put("header",
                            new JSONObject().put("type", "http").put("request", req)));
                }
                break;
            }
        }

        return new JSONObject()
                .put("protocol", "vless")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(vnext)))
                .put("streamSettings", stream);
    }

    private static String q(Uri u, String key, String def) {
        String v = u.getQueryParameter(key);
        return v == null ? def : v;
    }

    static JSONObject buildConfig(List<JSONObject> nodes) throws Exception {
        JSONArray outbounds = new JSONArray();
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject ob = nodes.get(i);
            ob.put("tag", "node" + i);
            outbounds.put(ob);
        }
        outbounds.put(new JSONObject().put("tag", "direct").put("protocol", "freedom"));

        JSONObject inbound = new JSONObject()
                .put("tag", "socks-in")
                .put("listen", LOCAL_HOST)
                .put("port", LOCAL_PORT)
                .put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "noauth").put("udp", false));

        JSONObject routing = new JSONObject().put("domainStrategy", "AsIs");
        JSONObject rule = new JSONObject().put("type", "field").put("inboundTag", new JSONArray().put("socks-in"));
        JSONObject cfg = new JSONObject()
                .put("log", new JSONObject().put("loglevel", "warning"))
                .put("inbounds", new JSONArray().put(inbound))
                .put("outbounds", outbounds);

        if (nodes.size() == 1) {
            rule.put("outboundTag", "node0");
        } else {
            // авто-выбор самого быстрого сервера
            cfg.put("observatory", new JSONObject()
                    .put("subjectSelector", new JSONArray().put("node"))
                    .put("probeURL", "https://www.gstatic.com/generate_204")
                    .put("probeInterval", "2m")
                    .put("enableConcurrency", true));
            routing.put("balancers", new JSONArray().put(new JSONObject()
                    .put("tag", "auto")
                    .put("selector", new JSONArray().put("node"))
                    .put("fallbackTag", "node0")
                    .put("strategy", new JSONObject().put("type", "leastPing"))));
            rule.put("balancerTag", "auto");
        }
        routing.put("rules", new JSONArray().put(rule));
        cfg.put("routing", routing);
        return cfg;
    }

    // ---------- простое окно настройки ----------

    public static void showDialog(Activity activity) {
        if (activity == null) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(20);
        box.setPadding(pad, AndroidUtilities.dp(8), pad, 0);

        TextView status = new TextView(activity);
        status.setText(isRunning()
                ? "Статус: работает (серверов " + prefs().getInt(K_NODES, 0) + ")"
                : (isEnabled() ? "Статус: включено, но ядро не запущено" : "Статус: выключено"));
        box.addView(status);

        EditText input = new EditText(activity);
        input.setHint("https://… подписка или vless://…");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        input.setMaxLines(4);
        input.setText(prefs().getString(K_SOURCE, ""));
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(activity)
                .setTitle("Xray (VLESS / подписка)")
                .setView(box)
                .setPositiveButton("Включить", (d, w) -> {
                    String src = input.getText().toString();
                    Toast.makeText(activity, "Запускаю Xray…", Toast.LENGTH_SHORT).show();
                    enable(src, (ok, msg) -> Toast.makeText(activity, msg, Toast.LENGTH_LONG).show());
                })
                .setNegativeButton("Отмена", null);
        if (isEnabled()) {
            b.setNeutralButton("Выключить", (d, w) -> {
                disable();
                Toast.makeText(activity, "Xray выключен", Toast.LENGTH_SHORT).show();
            });
        }
        b.show();
    }
}
