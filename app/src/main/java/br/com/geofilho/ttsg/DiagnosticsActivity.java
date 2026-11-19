package br.com.geofilho.ttsg;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

/** Página "Diagnóstico": ligar o registro, ver, copiar, compartilhar e limpar o relatório. */
public class DiagnosticsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        final SharedPreferences prefs = Prefs.get(this);

        Switch sw = findViewById(R.id.switch_diag);
        sw.setChecked(Prefs.diagnosticsOn(prefs));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.edit().putBoolean("diagnostics_on", checked).apply();
                if (!checked) {
                    Diagnostics.clearUtterances();
                }
            }
        });

        findViewById(R.id.btn_view).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showReport();
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Diagnostics.clear(DiagnosticsActivity.this);
                Toast.makeText(DiagnosticsActivity.this, "Relatório limpo.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showReport() {
        final String report = Diagnostics.report(this);
        new AlertDialog.Builder(this)
                .setTitle("Relatório de diagnóstico")
                .setMessage(report)
                .setPositiveButton("Copiar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Diagnóstico", report));
                            Toast.makeText(DiagnosticsActivity.this, "Relatório copiado.", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNeutralButton("Compartilhar", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("text/plain");
                        send.putExtra(Intent.EXTRA_SUBJECT, "Diagnóstico do Gerenciador de TTS G");
                        send.putExtra(Intent.EXTRA_TEXT, report);
                        try {
                            startActivity(Intent.createChooser(send, "Compartilhar relatório"));
                        } catch (ActivityNotFoundException e) {
                            Toast.makeText(DiagnosticsActivity.this, "Nenhum aplicativo para compartilhar.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }
}
