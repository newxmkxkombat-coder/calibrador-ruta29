package co.ruta29.calibrador;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;

/**
 * EL MOTOR. Corre "en primer plano" (con una notificación fija), por eso Android
 * lo deja trabajar con la pantalla apagada. Hace tres cosas:
 *  1. Escucha el GPS.
 *  2. Marca solo cada punto de control cuando pasas.
 *  3. Te habla diciendo cómo vas de tiempo.
 */
public class TrackerService extends Service implements LocationListener {

    static final String INICIAR = "iniciar", DETENER = "detener", MARCAR = "marcar",
            ANTERIOR = "anterior", VOZ_ON = "voz_on", VOZ_OFF = "voz_off";
    static final String PREFS = "motor";
    static final String CANAL = "ruta_bloqueo"; // canal nuevo: se ve en la pantalla de bloqueo
    static final int ID_NOTIF = 29;

    // Lo que la pantalla lee (con "estado()")
    static volatile boolean activo = false;
    private static final Object candado = new Object();
    private static Double lat, lon, vel, precision;
    private static int idx = 0;
    private static JSONObject marcas = new JSONObject();
    private static String gps = "buscando", gpsTxt = "Buscando señal de GPS…";

    // Datos de la ruta de hoy
    private JSONArray puntos;
    private String fecha;
    private double adelantoMeta = 2.5, radioMarca = 70, radioSalida = 120, factorCalles = 1.3;

    private LocationManager lm;
    private TextToSpeech tts;
    private boolean ttsListo = false, voz = false;
    private PowerManager.WakeLock wake;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long ultimaVoz = 0;
    private String ultimoEstado = "";
    private boolean avisoUnMinuto = false, avisoSalga = false;
    private double cercaD = -1; private long cercaT = 0;

    // Recorrido del día (por dónde ha pasado), para dibujarlo en el mapa
    private static final java.util.ArrayList<double[]> traza = new java.util.ArrayList<>();
    private int trazaSinGuardar = 0;
    // Ruta guardada: puntos en metros (x, y) y metros acumulados
    private double[] rx, ry, rcum;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String accion = intent != null && intent.getAction() != null ? intent.getAction() : INICIAR;
        switch (accion) {
            case INICIAR: iniciar(intent); break;
            case DETENER: detener(); break;
            case MARCAR: if (activo) marcar(System.currentTimeMillis()); break;
            case ANTERIOR: if (activo) anterior(); break;
            case VOZ_ON: voz = true; hablar("Voz activada.", true); break;
            case VOZ_OFF: voz = false; if (tts != null) tts.stop(); break;
        }
        return START_STICKY; // si Android lo cierra, que lo vuelva a abrir
    }

    // ================= ARRANCAR Y PARAR =================
    @SuppressLint("MissingPermission")
    private void iniciar(Intent intent) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String plan = intent != null ? intent.getStringExtra("plan") : null;
        String marcasTxt = intent != null ? intent.getStringExtra("marcas") : null;
        if (plan != null) {
            voz = intent.getBooleanExtra("voz", p.getBoolean("voz", false));
            p.edit().putString("plan", plan).putString("marcas", marcasTxt == null ? "{}" : marcasTxt)
                    .putBoolean("voz", voz).apply();
        } else {
            // Android reinició el motor solo: recuperar lo guardado
            plan = p.getString("plan", null);
            marcasTxt = p.getString("marcas", "{}");
            voz = p.getBoolean("voz", false);
        }
        try {
            JSONObject o = new JSONObject(plan);
            puntos = o.getJSONArray("puntos");
            fecha = o.optString("fecha");
            adelantoMeta = o.optDouble("adelantoMeta", 2.5);
            radioMarca = o.optDouble("radioMarca", 70);
            radioSalida = o.optDouble("radioSalida", 120);
            factorCalles = o.optDouble("factorCalles", 1.3);
            synchronized (candado) {
                marcas = new JSONObject(marcasTxt == null ? "{}" : marcasTxt);
                idx = primerPendiente();
            }
            prepararRuta(o.optJSONArray("ruta"));
            cargarTraza();
        } catch (Exception e) {
            stopSelf();
            return;
        }

        crearCanal();
        Notification n = notificacion("Calibrador activo", "Buscando señal de GPS…");
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID_NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(ID_NOTIF, n);
        } catch (Exception e) {
            // Android no dejó arrancar en segundo plano (pasa si lo reinicia solo): se cierra sin fallar
            activo = false;
            stopSelf();
            return;
        }
        activo = true;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (wake == null && pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ruta29:motor");
            wake.acquire(16 * 60 * 60 * 1000L); // máximo 16 horas
        }
        if (tts == null) {
            tts = new TextToSpeech(this, st -> {
                if (st == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(new Locale("es", "CO"));
                    if (r < 0) tts.setLanguage(new Locale("es"));
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    ttsListo = true;
                    hablar("Calibrador activo.", true);
                }
            });
        }
        if (lm == null) {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper()); // cada segundo
            } catch (Exception e) {
                synchronized (candado) { gps = "err"; gpsTxt = "No se pudo usar el GPS"; }
            }
        }
        h.removeCallbacks(tic);
        h.post(tic);
    }

    private void detener() {
        activo = false;
        guardarTraza();
        h.removeCallbacks(tic);
        if (lm != null) { lm.removeUpdates(this); lm = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; ttsListo = false; }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        synchronized (candado) { lat = null; lon = null; vel = null; gps = "buscando"; gpsTxt = "GPS apagado"; }
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { if (activo) detener(); super.onDestroy(); }

    // ================= GPS =================
    @Override
    public void onLocationChanged(Location l) {
        synchronized (candado) {
            lat = l.getLatitude(); lon = l.getLongitude();
            vel = l.hasSpeed() ? (double) l.getSpeed() : null;
            precision = (double) l.getAccuracy();
            gps = "live";
            gpsTxt = "GPS activo · precisión " + Math.round(l.getAccuracy()) + " m · funciona con pantalla apagada";
            double[] u = traza.isEmpty() ? null : traza.get(traza.size() - 1);
            if (l.getAccuracy() < 50 && (u == null || distancia(u[0], u[1], l.getLatitude(), l.getLongitude()) >= 15)) {
                traza.add(new double[]{l.getLatitude(), l.getLongitude(), System.currentTimeMillis()});
                if (++trazaSinGuardar >= 10) { guardarTraza(); trazaSinGuardar = 0; }
            }
        }
        revisarLlegada();
        actualizar(false);
    }
    @Override public void onProviderDisabled(String p) { synchronized (candado) { gps = "err"; gpsTxt = "El GPS del celular está apagado"; } }
    @Override public void onProviderEnabled(String p) { synchronized (candado) { gps = "buscando"; gpsTxt = "Buscando señal de GPS…"; } }
    @Override public void onStatusChanged(String p, int s, Bundle b) { }

    // Cada 5 segundos: revisar el día, actualizar notificación y voz
    private final Runnable tic = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            String hoy = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (fecha != null && !fecha.isEmpty() && !fecha.equals(hoy)) { detener(); return; }
            actualizar(false);
            h.postDelayed(this, 5000);
        }
    };

    // ================= MARCAR PUNTOS =================
    private JSONObject punto(int i) { return puntos.optJSONObject(i); }

    private int primerPendiente() {
        long ahora = System.currentTimeMillis();
        for (int i = 0; i < puntos.length(); i++) {
            JSONObject p = punto(i);
            if (!marcas.has(String.valueOf(p.optInt("i"))) && p.optLong("t") > ahora - 25 * 60000L) return i;
        }
        return puntos.length() - 1;
    }

    private void revisarLlegada() {
        Double la, lo; int i;
        synchronized (candado) { la = lat; lo = lon; i = idx; }
        if (la == null || puntos == null || i >= puntos.length()) return;
        long ahora = System.currentTimeMillis();
        // Revisa el punto que toca y los 3 siguientes: el primero que encuentre cerca, ese marca
        for (int j = i; j <= Math.min(i + 3, puntos.length() - 1); j++) {
            JSONObject p = punto(j);
            if (marcas.has(String.valueOf(p.optInt("i")))) continue;
            if (j > i && p.optLong("t") - ahora > 45 * 60000L) break; // no saltar a puntos de mucho más tarde
            double d = distancia(la, lo, p.optDouble("lat"), p.optDouble("lon"));
            if (p.optBoolean("salida")) {
                // arrancó: ya se alejó del punto de salida (y ya casi es la hora)
                if (j == i && d > radioSalida && ahora > p.optLong("t") - 15 * 60000L) { marcarEn(j, ahora); return; }
                continue;
            }
            if (d <= radioMarca) { marcarEn(j, ahora); return; }
            if (j == i && d <= 300) { // plan B: pasó cerca y se está alejando
                if (cercaD < 0 || d < cercaD) { cercaD = d; cercaT = ahora; }
                else if (d > cercaD + 60) { marcarEn(j, cercaT); return; }
            }
        }
    }

    private void marcarEn(int j, long t) {
        synchronized (candado) { idx = j; }
        marcar(t);
    }

    private void marcar(long t) {
        JSONObject p;
        synchronized (candado) {
            p = punto(idx);
            try { marcas.put(String.valueOf(p.optInt("i")), t); } catch (Exception ignorado) { }
            if (idx < puntos.length() - 1) idx++;
        }
        cercaD = -1;
        guardarMarcas();
        double dif = (p.optLong("t") - t) / 60000.0;
        String consejo = "";
        JSONObject sig; synchronized (candado) { sig = idx < puntos.length() ? punto(idx) : null; }
        if (sig != null && sig != p && sig.has("tip") && sig.optInt("vuelta") == p.optInt("vuelta")) {
            double plan = (sig.optLong("t") - p.optLong("t")) / 60000.0, tip = sig.optDouble("tip");
            if (tip > plan + 1) consejo = " Ojo: hasta " + sig.optString("nombre") + " sueles demorarte "
                    + Math.round(tip) + " minutos y la planilla da " + Math.round(plan) + ". Sal con ritmo.";
        }
        hablar((p.optBoolean("salida") ? "Arrancaste de " : "Marcaste ") + p.optString("nombre")
                + " con " + textoMin(dif) + "." + consejo, true);
        ultimoEstado = ""; avisoUnMinuto = false; avisoSalga = false;
        actualizar(true);
    }

    private void anterior() {
        synchronized (candado) {
            if (idx > 0) {
                idx--;
                marcas.remove(String.valueOf(punto(idx).optInt("i")));
            }
        }
        cercaD = -1;
        guardarMarcas();
        actualizar(true);
    }

    private void guardarMarcas() {
        String m;
        synchronized (candado) { m = marcas.toString(); }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("marcas", m).apply();
    }

    // ================= ¿VOY BIEN O MAL? =================
    private void actualizar(boolean forzarNotif) {
        if (puntos == null) return;
        long ahora = System.currentTimeMillis();
        JSONObject p, a; Double la, lo; int i;
        synchronized (candado) { i = idx; la = lat; lo = lon; }
        p = punto(i); a = i > 0 ? punto(i - 1) : null;
        String nombre = p.optString("nombre");
        String hora = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(p.optLong("t")));
        double minRest = (p.optLong("t") - ahora) / 60000.0;

        if (marcas.has(String.valueOf(p.optInt("i")))) {
            notificar("Día terminado", "Mira el resumen en la app");
            return;
        }
        if (p.optBoolean("salida")) {
            String txt = minRest > 0 ? "Sales de " + nombre + " a las " + hora + " · faltan " + (int) Math.ceil(minRest) + " min"
                    : "¡Hora de arrancar de " + nombre + "!";
            notificar("Arranque", txt);
            if (minRest <= 1 && minRest > 0 && !avisoUnMinuto) { avisoUnMinuto = true; hablar("Prepárate, en un minuto arrancas.", true); }
            if (minRest <= 0 && !avisoSalga) { avisoSalga = true; hablar("Ya es hora de arrancar.", true); }
            return;
        }
        if (la == null || a == null) {
            notificar(nombre + " " + hora, "Esperando señal de GPS…");
            return;
        }
        double dist = distancia(la, lo, p.optDouble("lat"), p.optDouble("lon")) * factorCalles;
        double tramo = Math.max(1, distancia(a.optDouble("lat"), a.optDouble("lon"), p.optDouble("lat"), p.optDouble("lon")) * factorCalles);
        double real = -1;
        // Si hay ruta guardada, medir por las calles de esa ruta
        if (rcum != null && p.has("s") && p.has("sAnt")) {
            double sP = p.optDouble("s"), sA = p.optDouble("sAnt");
            double[] q = proyectar(la, lo, sA - 80, sP + 80);
            if (q != null && q[0] < 250 && sP > sA) {
                dist = Math.max(0, sP - q[1]);
                tramo = sP - sA;
                real = Math.min(1, Math.max(0, (q[1] - sA) / tramo));
            }
        }
        double minTramo = Math.max(1, (p.optLong("t") - a.optLong("t")) / 60000.0);
        double debido = Math.min(1, Math.max(0, (ahora - a.optLong("t")) / (minTramo * 60000)));
        if (real < 0) real = Math.min(1, Math.max(0, 1 - dist / tramo));
        double delta = (real - debido) * minTramo;
        if (minRest < 0) delta = Math.min(delta, minRest);

        // PRONÓSTICO: ¿a qué hora llegarías si sigues así? (tiempos aprendidos o ritmo del último minuto)
        double restante = -1, margen = Double.NaN;
        if (p.has("tip")) restante = p.optDouble("tip") * (1 - real);
        else { double v = velocidadReciente(); if (v > 1.5) restante = dist / v / 60.0; }
        if (restante >= 0) margen = (p.optLong("t") - (ahora + restante * 60000)) / 60000.0;

        String cls, msg;
        if (delta < 0) { cls = "bad"; msg = "Vas atrasado, dale un poquito más."; }
        else if (delta < 1.5) { cls = "warn"; msg = "Vas justo, mantén el ritmo."; }
        else if (delta <= 4) { cls = "ok"; msg = "Vas bien de tiempo."; }
        else { cls = "warn"; msg = "Vas muy adelantado, puedes bajar el ritmo."; }
        // AVISO ANTES DE COLGARTE: aunque ahora vayas bien, si el pronóstico dice tarde, avisa ya
        boolean riesgo = false;
        if (!Double.isNaN(margen) && margen < 0 && minRest > 0) {
            cls = "bad"; riesgo = true;
            msg = "¡Ojo! A este ritmo llegas a " + nombre + " con " + textoMin(margen) + ". Apura.";
        } else if (!Double.isNaN(margen) && margen < 1 && cls.equals("ok")) {
            cls = "warn"; msg = "Vas muy justo para " + nombre + ", no te descuides.";
        }

        String signo = delta >= 0 ? "+" : "−";
        notificar(signo + String.format(Locale.US, "%.1f", Math.abs(delta)) + " min · " + nombre + " " + hora,
                msg + " Faltan " + (dist >= 1000 ? String.format(Locale.US, "%.1f km", dist / 1000) : Math.round(dist) + " m"));

        String estadoVoz = cls + (riesgo ? "-riesgo" : "");
        if (!estadoVoz.equals(ultimoEstado)) {
            hablar(riesgo ? msg : msg + " " + textoMin(delta) + " para " + nombre + ".", cls.equals("bad"));
            ultimoEstado = estadoVoz;
        } else if (riesgo && ahora - ultimaVoz > 90000) { // en riesgo, repetir cada minuto y medio
            hablar(msg, true);
        } else if (!riesgo && ahora - ultimaVoz > 120000) { // un aviso cada 2 minutos
            hablar(textoMin(delta) + " para " + nombre + ".", false);
        }
    }

    // ================= VOZ =================
    private void hablar(String txt, boolean forzar) {
        if (!voz || !ttsListo || tts == null) return;
        long ahora = System.currentTimeMillis();
        if (!forzar && ahora - ultimaVoz < 60000) return;
        ultimaVoz = ahora;
        tts.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "r29");
    }

    private static String textoMin(double m) {
        double a = Math.abs(m);
        String n = a < 1 ? Math.round(a * 60) + " segundos"
                : (Math.abs(a - Math.round(a)) < 0.05 ? String.valueOf(Math.round(a)) : String.format(new Locale("es"), "%.1f", a)) + " minutos";
        return n + (m >= 0 ? " de adelanto" : " de retraso");
    }

    // ================= NOTIFICACIÓN FIJA =================
    private void crearCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.deleteNotificationChannel("ruta"); // el canal viejo era "silencioso" y Android lo escondía al bloquear
            // Importancia normal = se muestra en la pantalla de bloqueo; sin sonido ni vibración
            NotificationChannel c = new NotificationChannel(CANAL, "Calibrador en ruta", NotificationManager.IMPORTANCE_DEFAULT);
            c.setSound(null, null);
            c.enableVibration(false);
            c.enableLights(false);
            c.setDescription("Muestra cómo vas de tiempo mientras el GPS está activo");
            c.setShowBadge(false);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); // se ve con el celular bloqueado
            nm.createNotificationChannel(c);
        }
    }

    private String ultimaNotif = "";
    private void notificar(String titulo, String texto) {
        String clave = titulo + "|" + texto;
        if (clave.equals(ultimaNotif)) return;
        ultimaNotif = clave;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_NOTIF, notificacion(titulo, texto));
    }

    private Notification notificacion(String titulo, String texto) {
        Intent abrir = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pp = PendingIntent.getService(this, 1, new Intent(this, TrackerService.class).setAction(DETENER), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pm = PendingIntent.getService(this, 2, new Intent(this, TrackerService.class).setAction(MARCAR), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pa = PendingIntent.getService(this, 3, new Intent(this, TrackerService.class).setAction(ANTERIOR), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CANAL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_bus)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(new Notification.BigTextStyle().bigText(texto))
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)   // mostrar todo en la pantalla de bloqueo
                .setCategory(Notification.CATEGORY_NAVIGATION)
                .setShowWhen(false)
                .setColor(0xFFE3242B).setColorized(true)  // roja, para que resalte
                .addAction(new Notification.Action.Builder(null, "✓ Ya marqué", pm).build())
                .addAction(new Notification.Action.Builder(null, "↩ Devolver", pa).build())
                .addAction(new Notification.Action.Builder(null, "Detener", pp).build());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    // ================= ESTADO PARA LA PANTALLA =================
    static String estadoJson() {
        try {
            JSONObject o = new JSONObject();
            synchronized (candado) {
                o.put("activo", activo);
                if (lat != null) { o.put("lat", lat); o.put("lon", lon); }
                if (vel != null) o.put("vel", vel);
                if (precision != null) o.put("acc", precision);
                o.put("idx", idx);
                JSONObject m = new JSONObject();
                Iterator<String> it = marcas.keys();
                while (it.hasNext()) { String k = it.next(); m.put(k, marcas.get(k)); }
                o.put("marcas", m);
                o.put("gps", gps);
                o.put("nTraza", traza.size());
                o.put("gpsTxt", gpsTxt);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"activo\":false}";
        }
    }

    // Velocidad promedio del último minuto y medio (más estable que la del instante), en m/s
    private double velocidadReciente() {
        synchronized (candado) {
            int n = traza.size();
            if (n < 2) return -1;
            long t = System.currentTimeMillis();
            double d = 0; int i = n - 1;
            while (i > 0 && t - (long) traza.get(i - 1)[2] <= 90000) {
                double[] a = traza.get(i), b = traza.get(i - 1);
                d += distancia(a[0], a[1], b[0], b[1]); i--;
            }
            double seg = (traza.get(n - 1)[2] - traza.get(i)[2]) / 1000.0;
            return seg >= 20 ? d / seg : -1;
        }
    }

    // ================= RECORRIDO DEL DÍA =================
    static String trazaJson(int desde) {
        StringBuilder b = new StringBuilder("[");
        synchronized (candado) {
            for (int i = Math.max(0, desde); i < traza.size(); i++) {
                double[] q = traza.get(i);
                if (b.length() > 1) b.append(',');
                b.append('[').append(String.format(Locale.US, "%.6f,%.6f,%d", q[0], q[1], (long) q[2])).append(']');
            }
        }
        return b.append(']').toString();
    }

    private void guardarTraza() {
        String hoy = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("traza", trazaJson(0)).putString("trazaFecha", hoy).apply();
    }

    private void cargarTraza() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String hoy = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        synchronized (candado) {
            traza.clear();
            if (!hoy.equals(p.getString("trazaFecha", ""))) return; // día nuevo: recorrido limpio
            try {
                JSONArray a = new JSONArray(p.getString("traza", "[]"));
                for (int i = 0; i < a.length(); i++) {
                    JSONArray q = a.getJSONArray(i);
                    traza.add(new double[]{q.getDouble(0), q.getDouble(1), q.getDouble(2)});
                }
            } catch (Exception ignorado) { }
        }
    }

    // ================= RUTA GUARDADA =================
    // Convierte grados a metros en un plano pequeño (sirve para una ciudad)
    private static double mx(double lon) { return (lon + 75.28) * Math.cos(Math.toRadians(2.94)) * 111320; }
    private static double my(double lat) { return (lat - 2.94) * 110574; }

    private void prepararRuta(JSONArray r) {
        rx = ry = rcum = null;
        if (r == null || r.length() < 20) return;
        int n = r.length();
        rx = new double[n]; ry = new double[n]; rcum = new double[n];
        for (int i = 0; i < n; i++) {
            JSONArray q = r.optJSONArray(i);
            rx[i] = mx(q.optDouble(1)); ry[i] = my(q.optDouble(0));
            rcum[i] = i == 0 ? 0 : rcum[i - 1] + Math.hypot(rx[i] - rx[i - 1], ry[i] - ry[i - 1]);
        }
    }

    // ¿A cuántos metros de la ruta estoy y en qué metro de la ruta voy? Devuelve {distancia, metro}
    private double[] proyectar(double lat, double lon, double sMin, double sMax) {
        double px = mx(lon), py = my(lat);
        double[] mejor = null;
        for (int i = 1; i < rx.length; i++) {
            if (rcum[i] < sMin || rcum[i - 1] > sMax) continue;
            double dx = rx[i] - rx[i - 1], dy = ry[i] - ry[i - 1], L2 = dx * dx + dy * dy;
            double f = L2 > 0 ? Math.max(0, Math.min(1, ((px - rx[i - 1]) * dx + (py - ry[i - 1]) * dy) / L2)) : 0;
            double d = Math.hypot(px - (rx[i - 1] + f * dx), py - (ry[i - 1] + f * dy));
            if (mejor == null || d < mejor[0]) mejor = new double[]{d, rcum[i - 1] + f * Math.sqrt(L2)};
        }
        return mejor;
    }

    // Fórmula de Haversine: metros entre dos puntos GPS
    static double distancia(double la1, double lo1, double la2, double lo2) {
        double R = 6371000, dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double x = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * R * Math.asin(Math.sqrt(x));
    }
}
