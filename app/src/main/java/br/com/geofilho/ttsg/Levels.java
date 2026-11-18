package br.com.geofilho.ttsg;

/**
 * Converte as barras de 0 a 100% nos valores que o TTS entende.
 * 50% é sempre o valor normal do motor.
 */
final class Levels {
    private Levels() {
    }

    /** Velocidade: 0% = 0,25x, 50% = 1x, 100% = 4x. */
    static float rate(int p) {
        return p <= 50 ? 0.25f + p / 50f * 0.75f : 1f + (p - 50) / 50f * 3f;
    }

    /** Tonalidade: 0% = 0,5x, 50% = 1x, 100% = 2x. */
    static float pitch(int p) {
        return p <= 50 ? 0.5f + p / 50f * 0.5f : 1f + (p - 50) / 50f;
    }

    static float volume(int p) {
        return p / 100f;
    }
}
