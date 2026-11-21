package br.com.geofilho.ttsg;

import android.app.Activity;
import android.os.Bundle;

/**
 * Aberta pelo widget "Dicionário": mostra só o menu do dicionário por cima da
 * tela inicial e fecha quando a pessoa termina, voltando direto para a tela inicial.
 */
public class DictionaryQuickActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            DictionaryDialogs.showMenu(this, new Runnable() {
                @Override
                public void run() {
                    finish();
                }
            });
        }
    }
}
