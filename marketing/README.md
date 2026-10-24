# Marketing assets: 2.0 "The Supply Update"

Listing copy and artwork for CurseForge, Modrinth and Hangar. Nothing here ships in the plugin jar.

## Listing

| File | Use |
| --- | --- |
| [`curseforge/description.md`](curseforge/description.md) | Full project description (Markdown; also works on Modrinth and Hangar) |
| [`curseforge/summary.txt`](curseforge/summary.txt) | Short project summary (248 characters) |
| [`../DonutOrders-2.0.0-PatchNotes.md`](../DonutOrders-2.0.0-PatchNotes.md) | File changelog the publish workflow uploads |

Image links in the description point at `raw.githubusercontent.com/MeherBenSalem/DonutOrders/main/marketing/images/...`,
so they work once this folder is on `main`. To host on CurseForge instead, upload the images to the project gallery and
replace each URL.

The description runs in this order:
1. the board, delivering, selling, new orders and price insight;
2. your orders, notifications, economy and rules, and safety;
3. integrations, staff tools, upgrading from 1.x and configuration;
4. compatibility, commands and permissions, the FAQ, and a gallery of 2.0.0 screenshots.

The artwork shares the pixel donut, fonts and dark backdrop of DonutAuctionHouse. Orders use mint icing where the
auction house uses pink.

## Adding a Minecraft version

The supported range comes from [`release/supported-minecraft.json`](../release/supported-minecraft.json), which the
publish workflow also uses. The banner and the compatibility card read it, so a new version needs no artwork edits:

1. Add the version to `release/supported-minecraft.json` and to `MATRIX` in
   [`tools/smoke/run.mjs`](../tools/smoke/run.mjs).
2. Run the smoke matrix, then `node tools/smoke/compat.mjs` to refresh `source/compat.json`.
3. Rerun the renderer for `banner compatibility`, and add a row to the compatibility table in
   `curseforge/description.md` if the Java requirement changes.

A new release name or number goes in `RELEASE` in `source/render.cjs` and in the first lines of the description.

## Images

| File | Size | Use |
| --- | --- | --- |
| `images/banner.png` | 1800×600 | Description header: the board, "2.0 · The Supply Update", platforms and versions |
| `images/icon.png` | 512×512 | Project avatar: the pixel donut with a gold "BUY" tag |
| `images/card-*.png` | 1600×900 | 14 feature cards: board, deliver, sell, create, insight, orders, notifications, economy, safety, integrations, staff, upgrade, config, compatibility |
| `images/header-*.png` | 1400×120, transparent | 17 section headings for the description |
| `images/divider.png` | 1400×40, transparent | Section divider |
| `images/gifs/deliver.gif` | 640×360 | Opening the board, picking the ender pearl order, filling the delivery menu (a shulker box included) and delivering |
| `images/screenshots/*.jpg` | 1920×1080 | Gallery uploads and card art |

### How the screenshots were captured

Every screenshot and the GIF are unedited captures of the **vanilla Minecraft 1.21.11 client**, launched with
portablemc, on a **Paper 1.21.11** server running DonutOrders 2.0.0.
- The client ran in a 1920×1080 Xvfb display with software rendering (Mesa llvmpipe) at GUI scale 3.
- It was driven by [`tools/marketing/capture.mjs`](../tools/marketing/capture.mjs) through console commands and XTEST
  mouse and keyboard input.

- **The world** is seed 8675309 on peaceful, at noon, in the nearest cherry grove.
  - Moonbeam stands on a single leaf block five blocks up (out of view), so the camera looks over the grove.
  - Recipes and the diamond and obsidian advancements were unlocked before the shots, so no toasts cover the menus.
  - `scenery.jpg` (and the blurred card backdrop) has the HUD hidden with F1. Every menu shot has the HUD on.
- **The board** was filled by the smoke-test fixture (`/lab seed 40`): made-up buyers, prices around typical survival
  values, two enchanted-book orders, and some orders partly delivered.
  - Moonbeam created four orders of their own with `/order create`.
  - The delivery history behind price insight was written straight into the SQLite database before the second
    start: ten diamond, ender pearl, obsidian and golden carrot deliveries in the last day.
- **Configuration**: the defaults, except a 5% seller tax paid to a `ServerBank` account and alerts sent at once
  (`notifications.batch-seconds: 0`). The economy is the fixture's in-memory Vault economy, so prices use Vault's plain
  `$` formatting.
- **Real actions**: every delivery, sale and alert in the shots happened.
  - A second player, Kiri (a mineflayer bot), sold diamonds and mending books into Moonbeam's orders. That produced
    the delivery alert and the filled title.
  - Moonbeam delivered ender pearls (a shulker box among them) through the delivery menu and sold obsidian, golden
    carrots and slime balls with `/orders sell all`.
  - The staff shots were taken after `/op Moonbeam`.
- **The upgrade card** shows the real console of a separate server started with DonutOrders 1.x files (config,
  items and a 1.x database).
- **`deliver.gif`** is a real-time recording (x11grab, 12 fps, cropped to the centre 1280×720 and scaled to 640×360).

## Regenerating the artwork

```bash
mvn -B -DskipTests package
mvn -B -f tools/smoke/fixture/pom.xml package -Ddonutorders.version=2.0.0 -Ddonutorders.jar="$PWD/target/DonutOrders-2.0.0.jar"
ORDERLAB_PORT=25600 node tools/marketing/capture.mjs     # screenshots, the GIF and console text (about 6 minutes)
cp marketing/images/screenshots/scenery.jpg marketing/source/backdrop.jpg
python3 tools/marketing/crops.py                          # crop boxes of menus and tooltips
node tools/smoke/compat.mjs                               # the compatibility card's data
NODE_PATH="$(npm root -g)" node marketing/source/render.cjs   # every image, or name the ones to render
```

`render.cjs` needs Playwright with Chromium. The fonts are Outfit and Inter (SIL Open Font License 1.1, in
`source/fonts/`).
