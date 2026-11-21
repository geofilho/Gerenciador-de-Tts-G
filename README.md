# Gerenciador de TTS G

## Baixar

[Baixar o aplicativo (APK, versão mais recente)](https://github.com/geofilho/Gerenciador-de-Tts-G/raw/main/atualizacao/GerenciadorTtsG.apk)

Depois de instalado, as próximas versões chegam pelo botão "Verificar atualização" do próprio aplicativo.

Aplicativo Android leve (cerca de 50 KB, sem bibliotecas externas) e feito para uso com leitor de tela.

## Tela principal (ordem de varredura)
1. Motor de TTS (lista os motores instalados)
2. Idioma
3. Voz
4. Caixa de texto de teste
5. Ouvir a voz
6. Velocidade, Tonalidade e Volume (0 a 100%, de 1 em 1%; 50% é o normal)
7. Dicionário (Adicionar palavra; Gerenciar dicionário, com editar, remover,
   exportar e importar)
8. Leitura de números, pontuação e emojis (números inteiro, um a um ou dois a
   dois; velocidade dos números; pontuação nenhuma, algumas ou todas; voz para
   emojis; caixa de texto e Ouvir a voz)
9. Usar como motor de TTS do sistema
10. Saída de áudio do leitor de tela (manter a fala e, no Android 12+, os sons
    do leitor no telefone quando houver Bluetooth conectado)
11. Tradução de voz TTS (tradução automática pela internet: chave, idioma de
    origem, idioma de destino e teste)
12. Verificar atualização (mostra as novidades e, com o OK, baixa e instala)
13. Diagnóstico (ativar ou desativar o registro; ver, copiar, compartilhar e
    limpar o relatório)

## Acesso rápido
- Atalhos ao manter o ícone pressionado (também nas rotinas da Samsung):
  Dicionário, Saída de áudio e Tradução.
- Widgets da tela inicial para as mesmas três funções. Os de Tradução e Saída
  de áudio são chaves: cada toque liga ou desliga e fala o novo estado. O do
  Dicionário abre o menu (ao sair, volta para a tela inicial).
- Botões nas configurações rápidas do Android, também com Tradução e Saída de
  áudio como chaves de liga e desliga.
- MacroDroid: "Iniciar atividade de aplicativo" com as entradas Dicionario,
  Ligar_traducao, Desligar_traducao, Ligar_saida_de_audio e
  Desligar_saida_de_audio; ou "Iniciar atalho" > Ações do Gerenciador de TTS G.

## Como funciona
O app também é um motor de TTS. Quando ele é escolhido como motor padrão do
sistema, todo texto do leitor de tela passa por ele e, nesta ordem: é traduzido
(se a tradução estiver ligada), passa pelo dicionário e pelas regras de
emojis, números e pontuação, e só então vai para o motor e a voz escolhidos no
app.

- Antes de digitar a senha, depois de religar, o app fala com o motor reserva
  (Samsung ou Google) e volta sozinho para a voz escolhida depois da senha.
- Se o motor escolhido falhar, a fala sai pelo motor reserva.
- O áudio começa a tocar enquanto o motor ainda está gerando.

### Tradução de voz
Online, pelo tradutor do Google. Texto que já está no idioma de destino não é
enviado; traduções repetidas ficam guardadas; o leitor de tela não espera mais
de 2,5 segundos nem fica preso quando você passa para outro item. Sem
internet, lê o texto original.

## Gerar o APK
- Android Studio: abrir a pasta e rodar `assembleRelease`.
- Sem Gradle: `ANDROID_JAR=/caminho/android.jar scripts/build-sem-gradle.sh`.
