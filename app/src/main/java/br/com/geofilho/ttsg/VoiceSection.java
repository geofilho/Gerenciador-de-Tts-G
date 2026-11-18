package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bloco de controles de voz usado nas duas telas: motor, idioma (só na
 * principal), voz, ouvir, velocidade, tonalidade e volume.
 * Cada escolha é um botão que abre uma lista simples: fácil com leitor de tela.
 */
final class VoiceSection implements TextToSpeech.OnInitListener {

    interface TextProvider {
        String text();
    }

    private final Activity act;
    private final String who;
    private final boolean withLanguage;
    private final boolean allowNone;
    private final SharedPreferences prefs;
    private final TextProvider textProvider;

    private final Button btnEngine;
    private final Button btnLanguage;
    private final Button btnVoice;
    private final Button btnTest;

    private final List<String> enginePkgs = new ArrayList<>();
    private final List<String> engineNames = new ArrayList<>();
    private final List<Locale> languages = new ArrayList<>();
    private final List<Voice> voices = new ArrayList<>();
    private List<Voice> allVoices = new ArrayList<>();

    private TextToSpeech tts;
    private boolean ready;

    VoiceSection(Activity act, String who, boolean withLanguage, boolean allowNone, TextProvider textProvider) {
        this.act = act;
        this.who = who;
        this.withLanguage = withLanguage;
        this.allowNone = allowNone;
        this.textProvider = textProvider;
        this.prefs = Prefs.get(act);

        btnEngine = act.findViewById(R.id.btn_engine);
        btnLanguage = act.findViewById(R.id.btn_language);
        btnVoice = act.findViewById(R.id.btn_voice);
        btnTest = act.findViewById(R.id.btn_test);
        if (!withLanguage) {
            btnLanguage.setVisibility(View.GONE);
        }

        btnEngine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseEngine();
            }
        });
        btnLanguage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseLanguage();
            }
        });
        btnVoice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseVoice();
            }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                test();
            }
        });

        setupSlider(R.id.seek_rate, R.id.lbl_rate, "Velocidade", "_rate", Prefs.rate(prefs, who));
        setupSlider(R.id.seek_pitch, R.id.lbl_pitch, "Tonalidade", "_pitch", Prefs.pitch(prefs, who));
        setupSlider(R.id.seek_volume, R.id.lbl_volume, "Volume", "_volume", Prefs.volume(prefs, who));

        loadEngines();
    }

    void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    /** Chamado quando o idioma principal muda em outra tela. */
    void refreshVoices() {
        if (ready) {
            fillVoices();
        }
    }

    // ----- motores -----

    private void loadEngines() {
        enginePkgs.clear();
        engineNames.clear();
        if (allowNone) {
            enginePkgs.add("");
            engineNames.add("Nenhum (usar a voz principal)");
        }
        PackageManager pm = act.getPackageManager();
        for (ResolveInfo r : EngineClient.installedEngines(act)) {
            enginePkgs.add(r.serviceInfo.packageName);
            engineNames.add(r.loadLabel(pm).toString());
        }
        String pkg = Prefs.engine(prefs, who);
        if (!enginePkgs.contains(pkg)) {
            pkg = allowNone ? "" : EngineClient.firstEngine(act);
        }
        selectEngine(pkg);
    }

    private void chooseEngine() {
        if (enginePkgs.isEmpty()) {
            say("Nenhum motor de TTS instalado neste aparelho.");
            return;
        }
        int checked = enginePkgs.indexOf(Prefs.engine(prefs, who));
        list("Motor de TTS", engineNames.toArray(new String[0]), checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
                selectEngine(enginePkgs.get(which));
            }
        });
    }

    private void selectEngine(String pkg) {
        prefs.edit().putString(who + "_engine", pkg).apply();
        int idx = enginePkgs.indexOf(pkg);
        String name = idx >= 0 ? engineNames.get(idx) : "nenhum instalado";
        btnEngine.setText((allowNone ? "Motor para emojis: " : "Motor de TTS: ") + name);
        shutdown();
        ready = false;
        languages.clear();
        voices.clear();
        allVoices = new ArrayList<>();
        boolean enabled = !pkg.isEmpty();
        setVoiceControlsEnabled(enabled);
        if (!enabled) {
            btnLanguage.setText("Idioma: nenhum");
            btnVoice.setText(allowNone ? "Voz para emojis: a mesma da voz principal" : "Voz: nenhuma");
            return;
        }
        btnLanguage.setText("Idioma: carregando");
        btnVoice.setText("Voz: carregando");
        tts = new TextToSpeech(act, this, pkg);
    }

    @Override
    public void onInit(int status) {
        if (tts == null) {
            return;
        }
        if (status != TextToSpeech.SUCCESS) {
            btnLanguage.setText("Idioma: o motor não respondeu");
            btnVoice.setText("Voz: o motor não respondeu");
            return;
        }
        ready = true;
        allVoices = readVoices();
        fillLanguages();
        fillVoices();
    }

    private List<Voice> readVoices() {
        List<Voice> out = new ArrayList<>();
        try {
            Set<Voice> set = tts.getVoices();
            if (set != null) {
                for (Voice v : set) {
                    Set<String> f = v.getFeatures();
                    if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                        continue;
                    }
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, new Comparator<Voice>() {
            @Override
            public int compare(Voice a, Voice b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return out;
    }

    // ----- idiomas -----

    private void fillLanguages() {
        TreeMap<String, Locale> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Voice v : allVoices) {
            Locale l = v.getLocale();
            byName.put(l.getDisplayName(), l);
        }
        if (byName.isEmpty()) {
            try {
                Set<Locale> langs = tts.getAvailableLanguages();
                if (langs != null) {
                    for (Locale l : langs) {
                        byName.put(l.getDisplayName(), l);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        languages.clear();
        languages.addAll(byName.values());
        if (!withLanguage) {
            return;
        }
        String saved = Prefs.language(prefs);
        Locale chosen = null;
        for (Locale l : languages) {
            if (l.toLanguageTag().equalsIgnoreCase(saved)) {
                chosen = l;
            }
        }
        if (chosen == null) {
            for (Locale l : languages) {
                if (l.getLanguage().equals(Locale.forLanguageTag(saved).getLanguage())) {
                    chosen = l;
                    break;
                }
            }
        }
        if (chosen == null && !languages.isEmpty()) {
            chosen = languages.get(0);
        }
        if (chosen != null) {
            prefs.edit().putString(Prefs.MAIN + "_lang", chosen.toLanguageTag()).apply();
            btnLanguage.setText("Idioma: " + chosen.getDisplayName());
        } else {
            btnLanguage.setText("Idioma: nenhum disponível");
        }
    }

    private void chooseLanguage() {
        if (!ready || languages.isEmpty()) {
            say("Nenhum idioma disponível neste motor.");
            return;
        }
        String[] names = new String[languages.size()];
        int checked = -1;
        String saved = Prefs.language(prefs);
        for (int i = 0; i < names.length; i++) {
            names[i] = languages.get(i).getDisplayName();
            if (languages.get(i).toLanguageTag().equalsIgnoreCase(saved)) {
                checked = i;
            }
        }
        list("Idioma", names, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
                Locale l = languages.get(which);
                prefs.edit().putString(Prefs.MAIN + "_lang", l.toLanguageTag())
                        .putString(who + "_voice", "").apply();
                btnLanguage.setText("Idioma: " + l.getDisplayName());
                fillVoices();
            }
        });
    }

    // ----- vozes -----

    private void fillVoices() {
        voices.clear();
        Locale lang = Locale.forLanguageTag(Prefs.language(prefs));
        for (Voice v : allVoices) {
            if (v.getLocale().toLanguageTag().equalsIgnoreCase(lang.toLanguageTag())) {
                voices.add(v);
            }
        }
        if (voices.isEmpty()) {
            for (Voice v : allVoices) {
                if (v.getLocale().getLanguage().equals(lang.getLanguage())) {
                    voices.add(v);
                }
            }
        }
        if (voices.isEmpty()) {
            voices.addAll(allVoices);
        }
        String saved = Prefs.voice(prefs, who);
        Voice chosen = null;
        for (Voice v : voices) {
            if (v.getName().equals(saved)) {
                chosen = v;
            }
        }
        if (chosen == null) {
            Voice def = null;
            try {
                def = tts.getDefaultVoice();
            } catch (Exception ignored) {
            }
            if (def != null && voices.contains(def)) {
                chosen = def;
            } else if (!voices.isEmpty()) {
                chosen = voices.get(0);
            }
        }
        String prefix = allowNone ? "Voz para emojis: " : "Voz: ";
        if (chosen != null) {
            prefs.edit().putString(who + "_voice", chosen.getName()).apply();
            btnVoice.setText(prefix + label(chosen));
        } else {
            prefs.edit().putString(who + "_voice", "").apply();
            btnVoice.setText(prefix + "padrão do motor");
        }
    }

    private void chooseVoice() {
        if (!ready || voices.isEmpty()) {
            say("Este motor não informou vozes. Será usada a voz padrão dele.");
            return;
        }
        String[] names = new String[voices.size()];
        int checked = -1;
        String saved = Prefs.voice(prefs, who);
        for (int i = 0; i < names.length; i++) {
            names[i] = label(voices.get(i));
            if (voices.get(i).getName().equals(saved)) {
                checked = i;
            }
        }
        list("Voz", names, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
                Voice v = voices.get(which);
                prefs.edit().putString(who + "_voice", v.getName()).apply();
                btnVoice.setText((allowNone ? "Voz para emojis: " : "Voz: ") + label(v));
            }
        });
    }

    private static String label(Voice v) {
        String name = v.getName();
        if (v.isNetworkConnectionRequired()) {
            name += " (precisa de internet)";
        }
        return name;
    }

    // ----- ouvir -----

    private void test() {
        if (tts == null || !ready) {
            say(Prefs.engine(prefs, who).isEmpty()
                    ? "Escolha um motor para emojis primeiro." : "O motor ainda está carregando.");
            return;
        }
        String voiceName = Prefs.voice(prefs, who);
        for (Voice v : voices) {
            if (v.getName().equals(voiceName)) {
                try {
                    tts.setVoice(v);
                } catch (Exception ignored) {
                }
                break;
            }
        }
        tts.setSpeechRate(Levels.rate(Prefs.rate(prefs, who)));
        tts.setPitch(Levels.pitch(Prefs.pitch(prefs, who)));
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Levels.volume(Prefs.volume(prefs, who)));
        tts.speak(textProvider.text(), TextToSpeech.QUEUE_FLUSH, params, "teste");
    }

    // ----- barras -----

    private void setupSlider(int seekId, int labelId, final String name, final String key, int value) {
        final PercentSeekBar bar = act.findViewById(seekId);
        final TextView lbl = act.findViewById(labelId);
        bar.setContentDescription(name);
        bar.setProgress(value);
        show(bar, lbl, name, value);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                show(bar, lbl, name, progress);
                prefs.edit().putInt(who + key, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
    }

    private static void show(PercentSeekBar bar, TextView lbl, String name, int value) {
        lbl.setText(name + ": " + value + "%");
        bar.updateState(value + "%");
    }

    private void setVoiceControlsEnabled(boolean enabled) {
        int[] ids = {R.id.btn_language, R.id.btn_voice, R.id.btn_test, R.id.seek_rate, R.id.seek_pitch,
                R.id.seek_volume};
        for (int id : ids) {
            act.findViewById(id).setEnabled(enabled);
        }
    }

    // ----- utilidades -----

    private void list(String title, String[] items, int checked, DialogInterface.OnClickListener l) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, l)
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void say(String msg) {
        android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_LONG).show();
    }
}
