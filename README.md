# SchemBot (Fabric 1.21.11)

Client-side bot that builds schematics (.litematic, .schem, .nbt).

## Get the jar (no dev setup)
1. Create a GitHub repo (public repos get free build minutes) and upload everything in this folder,
   including the hidden `.github` folder. If the hidden folder won't upload, use
   "Add file > Create new file", type `.github/workflows/build.yml`, and paste the file contents.
2. Open the repo's **Actions** tab, open the latest run, wait for the green check.
3. Download the **schembot** artifact, unzip it, and put the jar in `.minecraft/mods`.

Requires Fabric Loader 0.18+ and Fabric API for 1.21.11.

## Use
Put schematics in `.minecraft/schematics/`, then in game:
`/schembot list`, `/schembot load <file>`, `/schembot info`.
