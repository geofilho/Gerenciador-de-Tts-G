# Gerenciador de TTS G

Aplicativo Android leve (cerca de 50 KB, sem bibliotecas externas) e feito para uso com leitor de tela.

## Tela principal (ordem de varredura)
1. Motor de TTS (lista os motores instalados)
2. Idioma
3. Voz
4. Caixa de texto de teste
5. Ouvir a voz
6. Velocidade, Tonalidade e Volume (0 a 100%, de 1 em 1%; 50% é o normal)
7. Adicionar palavra ao dicionário (abre os campos "Palavra ou frase" e "Substituir por")
8. Gerenciar dicionário (tocar numa palavra: Editar ou Remover; exportar e importar)
9. Escolher TTS para falar emojis (motor, voz, ouvir, ajustes e chave de agrupar repetidos)
10. Usar como motor de TTS do sistema
11. Leitura de números: número inteiro, um a um ou dois a dois
12. Verificar atualização (mostra as novidades e, com o OK, baixa e instala)

## Como funciona
O app também é um motor de TTS. Quando ele é escolhido como motor padrão do
sistema, todo texto do leitor de tela passa pelo dicionário e pelas regras de
emojis e só então vai para o motor e a voz escolhidos no app.

## Gerar o APK
- Android Studio: abrir a pasta e rodar `assembleRelease`.
- Sem Gradle: `ANDROID_JAR=/caminho/android.jar scripts/build-sem-gradle.sh`.
