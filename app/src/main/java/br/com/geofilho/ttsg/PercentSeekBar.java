package br.com.geofilho.ttsg;

import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

/**
 * Barra de 0 a 100% que, com o leitor de tela (deslizar para cima/baixo ou
 * teclas de volume no TalkBack), anda de 1 em 1% em vez dos 5% padrão do Android.
 */
public class PercentSeekBar extends SeekBar {

    public PercentSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setMax(100);
        setKeyProgressIncrement(1);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (isEnabled()) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                return step(1);
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                return step(-1);
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private boolean step(int delta) {
        int value = Math.max(0, Math.min(getMax(), getProgress() + delta));
        if (value == getProgress()) {
            return false;
        }
        setProgress(value);
        return true;
    }

    /** Atualiza o que o leitor de tela fala como valor da barra. */
    void updateState(String spokenValue) {
        if (Build.VERSION.SDK_INT >= 30) {
            setStateDescription(spokenValue);
        }
    }
}
