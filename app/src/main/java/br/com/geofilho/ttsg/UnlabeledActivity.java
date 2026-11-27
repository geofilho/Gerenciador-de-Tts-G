package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
        findViewById(R.id.btn_name_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explain();
            }
        });
        findViewById(R.id.btn_named_list).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(UnlabeledActivity.this, NamedButtonsActivity.class));
            }
        });
    }

    /** Explica como dar nome; se já houver um botão sem nome guardado, oferece nomear agora. */
    private void explain() {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.name_button_title)
                .setMessage(R.string.name_button_help)
                .setPositiveButton(R.string.gboard_done, null);
        String[] t = Unlabeled.target();
        if (t != null) {
            b.setNeutralButton(R.string.name_button_last, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    startActivity(new Intent(UnlabeledActivity.this, NameButtonActivity.class));
                }
            });
        }
        b.show();
    }
}
