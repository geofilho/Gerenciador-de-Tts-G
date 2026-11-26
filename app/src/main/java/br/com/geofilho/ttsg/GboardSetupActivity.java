package br.com.geofilho.ttsg;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

/**
 * Explica a leitura das sugestões do Gboard e leva às configurações, na mesma ordem do
 * Compartilhar G: tentar ativar (o Android bloqueia), liberar a configuração restrita e ativar de novo.
 */
public class GboardSetupActivity extends Activity {

    private Button btnEnable;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_gboard_setup);
        btnEnable = findViewById(R.id.btn_gboard_enable);
        btnEnable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openServiceSettings();
            }
        });
        findViewById(R.id.btn_gboard_restricted).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))) {
                    Toast.makeText(GboardSetupActivity.this, "Toque no menu de três pontos, no canto de cima, e em "
                            + "Permitir configurações restritas. Se ele não aparecer, tente antes ativar o serviço.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        findViewById(R.id.btn_gboard_done).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        btnEnable.setText(isEnabled(this)
                ? "Ativar em acessibilidade. Serviço: ativado, pronto para usar"
                : "Ativar em acessibilidade. Serviço: desativado");
    }

    /** O serviço "ler sugestões do Gboard" está ligado nas configurações de acessibilidade? */
    static boolean isEnabled(Context c) {
        try {
            String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return on != null && on.contains(c.getPackageName() + "/") && on.contains("GboardService");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Android 13 ou mais novo: abre direto a página do serviço, onde fica a chave de ligar. Se o aparelho
     * não aceitar, abre a lista de Acessibilidade; se nem ela, as Configurações.
     */
    private void openServiceSettings() {
        String me = new ComponentName(this, GboardService.class).flattenToString();
        if (Build.VERSION.SDK_INT >= 33) {
            Intent direct = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, me);
            if (open(direct)) {
                return;
            }
        }
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", me);
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", me)
                .putExtra(":settings:show_fragment_args", args);
        if (open(list)) {
            Toast.makeText(this, "Entre em Aplicativos instalados e ative Gerenciador de TTS G: ler sugestões do Gboard.",
                    Toast.LENGTH_LONG).show();
        } else {
            open(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private boolean open(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
