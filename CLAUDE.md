# Instruções para este projeto

## Padrão de acessibilidade (obrigatório em tudo que for feito)

Todo aplicativo ou programa deve ser **100% acessível com leitor de tela** e
**100% acessível para baixa visão**.

### Leitor de tela
- Todo controle tem nome falado (texto, rótulo ou descrição) e a ordem de
  varredura segue a ordem lógica da tela.
- Nada de informação só visual: estados e avisos também são falados.
- Poucos toques: interface objetiva, sem telas e etapas desnecessárias.
- Barras de ajuste andam de 1 em 1% com os gestos do leitor de tela.

### Baixa visão (guia de acessibilidade do Android)
- Contraste de texto de pelo menos 4,5:1 (3:1 para texto grande, 18pt ou
  14pt em negrito) e 3:1 para ícones e outros elementos.
- Tamanhos de texto em `sp`, nunca em `dp` ou `px`, para seguir o tamanho de
  fonte do sistema; corpo de texto nunca menor que 12sp (aqui usamos 18sp).
- Áreas de toque de pelo menos 48dp (aqui usamos 56dp).
- Sem altura fixa em elementos com texto: o texto precisa crescer com a fonte
  e o tamanho de exibição; telas roláveis.
- Usar cores e temas do sistema (claro/escuro), para respeitar tema escuro,
  texto em alto contraste, inversão e correção de cores, texto em negrito e
  ampliação configurados na acessibilidade do aparelho.
- Sem textos todos em maiúsculas; espaço entre linhas confortável.
- Não usar só a cor para transmitir informação.
