# Raw Input Buffer — Forge 1.8.9

Port do [RawInputBuffer de Walksy](https://github.com/Walksy/RawInputBuffer) para
**Minecraft 1.8.9, Forge e Java 8**, no Windows. Mantém a captura Win32 Raw Input
em uma thread dedicada e acumula o movimento do mouse antes de entregá-lo ao jogo.

## Instalação

1. Instale o Minecraft **1.8.9** com **Forge 11.15.1.2318**.
2. Baixe `RawInputBuffer-Forge-1.1.0+1.8.9.jar` na página de
   [Releases](https://github.com/LucasLeao18/RawInputBuffer/releases).
3. Coloque o arquivo na pasta `.minecraft/mods` e inicie o perfil Forge com Java 8.

Use o `.jar` normal; o arquivo `-sources.jar` é apenas para desenvolvimento.
É um mod de cliente e não precisa ser instalado no servidor. A captura é ativada
automaticamente quando o mouse está preso ao jogo. Menus e perda de foco usam a
entrada normal do Windows. Em Linux e macOS, o mod permanece desativado.

## Como funciona

- Uma janela Win32 oculta recebe `WM_INPUT` em uma thread dedicada.
- `RIDEV_NOLEGACY` evita a geração de eventos legados de mouse para o jogo durante
  a captura. O registro é removido ao soltar o mouse ou perder o foco.
- Movimento relativo é acumulado sem criar um objeto por movimento. Os eixos
  chegam aos contadores do LWJGL 2 antes da atualização da câmera, preservando a
  sensibilidade e a inversão de mouse do Minecraft.
- Cliques dos cinco botões e scroll entram no buffer do LWJGL 2, passando pelo
  processamento normal do Minecraft e pelos eventos do Forge.
- A posição física do cursor é centralizada periodicamente. O HWND é atualizado
  quando o LWJGL recria a janela, por exemplo ao alternar tela cheia.
- Erros de inicialização desativam a captura e mantêm o mouse padrão.

O port usa os eventos do Forge e reflexão em campos **não ofuscados do LWJGL 2**.
Não usa mixins, coremods, código GLFW, Fabric ou WalksyLib. Usa a JNA **3.4.0** já
fornecida pelo Minecraft 1.8.9 e não inclui bibliotecas ou DLLs extras no mod.

## Estado da validação

Build e **10 testes automatizados aprovados em Windows x64 com Java 8**:
acúmulo de movimento equivalente a 8.000 pacotes, concorrência, cinco botões,
scroll negativo, descarte na perda de foco, limite da fila, decodificação dos
eventos pelo LWJGL exato do Minecraft 1.8.9, estruturas Win32 e criação/encerramento
da janela nativa. O teste sintético de 8.000 pacotes verifica o acumulador;
**não é um benchmark com mouse físico a 8.000 Hz**.

Esta primeira versão é uma **prévia**: ainda falta testar uma sessão real do
Minecraft com hardware de alto polling. Não há medição de ganho de FPS nesta
versão. A validação manual deve incluir câmera, atacar/usar/segurar, botões
laterais, hotbar, inventário, Esc, Alt+Tab, tela cheia e encerramento do cliente.

Requer o LWJGL 2 padrão do Forge 1.8.9. Clientes que substituem LWJGL, outros mods
que assumem o registro Win32 Raw Input ou alteram esses mesmos buffers não foram
validados. Movimento absoluto de tablets/telas de toque não controla a câmera.

## Compilar

Use **JDK 21 para executar o Gradle**. O compilador gera bytecode Java 8:

```powershell
.\gradlew.bat build
```

Para executar os testes no Java 8 no Windows:

```powershell
.\gradlew.bat clean build "-PtestJavaHome=C:/caminho/para/jdk8"
```

Os arquivos ficam em `build/libs/`. O workflow do GitHub Actions compila com JDK 21
e executa os testes em Java 8 no Windows. Não é necessário obter ou redistribuir
um jar de Minecraft para compilar este adaptador.

## Créditos e licença

Mod original e conceito: **Walksy**. Port Forge 1.8.9: **LucasLeao18**.
Licença [MIT](LICENSE), preservando o aviso de copyright original.
A branch `main` mantém o código upstream; `forge-1.8.9` contém este port.
