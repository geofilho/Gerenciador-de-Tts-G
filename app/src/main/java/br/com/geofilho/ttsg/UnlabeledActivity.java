package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;

/** Página "Botões sem etiqueta": a chave, como dar nome a um botão e os botões que você nomeou. */
public class UnlabeledActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_unlabeled);
        final SharedPreferences prefs = Prefs.get(this);
        Switch sw = findViewById(R.id.switch_unlabeled);
        sw.setChecked(Prefs.unlabeledOn(prefs));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("unlabeled_on", checked).apply();
                if (checked && !GboardSetupActivity.isEnabled(UnlabeledActivity.this)) {
                    startActivity(new Intent(UnlabeledActivity.this, GboardSetupActivity.class));
                }
            }
        });
        Switch thin = findViewById(R.id.switch_silence_thin);
        thin.setChecked(Prefs.silenceThin(prefs));
        thin.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("silence_thin", checked).apply();
            }
        });
        findViewById(R.id.btn_name_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Já parou num botão sem nome e voltou aqui: abre direto a janela de dar nome.
                if (Unlabeled.target() != null) {
                    startActivity(new Intent(UnlabeledActivity.this, NameButtonActivity.class));
                } else {
                    explain();
                }
            }
        });
        findViewById(R.id.btn_named_list).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(UnlabeledActivity.this, NamedButtonsActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // O texto do botão diz o que ele vai fazer agora.
        android.widget.Button b = findViewById(R.id.btn_name_button);
        String[] t = Unlabeled.target();
        if (t == null) {
            b.setText(R.string.name_button_how);
        } else {
            String app = t[0];
            try {
                app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(t[0], 0)).toString();
            } catch (Exception ignored) {
            }
            b.setText(getString(R.string.name_button_now, app));
        }
    }

    /** Explica como dar nome a um botão. */
    private void explain() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.name_button_how)
                .setMessage(R.string.name_button_help)
                .setPositiveButton(R.string.gboard_done, null)
                .show();
    }
}
