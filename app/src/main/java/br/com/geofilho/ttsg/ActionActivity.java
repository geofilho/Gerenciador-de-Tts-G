package br.com.geofilho.ttsg;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Executa as ações rápidas dos atalhos (MacroDroid e outros): Dicionário, ligar e
 * desligar a tradução, ligar e desligar a saída de áudio. Não mostra tela: faz a
 * ação, avisa com uma mensagem falada e fecha.
 * Só aceita atalhos criados pelo próprio app (senha interna), para outro app não
 * conseguir ligar a tradução escondido.
 */
public class ActionActivity extends Activity {

    static final String ACTION_DICTIONARY = "br.com.geofilho.ttsg.acao.DICIONARIO";
    static final String ACTION_TRANSLATE_ON = "br.com.geofilho.ttsg.acao.LIGAR_TRADUCAO";
    static final String ACTION_TRANSLATE_OFF = "br.com.geofilho.ttsg.acao.DESLIGAR_TRADUCAO";
    static final String ACTION_AUDIO_ON = "br.com.geofilho.ttsg.acao.LIGAR_SAIDA_AUDIO";
    static final String ACTION_AUDIO_OFF = "br.com.geofilho.ttsg.acao.DESLIGAR_SAIDA_AUDIO";
    static final String ACTION_TRANSLATE_TOGGLE = "br.com.geofilho.ttsg.acao.ALTERNAR_TRADUCAO";
    static final String ACTION_AUDIO_TOGGLE = "br.com.geofilho.ttsg.acao.ALTERNAR_SAIDA_AUDIO";
    static final String EXTRA_TOKEN = "senha";

    /** Senha interna, criada uma vez, que vai dentro de cada atalho. */
    static String token(SharedPreferences p) {
        String t = p.getString("action_token", "");
        if (t.isEmpty()) {
            t = java.util.UUID.randomUUID().toString();
            p.edit().putString("action_token", t).apply();
        }
        return t;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences p = Prefs.get(this);
        Intent in = getIntent();
        String action = in != null ? in.getAction() : null;
        String sent = in != null ? in.getStringExtra(EXTRA_TOKEN) : null;
        // Entradas com nome próprio (para "Iniciar atividade de aplicativo" do MacroDroid, que mostra
        // o nome técnico da tela): a ação vem do nome da entrada, sem senha.
        String byName = aliasAction(getComponentName() != null ? getComponentName().getClassName() : "");
        if (byName != null) {
            action = byName;
        } else if (action == null || sent == null || !sent.equals(token(p))) {
            say("Atalho inválido. Crie o atalho de novo pelo Gerenciador de TTS G.");
            finish();
            return;
        }
        if (ACTION_DICTIONARY.equals(action)) {
            Intent d = new Intent(this, DictionaryQuickActivity.class);
            d.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(d);
        } else if (ACTION_TRANSLATE_ON.equals(action)) {
            Toggles.setTranslation(this, true);
        } else if (ACTION_TRANSLATE_OFF.equals(action)) {
            Toggles.setTranslation(this, false);
        } else if (ACTION_AUDIO_ON.equals(action)) {
            Toggles.setAudio(this, true);
        } else if (ACTION_AUDIO_OFF.equals(action)) {
            Toggles.setAudio(this, false);
        } else if (ACTION_TRANSLATE_TOGGLE.equals(action)) {
            Toggles.toggleTranslation(this); // liga se estiver desligada, desliga se estiver ligada
        } else if (ACTION_AUDIO_TOGGLE.equals(action)) {
            Toggles.toggleAudio(this);
        }
        finish();
    }

    /** Ação de cada entrada com nome próprio (activity-alias no manifesto). */
    private static String aliasAction(String className) {
        String n = className.substring(className.lastIndexOf('.') + 1);
        if ("Dicionario".equals(n)) {
            return ACTION_DICTIONARY;
        }
        if ("Ligar_traducao".equals(n)) {
            return ACTION_TRANSLATE_ON;
        }
        if ("Desligar_traducao".equals(n)) {
            return ACTION_TRANSLATE_OFF;
        }
        if ("Ligar_saida_de_audio".equals(n)) {
            return ACTION_AUDIO_ON;
        }
        if ("Desligar_saida_de_audio".equals(n)) {
            return ACTION_AUDIO_OFF;
        }
        if ("Alternar_traducao".equals(n)) {
            return ACTION_TRANSLATE_TOGGLE;
        }
        if ("Alternar_saida_de_audio".equals(n)) {
            return ACTION_AUDIO_TOGGLE;
        }
        return null;
    }

    private void say(String msg) {
        Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
    }
}
