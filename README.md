# Gerenciador de TTS G

## Autoria e direitos autorais

Este projeto é de autoria de **geofilho** ([github.com/geofilho](https://github.com/geofilho)). Todos os direitos são reservados (veja [LICENSE](LICENSE)).

Você pode usar o projeto como inspiração para criar o seu próprio, com código, nome e identidade diferentes. Copiar, clonar ou republicar este app, mesmo trocando só nomes ou ícones, exige autorização do autor: [abra uma issue](https://github.com/geofilho/Gerenciador-de-Tts-G/issues) para pedir.

## Baixar

[Baixar o aplicativo (APK, versão mais recente)](https://github.com/geofilho/Gerenciador-de-Tts-G/raw/main/atualizacao/GerenciadorTtsG.apk)

Depois de instalado, as próximas versões chegam pelo botão "Verificar atualização" do próprio aplicativo.

Aplicativo Android leve (cerca de 200 KB, sem bibliotecas externas) e feito para uso com leitor de tela.

## Tela principal (ordem de varredura)
1. Motor de TTS (lista os motores instalados)
2. Idioma
3. Voz
4. Caixa de texto de teste
5. Ouvir a voz
6. Velocidade, Tonalidade e Volume (0 a 100%, de 1 em 1%; 50% é o normal)
7. Dicionário (Adicionar palavra; Gerenciar dicionário, com editar, remover,
   exportar e importar; também dá para importar tocando no arquivo do
   dicionário, por exemplo em Meus Arquivos, e escolhendo "Importar no
   Gerenciador de TTS G")
8. Personalização de leitura (números inteiro, um a um ou dois a
   dois; velocidade dos números; pontuação nenhuma, algumas ou todas; voz para
   emojis, com a opção de ignorar a palavra "Emoji" antes dos emojis; ler
   automaticamente as sugestões do teclado Gboard, testado no Gboard, no
   Pixelboard e no Teclado Samsung; Botões sem etiqueta; Tradução de voz TTS;
   caixa de texto e Ouvir a voz)
   - Botões sem etiqueta: falar o nome escondido dos botões (textos de dentro,
     dicas escondidas e código interno, como btn_send = enviar), nome primeiro
     e tipo depois ("Pesquisar, Botão"); códigos falados pelo leitor de tela
     viram nomes legíveis; calar o "Sem marcador" das faixas vazias; dar nome
     você mesmo ao botão sem identificação (por aplicativo, tela e lugar, e
     também para o mesmo botão repetido nos cartões de uma lista), pelas
     configurações rápidas, pelo atalho de acessibilidade, pelo MacroDroid ou
     pela página; lista dos botões nomeados por aplicativo, com editar e apagar
   - Tradução de voz TTS (tradução automática: chave, idioma de origem, idioma
     de destino e teste)
9. Usar como motor de TTS do sistema
10. Saída de áudio do leitor de tela (manter a fala e, no Android 12+, os sons
    do leitor no telefone quando houver Bluetooth conectado)
11. Backup (fazer backup direto na pasta Download, sem teclado, com data e hora
    no nome, e compartilhar; restaurar pelo arquivo ou tocando nele no Meus
    Arquivos; leva as configurações, o dicionário e os botões nomeados)
12. Sobre (Verificar atualização e Diagnóstico)

## Vozes de todos os motores para outros aplicativos
Aplicativos que deixam escolher a voz (por exemplo, o Ariadne GPS) veem, além
da voz padrão do Gerenciador, as vozes de todos os outros motores instalados,
com o nome do motor e da voz e o idioma. Escolhendo uma delas, a fala sai por
aquele motor e aquela voz, com o dicionário, os números, a pontuação, os
emojis, a tradução e a saída de áudio do Gerenciador. O leitor de tela continua
com a voz padrão. A lista fica guardada e é refeita quando um motor muda.

## Acesso rápido
- Atalhos ao manter o ícone pressionado (também nas rotinas da Samsung):
  Dicionário, Saída de áudio e Tradução.
- Widgets da tela inicial para as mesmas três funções. Os de Tradução e Saída
  de áudio são chaves: cada toque liga ou desliga e fala o novo estado. O do
  Dicionário abre o menu (ao sair, volta para a tela inicial).
- Botões nas configurações rápidas do Android: Dicionário, Saída de áudio,
  Tradução (estes dois como chaves de liga e desliga) e Dar nome ao botão.
- MacroDroid: "Iniciar atividade de aplicativo" com as entradas Dicionario,
  Ligar_traducao, Desligar_traducao, Ligar_saida_de_audio,
  Desligar_saida_de_audio, Alternar_traducao, Alternar_saida_de_audio (liga
  e desliga no mesmo atalho) e Dar_nome_ao_botao; ou "Iniciar atalho" > Ações do Gerenciador de TTS G.

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
Primeiro, um dicionário de tradução embutido (res/raw/traducao_base.txt), com
cerca de 1.400 palavras e frases comuns de aplicativos, do inglês para o
português, sem internet. Depois, online, pelo tradutor do Google. Texto que já
está no idioma de destino não é enviado; traduções repetidas ficam guardadas; o leitor de tela não espera mais
de 2,5 segundos nem fica preso quando você passa para outro item. Sem
internet, lê o texto original.

## Gerar o APK
- Android Studio: abrir a pasta e rodar `assembleRelease`.
- Sem Gradle: `ANDROID_JAR=/caminho/android.jar scripts/build-sem-gradle.sh`.
