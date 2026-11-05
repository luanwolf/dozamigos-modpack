# Dozamigos Modpack Updater (Forge 26.3)

Ao abrir o Minecraft, o mod compara a pasta `mods` com uma pasta de um repositório GitHub e:
adiciona mods novos, atualiza os alterados (por hash) e remove os que saíram do repositório
(apenas os que ele mesmo instalou antes; mods pessoais ficam intactos).
Após atualizar, o jogo fecha com um aviso: basta abrir de novo.

## Como usar
1. Crie um repositório no GitHub e coloque os `.jar` do modpack na pasta `mods/`.
   Para atualizar o pack, basta dar commit/push (adicionar, trocar ou apagar jars).
2. Compile: `gradlew build` → `build/libs/dozamigosupdater-1.0.0.jar`.
3. Entregue esse jar aos amigos (colocar em `mods/`). No primeiro início é criado
   `config/dozamigosupdater.properties`; edite `repo=dono/nome` (e `token=` se o repo for privado).
   Dica: distribua o jar já com esse arquivo de config pronto.

Jars com mais de 100 MB não são suportados pelo GitHub; sem `token` o download usa `raw.githubusercontent.com`.
