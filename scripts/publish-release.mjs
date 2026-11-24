/**
 * Upload one Paper/Folia plugin jar as a single CurseForge file, tagged for every
 * Minecraft version in release/supported-minecraft.json (NightBeam convention:
 * CurseForge only since 24 Nov 2026; the Modrinth project gets no new versions).
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const support = JSON.parse(
  fs.readFileSync(path.join(root, "release", "supported-minecraft.json"), "utf8"),
);
const gameVersions = Array.isArray(support) ? support : support.game_versions;
const loaders = Array.isArray(support)
  ? ["paper", "folia", "purpur", "spigot", "bukkit"]
  : support.loaders;
if (!Array.isArray(gameVersions) || gameVersions.length === 0) {
  throw new Error("release/supported-minecraft.json has no game versions");
}
if (!Array.isArray(loaders) || loaders.length === 0) {
  throw new Error("release/supported-minecraft.json has no loaders");
}

const version = process.env.VERSION;
const jar = process.env.JAR_PATH;

if (!version) throw new Error("VERSION is required");
if (!jar || !fs.existsSync(jar)) throw new Error("JAR_PATH missing or not found: " + jar);

function sectionForVersion(markdown, ver) {
  const lines = markdown.split(/\r?\n/);
  const start = lines.findIndex((line) => {
    const t = line.trim();
    return t === `## ${ver}` || t === `# ${ver}` || t.startsWith(`## ${ver} `) || t.startsWith(`# ${ver} `);
  });
  if (start < 0) return null;
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => /^##\s/.test(line.trim()));
  const body = (end < 0 ? rest : rest.slice(0, end)).join("\n").trim();
  return body || null;
}

function resolveChangelog(ver) {
  const named = fs.readdirSync(root).filter(
    (f) =>
      f.toLowerCase().endsWith(`-${ver}-patchnotes.md`) ||
      f.toLowerCase() === `${ver}-patchnotes.md`,
  );
  for (const f of named) {
    const p = path.join(root, f);
    if (fs.existsSync(p)) return fs.readFileSync(p, "utf8");
  }
  for (const f of ["CHANGELOG.md", "PATCH_NOTES.md"]) {
    const p = path.join(root, f);
    if (!fs.existsSync(p)) continue;
    const full = fs.readFileSync(p, "utf8");
    return sectionForVersion(full, ver) || full;
  }
  return `Release ${ver}`;
}

const changelog = resolveChangelog(version);
const jarName = path.basename(jar);

(async () => {
  if (!process.env.CURSEFORGE_ID) throw new Error("Set CURSEFORGE_ID (the CurseForge project ID)");
  if (!process.env.CURSEFORGE_TOKEN || !process.env.CURSEFORGE_ID) {
    throw new Error("CURSEFORGE_TOKEN and CURSEFORGE_ID are required");
  }

  const gameVersionNames = [...gameVersions, "Client", "Server"];
  const meta = {
    changelog,
    changelogType: "markdown",
    displayName: version,
    gameVersionNames,
    releaseType: "release",
  };
  const cfForm = new FormData();
  cfForm.append("metadata", JSON.stringify(meta));
  cfForm.append("file", new Blob([fs.readFileSync(jar)]), jarName);
  const cfRes = await fetch(
    `https://minecraft.curseforge.com/api/projects/${process.env.CURSEFORGE_ID}/upload-file`,
    { method: "POST", headers: { "X-Api-Token": process.env.CURSEFORGE_TOKEN }, body: cfForm },
  );
  const cfText = await cfRes.text();
  if (!cfRes.ok) throw new Error("CurseForge " + cfRes.status + " " + cfText.slice(0, 500));
  console.log("CurseForge OK", version, gameVersionNames.length, "tags", jarName);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
