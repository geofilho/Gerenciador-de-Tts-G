package br.com.geofilho.ttsg;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

/** Menu Sobre: Verificar atualização e Diagnóstico. */
public class AboutActivity extends Activity {

    private Updater updater;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        updater = new Updater(this);
        Button btnUpdate = findViewById(R.id.btn_update);
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            btnUpdate.setText(getString(R.string.check_update) + ", versão instalada " + v);
        } catch (Exception ignored) {
        }
        btnUpdate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.check();
            }
        });
        findViewById(R.id.btn_diagnostics).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(AboutActivity.this, DiagnosticsActivity.class));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updater.onResume();
    }
}
