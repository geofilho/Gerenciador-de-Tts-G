package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

/** Página "Leitura de números e pontuação". */
public class NumbersActivity extends Activity {

    /** Na mesma ordem das constantes NUMBERS_* do TextProcessor. */
    private static final String[] NUMBER_MODES = {"Número inteiro", "Um a um", "Dois a dois"};
    /** Na mesma ordem das constantes PUNCT_* do TextProcessor. */
    private static final String[] PUNCT_MODES = {"Nenhuma", "Algumas", "Todas"};

    private SharedPreferences prefs;
    private Button btnNumbers;
    private Button btnPunctuation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_numbers);
        prefs = Prefs.get(this);

        btnNumbers = findViewById(R.id.btn_numbers);
        btnPunctuation = findViewById(R.id.btn_punctuation);
        refresh();

        btnNumbers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Leitura de números", NUMBER_MODES, "number_mode", Prefs.numberMode(prefs));
            }
        });
        btnPunctuation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                choose("Leitura de pontuação", PUNCT_MODES, "punctuation_mode", Prefs.punctuationMode(prefs));
            }
        });

        if (!prefs.contains("number_rate")) {
            prefs.edit().putInt("number_rate", Prefs.numberRate(prefs)).apply();
        }
        VoiceSection.bindSlider(this, prefs, R.id.seek_number_rate, R.id.lbl_number_rate,
                "Velocidade dos números", "number_rate", Prefs.numberRate(prefs));
    }

    private void refresh() {
        btnNumbers.setText("Leitura de números: " + label(NUMBER_MODES, Prefs.numberMode(prefs)));
        btnPunctuation.setText("Leitura de pontuação: " + label(PUNCT_MODES, Prefs.punctuationMode(prefs)));
    }

    private static String label(String[] items, int index) {
        return items[index >= 0 && index < items.length ? index : 0];
    }

    private void choose(String title, String[] items, final String key, int checked) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(items, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        prefs.edit().putInt(key, which).apply();
                        refresh();
                        d.dismiss();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
