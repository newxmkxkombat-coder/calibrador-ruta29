package co.ruta29.calibrador;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * La pantalla de la app. Muestra la página de GitHub (así se actualiza sola)
 * y le da a la página un "puente" (Android) para controlar el motor de GPS.
 */
public class MainActivity extends Activity {

    // Dirección de la página: cuando la cambiamos en GitHub, la app la ve nueva
    static final String BASE = "https://newxmkxkombat-coder.github.io/calibrador-ruta29/";
    static final int PEDIR_PERMISOS = 7;

    private WebView web;
    private String planPendiente, marcasPendientes;
    private boolean vozPendiente;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#111413"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // para guardar el registro del día
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Puente(), "Android");
        web.setWebViewClient(new Cliente());
        web.loadUrl(BASE);

        pedirPermisos(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    // ---------- Permisos ----------
    private boolean tieneGps() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void pedirPermisos(boolean soloGps) {
        java.util.ArrayList<String> faltan = new java.util.ArrayList<>();
        if (!tieneGps()) {
            faltan.add(Manifest.permission.ACCESS_FINE_LOCATION);
            faltan.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!soloGps && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            faltan.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!faltan.isEmpty()) requestPermissions(faltan.toArray(new String[0]), PEDIR_PERMISOS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        // Si la persona tocó "Iniciar GPS" antes de dar el permiso, arrancamos ahora
        if (code == PEDIR_PERMISOS && planPendiente != null && tieneGps()) {
            arrancarMotor(planPendiente, marcasPendientes, vozPendiente);
            planPendiente = null;
        }
    }

    private void arrancarMotor(String plan, String marcas, boolean voz) {
        Intent i = new Intent(this, TrackerService.class)
                .setAction(TrackerService.INICIAR)
                .putExtra("plan", plan)
                .putExtra("marcas", marcas)
                .putExtra("voz", voz);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void enviarAlMotor(String accion) {
        startService(new Intent(this, TrackerService.class).setAction(accion));
    }

    // ---------- El puente que usa la página ----------
    class Puente {
        @JavascriptInterface public void iniciar(String plan, String marcas, boolean voz) {
            if (!tieneGps()) {
                planPendiente = plan; marcasPendientes = marcas; vozPendiente = voz;
                runOnUiThread(() -> pedirPermisos(false));
                return;
            }
            arrancarMotor(plan, marcas, voz);
        }
        @JavascriptInterface public void detener() { enviarAlMotor(TrackerService.DETENER); }
        @JavascriptInterface public boolean activo() { return TrackerService.activo; }
        @JavascriptInterface public String estado() { return TrackerService.estadoJson(); }
        @JavascriptInterface public String traza(int desde) { return TrackerService.trazaJson(desde); }
        // Mientras el GPS está prendido, la app se ve encima de la pantalla de bloqueo
        // (como Google Maps al navegar). Para salir a otras apps igual pide la clave.
        @JavascriptInterface public void sobreBloqueo(boolean on) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(on);
                else if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
            });
        }
        @JavascriptInterface public void pantallaEncendida(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
        @JavascriptInterface public void compartir(String texto) {
            // Abre el menú de compartir de Android (WhatsApp, etc.)
            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto);
            Intent w = new Intent(i).setPackage("com.whatsapp");
            try { startActivity(w); } catch (Exception e) { startActivity(Intent.createChooser(i, "Compartir resumen")); }
        }
        @JavascriptInterface public void marcar() { enviarAlMotor(TrackerService.MARCAR); }
        @JavascriptInterface public void anterior() { enviarAlMotor(TrackerService.ANTERIOR); }
        @JavascriptInterface public void voz(boolean on) {
            SharedPreferences p = getSharedPreferences(TrackerService.PREFS, MODE_PRIVATE);
            p.edit().putBoolean("voz", on).apply();
            if (TrackerService.activo) enviarAlMotor(on ? TrackerService.VOZ_ON : TrackerService.VOZ_OFF);
        }
        @JavascriptInterface public int version() { return BuildConfig.VERSION_CODE; }
        @JavascriptInterface public void abrirEnlace(String url) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
        // ¿La ubicación está en "Permitir todo el tiempo"?
        @JavascriptInterface public boolean ubicacionSiempre() {
            if (Build.VERSION.SDK_INT < 29) return true;
            return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        // Pedirla: en Android 11+ abre la pantalla de permisos donde se escoge "Permitir todo el tiempo"
        @JavascriptInterface public void pedirUbicacionSiempre() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < 29) return;
                if (!tieneGps()) { pedirPermisos(true); return; }
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, PEDIR_PERMISOS + 1);
            });
        }
        // ¿El celular deja trabajar la app sin restricciones de batería?
        @JavascriptInterface public boolean bateriaLibre() {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        @SuppressLint("BatteryLife")
        // Abrir la página de la app en Ajustes (ahí está "Uso de batería" / "Actividad en segundo plano")
        @JavascriptInterface public void abrirAjustesApp() {
            // Honor / Huawei: ir directo a "Inicio de aplicaciones". Si no existe, la página normal de la app.
            String[][] pantallas = {
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.optimize.process.ProtectActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            };
            String m = Build.MANUFACTURER.toLowerCase(java.util.Locale.US);
            if (m.contains("honor") || m.contains("huawei")) {
                for (String[] p : pantallas) {
                    try {
                        startActivity(new Intent().setComponent(new android.content.ComponentName(p[0], p[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    } catch (Exception ignorado) { }
                }
            }
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        }
        @JavascriptInterface public String marca() { return Build.MANUFACTURER + " " + Build.MODEL; }
        @JavascriptInterface public void pedirBateria() {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            }
        }
    }

    // ---------- Cargar la página (con copia guardada para cuando no hay señal) ----------
    class Cliente extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            String url = r.getUrl().toString();
            if (url.startsWith(BASE) && !url.endsWith(".apk")) return false;
            startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); // otros links se abren afuera
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            String url = r.getUrl().toString();
            if (!"GET".equals(r.getMethod()) || !url.startsWith(BASE) || url.endsWith(".apk")) return null;
            String nombre = url.substring(BASE.length());
            int q = nombre.indexOf('?');
            if (q >= 0) nombre = nombre.substring(0, q);
            if (nombre.isEmpty()) nombre = "index.html";
            if (nombre.contains("/") || nombre.contains("..")) return null;
            File guardado = new File(getFilesDir(), "web_" + nombre);
            String tipo = tipoDe(nombre);
            // 1) Intentar internet y guardar copia
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + nombre).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                if (c.getResponseCode() == 200) {
                    byte[] datos = leerTodo(c.getInputStream());
                    try (FileOutputStream f = new FileOutputStream(guardado)) { f.write(datos); }
                    return respuesta(tipo, new ByteArrayInputStream(datos));
                }
            } catch (Exception ignorado) { }
            // 2) Sin señal: usar la última copia guardada
            try {
                if (guardado.exists()) return respuesta(tipo, new FileInputStream(guardado));
            } catch (Exception ignorado) { }
            // 3) Primera vez y sin señal: la copia que viene dentro de la app
            try {
                return respuesta(tipo, getAssets().open(nombre));
            } catch (Exception ignorado) { }
            return null;
        }
    }

    private static WebResourceResponse respuesta(String tipo, InputStream in) {
        WebResourceResponse r = new WebResourceResponse(tipo, "utf-8", in);
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        r.setResponseHeaders(h);
        return r;
    }

    private static String tipoDe(String n) {
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".json") || n.endsWith(".webmanifest")) return "application/json";
        return "application/octet-stream";
    }

    private static byte[] leerTodo(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toByteArray();
    }
}
